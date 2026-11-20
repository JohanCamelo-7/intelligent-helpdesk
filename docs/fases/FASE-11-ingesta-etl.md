# FASE 11. Implementación de procesos de ingesta y ETL/ELT

## Qué se construyó

`data-ingestion` consume la cola `lake.ingestion` (todos los eventos) y escribe lotes JSONL en
`s3://lake/landing/events/dt=AAAA-MM-DD/`, confirmando los mensajes solo después de escribir el lote (al menos una vez).
`lakehouse.extract` toma además instantáneas de las tablas maestras de PostgreSQL. Airflow orquesta: `lakehouse_pipeline`
(cada hora), `lakehouse_reconcile` (diario: compara conteos de PostgreSQL contra Silver) y `lakehouse_maintenance` (semanal).

## Rutas

- `data-ingestion/ingestion/`
- `data-lake/lakehouse/extract.py`
- `data-lake/dags/lakehouse_dags.py`

## Cómo ejecutar

```bash
docker compose up -d data-ingestion airflow
# Airflow: http://localhost:8081 → lakehouse_pipeline → Trigger
```

## Cómo probar

`cd data-ingestion && pytest`; en Airflow, ver la ejecución en verde y `ops.runs` en Trino.
