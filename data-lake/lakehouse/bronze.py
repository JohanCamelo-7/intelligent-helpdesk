"""BRONZE: datos tal como llegan, con trazabilidad de ingesta y sin duplicados por event_id."""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

from pyspark.sql import functions as F
from pyspark.sql import types as T

from .io import LakeIO, LandingSource
from .ops import StepContext

EVENTS_SCHEMA = T.StructType([
    T.StructField("event_id", T.StringType(), False),
    T.StructField("event_type", T.StringType(), False),
    T.StructField("event_version", T.IntegerType()),
    T.StructField("occurred_at", T.TimestampType()),
    T.StructField("aggregate_type", T.StringType()),
    T.StructField("aggregate_id", T.StringType()),
    T.StructField("correlation_id", T.StringType()),
    T.StructField("source", T.StringType()),
    T.StructField("payload", T.StringType()),          # JSON original
    T.StructField("routing_key", T.StringType()),
    T.StructField("ingested_at", T.TimestampType()),
    T.StructField("landing_key", T.StringType()),
    T.StructField("ingestion_run_id", T.StringType()),
    T.StructField("ingest_date", T.DateType()),
])

SNAPSHOT_SCHEMA = T.StructType([
    T.StructField("entity", T.StringType(), False),
    T.StructField("entity_id", T.StringType(), False),
    T.StructField("snapshot_run_id", T.StringType(), False),
    T.StructField("snapshot_ts", T.TimestampType()),
    T.StructField("data", T.StringType()),             # fila original en JSON
    T.StructField("landing_key", T.StringType()),
    T.StructField("ingestion_run_id", T.StringType()),
    T.StructField("ingest_date", T.DateType()),
])

QUARANTINE_SCHEMA = T.StructType([
    T.StructField("run_id", T.StringType()), T.StructField("layer", T.StringType()),
    T.StructField("table_name", T.StringType()), T.StructField("rule_name", T.StringType()),
    T.StructField("record", T.StringType()), T.StructField("recorded_at", T.TimestampType()),
])


def _ts(value) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(str(value).replace("Z", "+00:00"))


def _dt_window(days: int) -> set[str]:
    today = datetime.now(timezone.utc).date()
    return {(today - timedelta(days=i)).isoformat() for i in range(days + 1)}


def _keys_in_window(landing: LandingSource, prefix: str, days: int | None) -> list[str]:
    keys = landing.list(prefix)
    if days is None:
        return keys
    window = _dt_window(days)
    return [k for k in keys if any(f"/dt={d}/" in k for d in window)]


CHUNK_EVENTS = 25_000   # eventos por bloque: memoria acotada aunque Landing tenga cientos de miles


def _loaded_keys(io: LakeIO, table: str, days: int | None) -> set[str]:
    """Archivos de Landing que ya están en Bronze (cada archivo se escribe una sola vez y se carga completo).
    Se filtra por la fecha del nombre del archivo (/dt=AAAA-MM-DD/), la misma que usa la ventana de lectura."""
    existing = io.read(table)
    if existing is None:
        return set()
    if days is not None:
        patron = "/dt=(" + "|".join(sorted(_dt_window(days))) + ")/"
        existing = existing.where(F.col("landing_key").rlike(patron))
    return {r[0] for r in existing.select("landing_key").distinct().collect() if r[0]}


def _append_events(io: LakeIO, rows: list) -> int:
    df = io.spark.createDataFrame(rows, EVENTS_SCHEMA).dropDuplicates(["event_id"])
    existing = io.read("bronze.domain_events")
    if existing is not None:
        df = df.join(existing.select("event_id"), "event_id", "left_anti")
    return io.append("bronze.domain_events", df, partition_col="ingest_date")


def load_events(io: LakeIO, landing: LandingSource, ctx: StepContext, days: int | None = 3) -> int:
    """Carga incremental: solo lee los archivos de Landing nuevos y escribe en bloques de CHUNK_EVENTS."""
    ya = _loaded_keys(io, "bronze.domain_events", days)
    keys = [k for k in _keys_in_window(landing, "landing/events/", days) if k not in ya]
    rows, bad, total = [], [], 0
    now = datetime.now(timezone.utc)
    for key in keys:
        for line in landing.read_lines(key):
            if not line.strip():
                continue
            try:
                e = json.loads(line)
                rows.append((str(e["event_id"]), str(e["event_type"]), int(e.get("event_version") or 1),
                             _ts(e["occurred_at"]), e.get("aggregate_type"), e.get("aggregate_id"),
                             e.get("correlation_id"), e.get("source"), json.dumps(e.get("payload") or {}, ensure_ascii=False),
                             e.get("_routing_key"), _ts(e.get("_ingested_at")) or now, key, ctx.run_id,
                             (_ts(e.get("_ingested_at")) or now).date()))
            except (KeyError, ValueError, TypeError) as exc:
                bad.append((ctx.run_id, "bronze", "bronze.domain_events", f"parse: {exc}", line[:4000], now))
        if len(rows) >= CHUNK_EVENTS:
            total += _append_events(io, rows)
            rows = []
    if rows:
        total += _append_events(io, rows)
    if bad:
        io.append("ops.quarantine_rows", io.spark.createDataFrame(bad, QUARANTINE_SCHEMA))
        ctx.rows_rejected += len(bad)
    if total:
        ctx.link("landing/events", "bronze.domain_events", total)
    return total


def load_snapshots(io: LakeIO, landing: LandingSource, ctx: StepContext, days: int | None = 3) -> int:
    rows = []
    now = datetime.now(timezone.utc)
    for key in _keys_in_window(landing, "landing/snapshots/", days):
        entity = key.split("landing/snapshots/")[1].split("/")[0]
        for line in landing.read_lines(key):
            if not line.strip():
                continue
            r = json.loads(line)
            rows.append((entity, str(r["entity_id"]), r["_snapshot_run_id"], _ts(r["_snapshot_ts"]),
                         json.dumps({k: v for k, v in r.items() if not k.startswith("_")}, ensure_ascii=False, default=str),
                         key, ctx.run_id, now.date()))
    ctx.rows_in += len(rows)
    if not rows:
        return 0
    df = io.spark.createDataFrame(rows, SNAPSHOT_SCHEMA).dropDuplicates(["entity", "entity_id", "snapshot_run_id"])
    existing = io.read("bronze.master_snapshots")
    if existing is not None:
        df = df.join(existing.select("entity", "entity_id", "snapshot_run_id"),
                     ["entity", "entity_id", "snapshot_run_id"], "left_anti")
    n = io.append("bronze.master_snapshots", df, partition_col="entity")
    ctx.link("landing/snapshots", "bronze.master_snapshots", n)
    return n


def run(io: LakeIO, landing: LandingSource, ctx: StepContext, days: int | None = 3) -> None:
    e = load_events(io, landing, ctx, days)
    s = load_snapshots(io, landing, ctx, days)
    ctx.message = f"eventos nuevos={e}, filas de catálogo nuevas={s}"
    # Garantiza que las tablas existan aunque el primer lote venga vacío
    if not io.exists("bronze.domain_events"):
        io.append("bronze.domain_events", io.spark.createDataFrame([], EVENTS_SCHEMA), partition_col="ingest_date")
    if not io.exists("bronze.master_snapshots"):
        io.append("bronze.master_snapshots", io.spark.createDataFrame([], SNAPSHOT_SCHEMA), partition_col="entity")
