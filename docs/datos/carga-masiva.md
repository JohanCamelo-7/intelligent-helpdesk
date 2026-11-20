# Prueba de carga del Data Lake (30.000 tickets)

El generador de escenarios (`helpdesk-api/.../demo/DemoService.java`) crea datos históricos simulados recorriendo el
**mismo camino que los datos reales**: servicios de dominio → PostgreSQL → outbox → RabbitMQ → `data-ingestion` →
Landing → Bronze → Silver → Gold → Trino → tableros. Así se prueba la carga, el almacenamiento, el procesamiento y
las consultas del lago con un volumen considerable.

Todo queda marcado `es_demo = true` y canal `DEMO`, y el diagnóstico de IA es simulado. **No son datos reales**:
no deben presentarse como resultados de la organización.

## Qué genera el escenario «Carga masiva»

| Elemento | Cantidad | Detalle |
| --- | --- | --- |
| Tickets | 30.000 | Del 1 de enero de 2024 a hoy, procesados en orden cronológico |
| Usuarios | 100 | `usuario1…100@demo.helpdesk.local`; nombres únicos, 11 áreas con cargos, sedes ≈ 55 % Bogotá / 30 % Bello / 15 % Girardot |
| Técnicos | 25 | `tecnico1…25@demo.helpdesk.local`; 5 senior, 10 semi-senior y 10 junior, con especialidades que cubren las 6 categorías |
| Administradores | 2 | `admin1…2@demo.helpdesk.local`; se reparten las asignaciones manuales (≈ 60 / 40) |
| Equipos | ≈ 125 | Un PC por usuario (`PC-BOG-101`…), impresoras (Bogotá 4, Bello 3, Girardot 2) y proyectores (3/2/1) |
| Eventos de dominio | ≈ 300.000–400.000 | Unos 10–13 por ticket (creación, IA, clasificación, asignación, estados, actividades, cierre, encuesta) |

Contraseña de todas las cuentas de demostración: `Demo1234!`.

### Cómo se reparte (realismo)

- **Tendencia:** la demanda crece de forma gradual (≈ +85 % entre el inicio y el final del rango).
- **Semana y hora:** más tickets los lunes y muy pocos el fin de semana; picos de 8 a 12 y de 14 a 17.
- **Estacionalidad:** enero más alto, diciembre más bajo; festivos fijos de Colombia casi sin actividad.
- **Incidentes masivos:** unos 30, de 8 a 35 reportes de red, correo o sistema en la misma sede en menos de 3 horas.
- **Usuarios:** unos reportan mucho más que otros (distribución de cola larga), como en una organización real.
- **Reincidencia:** ≈ 8 % repite una falla del mismo usuario y equipo en los 30 días siguientes a su cierre.
- **Ciclo de vida:** ≈ 3 % cancelados, ≈ 5 % reabiertos, ≈ 70 % con encuesta (25 % con comentario); los tickets de
  las últimas 48 horas pueden quedar pendientes o en curso.
- **IA simulada:** el acierto sube de ≈ 72 % a ≈ 90 % a lo largo del periodo, para que se vea la tendencia.
- **Sin duplicados:** cada descripción es única (frase base + contexto + consecuencia), y los correos, códigos de
  inventario y números de ticket son únicos.
- **Determinista:** la misma semilla produce los mismos datos. Volver a ejecutar reutiliza personas y equipos
  existentes, pero **agrega** otros 30.000 tickets.

## Cómo ejecutarla

1. Entra como administrador → **Configuración → Escenario de demostración**.
2. Pulsa **Carga masiva (30.000 tickets desde 2024)** y luego **Generar escenario**.
3. La barra muestra el avance (se actualiza cada 5 s). Según el equipo tarda **entre 20 y 60 minutos**; puedes
   seguir usando la aplicación. Si cierras la página, al volver retoma el seguimiento.
4. Al terminar, espera a que el outbox quede vacío (los eventos se publican a ≈ 2.000 por segundo):

   ```bash
   docker compose exec postgres psql -U postgres -d helpdesk -c \
     "SELECT count(*) FILTER (WHERE published_at IS NULL) AS pendientes, count(*) AS total FROM outbox;"
   ```

   Cuando `pendientes` sea 0, ejecuta **Pipeline del Data Lake → Ejecutar ahora** (o espera la ejecución horaria).
   Con este volumen el pipeline tarda unos minutos más que con datos pequeños.

