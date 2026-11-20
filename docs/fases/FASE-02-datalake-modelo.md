# FASE 2. Diseño de la arquitectura Data Lake y modelo de datos

## Qué se construyó

Arquitectura Medallion sobre Apache Iceberg (catálogo JDBC en PostgreSQL `lake_catalog`, archivos en SeaweedFS
bucket `lake`): Landing (JSONL por fecha de ingesta) → Bronze (eventos crudos, solo se agregan) → Silver (entidades
limpias y estado actual) → Gold (esquema estrella: hechos de tickets, asignaciones, diagnósticos de IA, sesiones
del agente; dimensiones de fecha, técnico, categoría, sede y prioridad) + tablas `ops` (ejecuciones, calidad,
linaje y cuarentena). El catálogo de tablas genera el diccionario de datos.

## Rutas

- `data-lake/catalog/tablas.yaml`
- `data-lake/catalog/generar_diccionario.py`
- `docs/datos/diccionario-de-datos.md`
- `docs/datos/reglas-de-contabilizacion.md`

## Cómo ejecutar

```bash
cd data-lake && python catalog/generar_diccionario.py   # regenera el diccionario
```

## Cómo probar

Abrir `docs/datos/diccionario-de-datos.md` y comprobar que cada tabla tiene dueño, grano y columnas.
