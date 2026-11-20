"""Ejecución manual o desde Airflow:

    python -m lakehouse.cli extract      # PostgreSQL -> Landing (catálogos y usuarios)
    python -m lakehouse.cli bronze       # Landing -> Bronze   [--days N | --all]
    python -m lakehouse.cli silver       # Bronze  -> Silver   (con reglas de calidad)
    python -m lakehouse.cli gold         # Silver  -> Gold
    python -m lakehouse.cli all          # todo lo anterior en orden
    python -m lakehouse.cli reconcile    # PostgreSQL vs Silver
    python -m lakehouse.cli maintain     # retención y mantenimiento Iceberg
    python -m lakehouse.cli purge --desde-numero SOP-030001   # borra esos tickets de Bronze y Landing
"""
from __future__ import annotations

import argparse
import logging
import sys

from . import bronze, gold, maintenance, purge, silver
from .extract import extract
from .io import LakeIO, LandingSource, build_spark
from .ops import new_run_id, step

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("lakehouse")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Pipeline del Data Lake")
    ap.add_argument("step", choices=["extract", "bronze", "silver", "gold", "all", "reconcile", "maintain", "purge"])
    ap.add_argument("--days", type=int, default=3, help="días de Landing a (re)procesar en Bronze")
    ap.add_argument("--all", action="store_true", help="reprocesar toda la zona Landing")
    ap.add_argument("--run-id", default=None)
    ap.add_argument("--desde-numero", default=None, help="purge: número de ticket inicial (p. ej. SOP-030001)")
    args = ap.parse_args(argv)
    if args.step == "purge" and not args.desde_numero:
        ap.error("purge requiere --desde-numero (por ejemplo SOP-030001)")
    run_id = args.run_id or new_run_id()
    days = None if args.all else args.days
    landing = LandingSource()

    spark = build_spark(app=f"lakehouse-{args.step}")
    io = LakeIO(spark)
    try:
        steps = ["extract", "bronze", "silver", "gold"] if args.step == "all" else [args.step]
        for s in steps:
            log.info("== %s (run %s)", s, run_id)
            with step(io, run_id, s) as ctx:
                if s == "extract":
                    counts = extract(run_id, landing)
                    ctx.rows_out = sum(counts.values())
                    ctx.message = str(counts)
                elif s == "bronze":
                    bronze.run(io, landing, ctx, days)
                elif s == "silver":
                    silver.build(io, ctx)
                elif s == "gold":
                    gold.build(io, ctx)
                elif s == "reconcile":
                    maintenance.reconcile(io, ctx)
                elif s == "maintain":
                    maintenance.maintain(io, landing, ctx)
                elif s == "purge":
                    purge.purgar(io, landing, ctx, args.desde_numero)
                log.info("   %s", ctx.message)
        return 0
    finally:
        spark.stop()


if __name__ == "__main__":
    sys.exit(main())
