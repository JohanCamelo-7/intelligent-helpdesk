"""Control de calidad declarativo (quality/rules.yaml)."""
from __future__ import annotations

import os
from datetime import datetime, timezone
from functools import reduce
from pathlib import Path

import yaml
from pyspark.sql import DataFrame
from pyspark.sql import functions as F
from pyspark.sql import types as T

from .io import LakeIO
from .ops import StepContext

DQ_SCHEMA = T.StructType([
    T.StructField("run_id", T.StringType()), T.StructField("table_name", T.StringType()),
    T.StructField("rule_name", T.StringType()), T.StructField("severity", T.StringType()),
    T.StructField("passed", T.BooleanType()), T.StructField("failed_rows", T.LongType()),
    T.StructField("checked_at", T.TimestampType()),
])

RULES_PATH = Path(os.getenv("QUALITY_RULES", Path(__file__).resolve().parents[1] / "quality" / "rules.yaml"))


class QualityError(RuntimeError):
    pass


def load_rules(path: Path = RULES_PATH) -> dict:
    with open(path, encoding="utf-8") as fh:
        return (yaml.safe_load(fh) or {}).get("tables", {})


class QualityGate:
    def __init__(self, io: LakeIO, ctx: StepContext, rules: dict | None = None) -> None:
        self.io, self.ctx = io, ctx
        self.rules = rules if rules is not None else load_rules()

    def apply(self, table: str, df: DataFrame) -> DataFrame:
        spec = self.rules.get(table)
        if not spec:
            return df
        now = datetime.now(timezone.utc)
        results: list[tuple] = []
        df = df.cache()
        total = df.count()

        row_rules = spec.get("row_rules", [])
        flags = {r["name"]: F.coalesce(F.expr(r["expr"]), F.lit(False)) for r in row_rules}
        checked = df.select("*", *[c.alias(f"__ok_{n}") for n, c in flags.items()]).cache()
        critical = [f"__ok_{r['name']}" for r in row_rules if r.get("severity") == "critical"]

        for r in row_rules:
            failed = checked.where(~F.col(f"__ok_{r['name']}")).count()
            results.append((self.ctx.run_id, table, r["name"], r.get("severity", "warning"), failed == 0, failed, now))

        if critical:
            ok_all = reduce(lambda a, b: a & b, [F.col(c) for c in critical])
            bad = checked.where(~ok_all)
            n_bad = bad.count()
            if n_bad:
                failed_rule = F.coalesce(*[F.when(~F.col(c), F.lit(c.replace("__ok_", ""))) for c in critical])
                q = bad.select(F.lit(self.ctx.run_id).alias("run_id"), F.lit("silver").alias("layer"),
                               F.lit(table).alias("table_name"), failed_rule.alias("rule_name"),
                               F.to_json(F.struct(*[F.col(c) for c in df.columns])).alias("record"),
                               F.current_timestamp().alias("recorded_at"))
                self.io.append("ops.quarantine_rows", q)
                self.ctx.rows_rejected += n_bad
            valid = checked.where(ok_all)
        else:
            n_bad = 0
            valid = checked
        valid = valid.drop(*[f"__ok_{n}" for n in flags])

        errors = []
        for r in spec.get("table_rules", []):
            if r["type"] == "unique":
                dups = valid.count() - valid.select(*r["columns"]).distinct().count()
                passed, failed = dups == 0, dups
            elif r["type"] == "max_rejected_ratio":
                ratio = (n_bad / total) if total else 0.0
                passed, failed = ratio <= float(r["value"]), n_bad
            else:
                raise ValueError(f"Regla de tabla desconocida: {r['type']}")
            results.append((self.ctx.run_id, table, r["name"], r.get("severity", "warning"), passed, int(failed), now))
            if not passed and r.get("severity") == "critical":
                errors.append(f"{table}.{r['name']} (filas: {failed})")

        self.io.append("ops.dq_results", self.io.spark.createDataFrame(results, DQ_SCHEMA))
        if errors:
            raise QualityError("Fallaron reglas críticas de calidad: " + "; ".join(errors))
        return valid
