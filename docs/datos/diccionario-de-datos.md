# Diccionario de datos del Data Lake

Generado automáticamente desde `data-lake/catalog/tablas.yaml` y `data-lake/quality/rules.yaml`. No editar a mano.

## Capa Bronze

### `bronze.domain_events`

Todos los eventos de dominio tal como llegan (sobre + payload JSON original). Solo inserción, sin duplicados por event_id.

- **Fuente:** landing/events (data-ingestion <- RabbitMQ <- outbox de helpdesk-api y ai-service)
- **Partición:** ingest_date
- **Retención:** 3 años
- **Datos personales:** Sí (payload puede contener identificadores de usuario; no contiene correos ni nombres)
- **Responsable:** Equipo de datos (proyecto)

| Columna | Descripción |
| --- | --- |
| `event_id` | UUID del evento (clave de deduplicación) |
| `event_type` | Tipo de evento (TicketCreated, TicketClosed, AiDiagnosisCompleted...) |
| `occurred_at` | Momento en que ocurrió el hecho de negocio (UTC) |
| `aggregate_id` | Identificador del agregado (normalmente el ticket) |
| `correlation_id` | Identificador de correlación de la petición original |
| `source` | Servicio emisor |
| `payload` | JSON original del evento |
| `ingested_at` | Momento de llegada a Landing |
| `landing_key` | Archivo de Landing de origen (linaje) |
| `ingestion_run_id` | Ejecución del pipeline que lo cargó |

### `bronze.master_snapshots`

Fotos completas por hora de catálogos, usuarios (sin correo ni nombre) y técnicos.

- **Fuente:** PostgreSQL (rol lake_reader) vía lakehouse.extract
- **Partición:** entity
- **Retención:** 3 años
- **Datos personales:** Bajo (id de usuario, cargo y área)
- **Responsable:** Equipo de datos (proyecto)

| Columna | Descripción |
| --- | --- |
| `entity` | Entidad (usuarios, tecnicos, sedes, equipos, categorias, tipos_falla, prioridades, estados) |
| `entity_id` | Clave natural de la entidad |
| `snapshot_run_id` | Ejecución de extracción |
| `snapshot_ts` | Momento de la foto |
| `data` | Fila original en JSON |

## Capa Silver

### `silver.tickets`

Estado integrado de cada ticket reconstruido desde los eventos (clasificación vigente, estado, fechas, técnico, reaperturas, encuesta).

- **Fuente:** bronze.domain_events
- **Retención:** 3 años
- **Datos personales:** Seudonimizado (solicitante_hash = SHA-256 con sal)
- **Responsable:** Equipo de datos (proyecto)

| Columna | Descripción |
| --- | --- |
| `ticket_id` | Identificador del ticket |
| `numero` | Número legible (SOP-000123) |
| `solicitante_hash` | Solicitante seudonimizado |
| `categoria_codigo` | Categoría vigente (la confirmada al cierre tiene prioridad) |
| `tipo_falla_codigo` | Tipo de falla vigente |
| `prioridad_codigo` | Prioridad vigente |
| `estado_codigo` | Estado vigente |
| `tecnico_id` | Técnico asignado vigente |
| `asignado_por_ia` | La primera asignación fue automática |
| `requirio_revision` | Pasó por revisión humana |
| `ia_tipo_falla_propuesto` | Tipo de falla propuesto por la IA |
| `creado_en / asignado_en / inicio_atencion_en / finalizado_en` | Hitos del ciclo de vida (UTC) |
| `reaperturas` | Veces que se reabrió |
| `es_demo` | Registro de un escenario de demostración |

Reglas de calidad:

- `ticket_id_no_nulo` (critical): `ticket_id IS NOT NULL`
- `creado_en_no_nulo` (critical): `creado_en IS NOT NULL`
- `estado_valido` (critical): `estado_codigo IN ('PENDIENTE','EN_CURSO','FINALIZADA','CANCELADA')`
- `prioridad_valida` (critical): `prioridad_codigo IN ('BAJA','MEDIA','ALTA','CRITICA')`
- `cierre_posterior_a_creacion` (critical): `finalizado_en IS NULL OR finalizado_en >= creado_en`
- `finalizado_con_fecha` (warning): `estado_codigo <> 'FINALIZADA' OR finalizado_en IS NOT NULL`
- `finalizado_con_tipo_falla` (warning): `estado_codigo <> 'FINALIZADA' OR tipo_falla_codigo IS NOT NULL`
- `en_curso_con_tecnico` (warning): `estado_codigo <> 'EN_CURSO' OR tecnico_id IS NOT NULL`
- `sede_informada` (warning): `sede_codigo IS NOT NULL`
- `ticket_id_unico` (critical): unique
- `rechazo_maximo_5pct` (critical): max_rejected_ratio

### `silver.technicians`

Historial de técnicos (SCD tipo 2) por cambios de nombre, nivel, carga máxima o especialidades.

- **Fuente:** bronze.master_snapshots
- **Datos personales:** Nombre del técnico (necesario para reportes de rendimiento; visible solo para ADMIN)
- **Responsable:** Equipo de datos (proyecto)

