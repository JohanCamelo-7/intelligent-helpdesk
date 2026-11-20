# FASE 12. Construcción de las capas Bronze, Silver y Gold

## Qué se construyó

Bronze: eventos tal cual, deduplicados por `event_id`, con fecha de ingesta y archivo de origen. Silver: tickets,
asignaciones, diagnósticos, sesiones y catálogos con tipos limpios y el estado actual de cada ticket. Calidad: reglas
en YAML (completitud, unicidad, dominios, orden de fechas); las filas que no cumplen van a cuarentena, y una regla
crítica detiene el paso. Gold: esquema estrella e indicadores agregados en hora de Bogotá. Mantenimiento: compactación
y expiración de snapshots.

## Rutas

- `data-lake/lakehouse/bronze.py`
- `data-lake/lakehouse/silver.py`
- `data-lake/lakehouse/quality.py`
- `data-lake/quality/rules.yaml`
- `data-lake/lakehouse/gold.py`
- `data-lake/lakehouse/maintenance.py`

## Cómo ejecutar

```bash
docker compose exec airflow python -m lakehouse.cli all   # ejecución manual de todo el pipeline
```

## Cómo probar

`cd data-lake && pytest` (pipeline completo Landing → Gold en Spark local y regla crítica que detiene el paso).
