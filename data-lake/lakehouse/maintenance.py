"""Reconciliación con el sistema operativo y mantenimiento del lago."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from pyspark.sql import functions as F

from .config import config
from .io import LakeIO, LandingSource
from .ops import StepContext
from .quality import DQ_SCHEMA, QualityError

RECONCILE_SQL = """
    SELECT 'tickets' AS entidad, count(*) AS total FROM tickets
    UNION ALL SELECT 'tickets_finalizados', count(*) FROM tickets WHERE estado_codigo = 'FINALIZADA'
    UNION ALL SELECT 'asignaciones', count(*) FROM asignaciones
    UNION ALL SELECT 'actividades', count(*) FROM actividades
    UNION ALL SELECT 'encuestas', count(*) FROM encuestas"""


def reconcile(io: LakeIO, ctx: StepContext, conn=None, tolerance: int = 0) -> dict:
    """Compara conteos PostgreSQL vs Silver. Una diferencia indica eventos perdidos o aún en tránsito."""
    import psycopg
    own = conn is None
    conn = conn or psycopg.connect(config.helpdesk_dsn)
    try:
        with conn.cursor() as cur:
            cur.execute(RECONCILE_SQL)
            origen = {r[0]: int(r[1]) for r in cur.fetchall()}
    finally:
        if own:
            conn.close()

    def cnt(table: str, cond: str | None = None) -> int:
        df = io.read(table)
        if df is None:
            return 0
        return (df.where(cond) if cond else df).count()

    lago = {"tickets": cnt("silver.tickets"),
            "tickets_finalizados": cnt("silver.tickets", "estado_codigo = 'FINALIZADA'"),
            "asignaciones": cnt("silver.assignments"), "actividades": cnt("silver.activities"),
            "encuestas": cnt("silver.surveys")}
    now = datetime.now(timezone.utc)
    rows, diffs = [], {}
    for k, v in origen.items():
        d = v - lago.get(k, 0)
        diffs[k] = {"postgres": v, "lago": lago.get(k, 0), "diferencia": d}
        rows.append((ctx.run_id, "reconciliacion", k, "critical", abs(d) <= tolerance, abs(d), now))
    io.append("ops.dq_results", io.spark.createDataFrame(rows, DQ_SCHEMA))
    ctx.message = str(diffs)
    bad = [k for k, v in diffs.items() if abs(v["diferencia"]) > tolerance]
    if bad:
        raise QualityError(f"Diferencias entre PostgreSQL y el lago en: {', '.join(bad)} -> {diffs}")
    return diffs


def maintain(io: LakeIO, landing: LandingSource, ctx: StepContext) -> dict:
    """Retención de Landing (90 días) y mantenimiento de tablas Iceberg (expiración y compactación)."""
    limite = (datetime.now(timezone.utc) - timedelta(days=config.landing_retention_days)).date()
    viejos = []
    for k in landing.list("landing/"):
        if "/dt=" in k:
            try:
                d = datetime.strptime(k.split("/dt=")[1][:10], "%Y-%m-%d").date()
            except ValueError:
                continue
            if d < limite:
                viejos.append(k)
    landing.delete(viejos)
    result = {"landing_eliminados": len(viejos), "tablas": 0}
    if not io.local:
        older = (datetime.now(timezone.utc) - timedelta(days=7)).strftime("%Y-%m-%d %H:%M:%S")
        tables = [r.tableName for ns in ("bronze", "silver", "gold", "ops")
                  for r in io.spark.sql(f"SHOW TABLES IN lake.{ns}").select(F.concat_ws(".", F.lit(ns), "tableName").alias("tableName")).collect()]
        for t in tables:
            io.spark.sql(f"CALL lake.system.expire_snapshots(table => '{t}', older_than => TIMESTAMP '{older}', retain_last => 5)")
            if t.startswith("bronze."):
                io.spark.sql(f"CALL lake.system.rewrite_data_files(table => '{t}')")
        result["tablas"] = len(tables)
    ctx.message = str(result)
    return result