| Columna | Descripción |
| --- | --- |
| `tecnico_id` | Identificador |
| `nivel` | JUNIOR, SEMI_SENIOR o SENIOR |
| `especialidades` | Códigos de especialidad |
| `valido_desde / valido_hasta` | Vigencia de la versión |
| `es_actual` | Versión vigente |

Reglas de calidad:

- `nivel_valido` (critical): `nivel IN ('JUNIOR','SEMI_SENIOR','SENIOR')`
- `una_version_por_inicio` (critical): unique

### `silver.ai_diagnoses`

Un registro por diagnóstico de IA (texto o imagen) con confianza y si fue concluyente.

- **Fuente:** bronze.domain_events (AiDiagnosisCompleted)
- **Datos personales:** No
- **Responsable:** Equipo de datos (proyecto)

Reglas de calidad:

- `confianza_0_a_1` (critical): `confianza BETWEEN 0 AND 1`
- `origen_valido` (critical): `origen IN ('TEXTO','IMAGEN')`
- `concluyente_con_categoria` (warning): `NOT concluyente OR categoria_propuesta IS NOT NULL`

### `silver.assignments`

Asignaciones y reasignaciones con técnico recomendado, asignado, modo y puntajes.

- **Fuente:** bronze.domain_events (TechnicianAssigned)
- **Datos personales:** No
- **Responsable:** Equipo de datos (proyecto)

Reglas de calidad:

- `modo_valido` (critical): `modo IN ('AUTO','MANUAL')`
- `tecnico_asignado` (critical): `tecnico_asignado_id IS NOT NULL`
- `puntaje_en_rango` (warning): `puntaje_asignado IS NULL OR (puntaje_asignado BETWEEN 0 AND 1)`
- `asignacion_unica` (critical): unique

## Capa Gold

### `gold.fact_tickets`

Hecho principal. Granularidad = un ticket. Medidas de tiempos, reincidencia y efectividad de IA.

- **Fuente:** silver.tickets, silver.technicians
- **Datos personales:** No (solo claves sustitutas y seudónimos)
- **Consumidores:** analytics-service (Trino), reportes
- **Responsable:** Equipo de datos (proyecto)

| Columna | Descripción |
| --- | --- |
| `fecha_creacion_key` | Clave de DimFecha (yyyymmdd, America/Bogota) |
| `min_hasta_asignacion` | Minutos entre creación y primera asignación |
| `min_hasta_atencion` | Minutos entre asignación e inicio de atención |
| `min_resolucion` | Minutos entre creación y finalización (solo finalizados) |
| `es_reincidente` | Mismo equipo/solicitante y categoría dentro de 30 días tras un cierre |
| `ia_acerto` | El tipo propuesto por la IA coincide con el confirmado (null si no aplica) |
| `asignacion_efectiva` | Asignación automática sin reasignación en un ticket finalizado |

### `gold.fact_atenciones`

Hecho de actividades técnicas. Granularidad = una actividad registrada.

- **Fuente:** silver.activities
- **Responsable:** Equipo de datos (proyecto)

### `gold.fact_asignaciones`

Hecho de asignaciones. Granularidad = una asignación o reasignación.

- **Fuente:** silver.assignments
- **Responsable:** Equipo de datos (proyecto)

### `gold.fact_diagnosticos_ia`

Hecho de diagnósticos. Resultado ACERTADO, CORREGIDO, PENDIENTE o NO_CONCLUYENTE.

- **Fuente:** silver.ai_diagnoses, silver.tickets
- **Responsable:** Equipo de datos (proyecto)

### `gold.fact_snapshot_diario`

Foto diaria de tickets abiertos al final de cada día (evolución de pendientes).

- **Fuente:** silver.tickets
- **Responsable:** Equipo de datos (proyecto)

### `gold.dim_fecha`

Calendario con semana ISO, día de la semana y festivos de Colombia.

- **Responsable:** Equipo de datos (proyecto)

### `gold.dim_tecnico`

Dimensión de técnicos con historial (SCD2).

- **Responsable:** Equipo de datos (proyecto)

### `gold.agg_rendimiento_tecnico`

Desempeño por técnico y categoría en 90 días (insumo D del algoritmo de asignación).

- **Responsable:** Equipo de datos (proyecto)

## Capa Ops

### `ops.pipeline_runs`

Registro de cada paso del pipeline (estado, filas leídas, escritas y rechazadas, mensaje).

- **Responsable:** Equipo de datos (proyecto)

### `ops.dq_results`

Resultado de cada regla de calidad y de la reconciliación diaria.

- **Responsable:** Equipo de datos (proyecto)

### `ops.lineage`

Linaje por ejecución (tabla origen -> tabla destino, filas).

- **Responsable:** Equipo de datos (proyecto)

### `ops.quarantine_rows`

Registros rechazados por formato o por reglas críticas, con la regla incumplida.

- **Responsable:** Equipo de datos (proyecto)
