"""Purga de tickets del Data Lake (por ejemplo, una carga de demostración duplicada).

Borra de Bronze todos los eventos de los tickets con número >= `numero_min` y los quita también de los
archivos de Landing, para que una recarga completa (`bronze --all`) no los vuelva a insertar. Silver y Gold
se reconstruyen completos en la siguiente ejecución del pipeline, así que no requieren cambios.

Uso: python -m lakehouse.cli purge --desde-numero SOP-030001
"""
from __future__ import annotations

import json

from pyspark.sql import functions as F

from .io import LakeIO, LandingSource
from .ops import StepContext

TABLA = "bronze.domain_events"


def ticket_ids(io: LakeIO, numero_min: str) -> set[str]:
    eventos = io.read(TABLA)
    if eventos is None:
        return set()
    filas = (eventos.where(F.col("event_type") == "TicketCreated")
             .where(F.get_json_object("payload", "$.numero") >= F.lit(numero_min))
             .select("aggregate_id").distinct().collect())
    return {r[0] for r in filas if r[0]}


def _es_de(evento: dict, ids: set[str]) -> bool:
    payload = evento.get("payload") or {}
    return evento.get("aggregate_id") in ids or (isinstance(payload, dict) and payload.get("ticket_id") in ids)


def purgar(io: LakeIO, landing: LandingSource, ctx: StepContext, numero_min: str) -> dict:
    ids = ticket_ids(io, numero_min)
    if not ids:
        ctx.message = f"No hay tickets con número >= {numero_min}"
        return {"tickets": 0}

    # 1) Bronze: DELETE transaccional en Iceberg (en modo local se reescribe el Parquet)
    antes = io.read(TABLA).count()
    vista = io.spark.createDataFrame([(i,) for i in sorted(ids)], "id string")
    if io.local:
        restante = io.read(TABLA).join(vista, F.col("aggregate_id") == F.col("id"), "left_anti") \
            .join(vista, F.get_json_object("payload", "$.ticket_id") == F.col("id"), "left_anti")
        io.overwrite(TABLA, restante)
    else:
        vista.createOrReplaceTempView("_purga_ids")
        io.spark.sql(f"DELETE FROM {io.ident(TABLA)} WHERE aggregate_id IN (SELECT id FROM _purga_ids) "
                     f"OR get_json_object(payload, '$.ticket_id') IN (SELECT id FROM _purga_ids)")
    borrados_bronze = antes - io.read(TABLA).count()

    # 2) Landing: quita esas líneas de los archivos (las demás se conservan byte a byte) y borra los vacíos
    reescritos, eliminados, lineas = 0, [], 0
    for key in landing.list("landing/events/"):
        conservar, quitadas = [], 0
        for line in landing.read_lines(key):
            if not line.strip():
                continue
            try:
                quitar = _es_de(json.loads(line), ids)
            except ValueError:
                quitar = False          # una línea inválida ya fue a cuarentena: se deja como está
            if quitar:
                quitadas += 1
            else:
                conservar.append(line)
        if quitadas:
            lineas += quitadas
            if conservar:
                landing.write_lines(key, conservar)
                reescritos += 1
            else:
                eliminados.append(key)
    if eliminados:
        landing.delete(eliminados)

    r = {"tickets": len(ids), "eventos_bronze_borrados": borrados_bronze, "lineas_landing_quitadas": lineas,
         "archivos_landing_eliminados": len(eliminados), "archivos_landing_reescritos": reescritos}
    ctx.rows_in = len(ids)
    ctx.rows_out = borrados_bronze
    ctx.message = str(r)
    return r
