"""Trazabilidad del pipeline: ejecuciones, linaje y filas en cuarentena (esquema ops)."""
from __future__ import annotations

import traceback
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone

from pyspark.sql import types as T

from .io import LakeIO

RUNS_SCHEMA = T.StructType([
    T.StructField("run_id", T.StringType()), T.StructField("step", T.StringType()),
    T.StructField("status", T.StringType()), T.StructField("started_at", T.TimestampType()),
    T.StructField("finished_at", T.TimestampType()), T.StructField("rows_in", T.LongType()),
    T.StructField("rows_out", T.LongType()), T.StructField("rows_rejected", T.LongType()),
    T.StructField("message", T.StringType()),
])
LINEAGE_SCHEMA = T.StructType([
    T.StructField("run_id", T.StringType()), T.StructField("source_table", T.StringType()),
    T.StructField("target_table", T.StringType()), T.StructField("rows", T.LongType()),
    T.StructField("recorded_at", T.TimestampType()),
])


def new_run_id() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:6]


class StepContext:
    def __init__(self, io: LakeIO, run_id: str, step: str) -> None:
        self.io, self.run_id, self.step = io, run_id, step
        self.rows_in = self.rows_out = self.rows_rejected = 0
        self.lineage: list[tuple[str, str, int]] = []
        self.message = ""

    def link(self, source: str, target: str, rows: int) -> None:
        self.lineage.append((source, target, int(rows)))
        self.rows_out += int(rows)


@contextmanager
def step(io: LakeIO, run_id: str, name: str):
    ctx = StepContext(io, run_id, name)
    started = datetime.now(timezone.utc)
    status, msg = "OK", ""
    try:
        yield ctx
        msg = ctx.message
    except Exception as exc:
        status, msg = "ERROR", f"{type(exc).__name__}: {exc}\n{traceback.format_exc(limit=3)}"[:2000]
        raise
    finally:
        spark = io.spark
        io.append("ops.pipeline_runs", spark.createDataFrame(
            [(run_id, name, status, started, datetime.now(timezone.utc), ctx.rows_in, ctx.rows_out, ctx.rows_rejected, msg)],
            RUNS_SCHEMA))
        if ctx.lineage:
            now = datetime.now(timezone.utc)
            io.append("ops.lineage", spark.createDataFrame(
                [(run_id, s, t, n, now) for s, t, n in ctx.lineage], LINEAGE_SCHEMA))