## Validación por capa

Consultas con Trino (en Git Bash funcionan tal cual):

```bash
docker compose exec trino trino --user admin --catalog lake --execute "<consulta>"
```

**1. Volumen en cada capa**

```sql
SELECT 'bronze.domain_events' AS tabla, count(*) AS filas FROM bronze.domain_events
UNION ALL SELECT 'silver.tickets', count(*) FROM silver.tickets
UNION ALL SELECT 'gold.fact_tickets', count(*) FROM gold.fact_tickets
UNION ALL SELECT 'gold.fact_atenciones', count(*) FROM gold.fact_atenciones
UNION ALL SELECT 'gold.fact_asignaciones', count(*) FROM gold.fact_asignaciones
```

**2. Consistencia con PostgreSQL** (deben coincidir; el pipeline diario `lakehouse_reconcile` lo comprueba solo):

```bash
docker compose exec postgres psql -U postgres -d helpdesk -c "SELECT count(*) FROM tickets WHERE es_demo;"
```
```sql
SELECT count(*) FROM gold.fact_tickets WHERE es_demo
```

**3. Sin duplicados en Bronze** (debe devolver 0 filas):

```sql
SELECT event_id, count(*) FROM bronze.domain_events GROUP BY event_id HAVING count(*) > 1
```

**4. Distribución en el tiempo** (debe verse el crecimiento y diciembre más bajo):

```sql
SELECT year(fecha_creacion) AS anio, month(fecha_creacion) AS mes, count(*) AS tickets
FROM gold.fact_tickets GROUP BY 1, 2 ORDER BY 1, 2
```

**5. Reparto por estado, categoría y sede**

```sql
SELECT estado_codigo, count(*) FROM gold.fact_tickets GROUP BY 1 ORDER BY 2 DESC;
SELECT categoria_codigo, sede_codigo, count(*) FROM gold.fact_tickets GROUP BY 1, 2 ORDER BY 3 DESC
```

**6. Calidad y tiempos del pipeline** (rendimiento de cada paso):

```sql
SELECT step, status, rows_in, rows_out, rows_rejected,
       date_diff('second', started_at, finished_at) AS segundos
FROM ops.pipeline_runs ORDER BY started_at DESC LIMIT 12;
SELECT table_name, rule_name, passed, failed_rows FROM ops.dq_results ORDER BY 1, 2
```

**7. Consultas analíticas pesadas** (tiempo de respuesta de Trino sobre Gold):

```sql
SELECT tecnico_id, categoria_codigo, count(*) AS tickets, avg(min_resolucion) AS min_prom,
       avg(CAST(ia_acerto AS integer)) AS acierto_ia
FROM gold.fact_tickets WHERE estado_codigo = 'FINALIZADA'
GROUP BY 1, 2 ORDER BY 3 DESC
```

Para medir el tiempo de una consulta, agrega `--debug` al comando de Trino o revisa la consola en
http://localhost:8082 (usuario `admin`).

## Qué mirar si algo no cuadra

| Síntoma | Revisión |
| --- | --- |
| La barra no avanza | `docker compose logs helpdesk-api --tail=50` (los tickets omitidos se registran con el motivo) |
| Outbox con pendientes que no bajan | `docker compose logs helpdesk-api | grep "No se pudieron publicar"` y estado de RabbitMQ (http://localhost:15672) |
| Bronze con menos eventos que el outbox | `docker compose logs data-ingestion --tail=50` |
| El pipeline falla o se queda sin memoria | Airflow (http://localhost:8081) → `lakehouse_pipeline` → registro de la tarea. Puedes subir `SPARK_DRIVER_MEMORY` en `.env` (por defecto `2g`) |
| Analítica sigue en cero | Los tableros leen Gold: confirma que el pipeline terminó en verde y que el filtro de fechas incluye 2024–2026 |

Para borrar todos los datos y empezar de cero: `docker compose down -v` y luego `./scripts/iniciar.sh`. Si conservas
tu `.env`, la base se vuelve a crear con las mismas contraseñas.
