"""Orquestación del Data Lake con Apache Airflow 3.

lakehouse_pipeline     cada hora      extract -> bronze -> silver (calidad) -> gold
lakehouse_reconcile    diario 02:00   PostgreSQL vs Silver
lakehouse_maintenance  domingo 03:00  retención de Landing y mantenimiento Iceberg

Cada tarea ejecuta `python -m lakehouse.cli <paso>` en un proceso aparte (JVM de Spark
aislada). Todas se pueden lanzar manualmente desde la interfaz de Airflow o desde el
panel ADMIN de la aplicación (API REST de Airflow). Si una regla crítica de calidad
falla en Silver, Gold no se ejecuta.
"""
from __future__ import annotations

import subprocess
import sys
from datetime import datetime, timedelta

from airflow.sdk import dag, task

DEFAULTS = {"owner": "datos", "retries": 2, "retry_delay": timedelta(minutes=2)}


def run_step(step: str, *extra: str) -> None:
    cmd = [sys.executable, "-m", "lakehouse.cli", step, *extra]
    print("Ejecutando:", " ".join(cmd))
    subprocess.run(cmd, check=True, cwd="/opt/lakehouse")


@dag(dag_id="lakehouse_pipeline", schedule="@hourly", start_date=datetime(2026, 1, 1), catchup=False,
     max_active_runs=1, default_args=DEFAULTS, tags=["lakehouse"],
     doc_md="Landing → Bronze → Silver → Gold. Ejecutar manualmente con «Trigger» para refrescar la analítica.")
def lakehouse_pipeline():
    @task
    def extract_master_data():
        run_step("extract")

    @task
    def load_bronze():
        run_step("bronze", "--days", "3")

    @task
    def build_silver():
        run_step("silver")

    @task
    def build_gold():
        run_step("gold")

    extract_master_data() >> load_bronze() >> build_silver() >> build_gold()


@dag(dag_id="lakehouse_reconcile", schedule="0 2 * * *", start_date=datetime(2026, 1, 1), catchup=False,
     default_args=DEFAULTS, tags=["lakehouse", "calidad"])
def lakehouse_reconcile():
    @task
    def reconcile():
        run_step("reconcile")

    reconcile()


@dag(dag_id="lakehouse_maintenance", schedule="0 3 * * 0", start_date=datetime(2026, 1, 1), catchup=False,
     default_args=DEFAULTS, tags=["lakehouse", "mantenimiento"])
def lakehouse_maintenance():
    @task
    def maintain():
        run_step("maintain")

    @task
    def full_reload_check():
        # Reprocesa toda la zona Landing una vez por semana: corrige cualquier evento tardío.
        run_step("bronze", "--all")

    full_reload_check() >> maintain()


lakehouse_pipeline()
lakehouse_reconcile()
lakehouse_maintenance()
