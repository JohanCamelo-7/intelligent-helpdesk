# FASE 3. PostgreSQL y migraciones

## Qué se construyó

PostgreSQL 17 con 3 bases: `helpdesk` (transaccional), `airflow` y `lake_catalog`. Cada una tiene su propio rol, y
`lake_reader` es de solo lectura. Las migraciones de Flyway crean el esquema (usuarios, tickets, adjuntos, historial,
diagnósticos de IA, técnicos, asignaciones, notificaciones, outbox, eventos procesados, auditoría, reportes) y los
datos de referencia (6 categorías, 18 tipos de falla, 12 especialidades, 3 sedes, 10 equipos, configuración de asignación).

## Rutas

- `infrastructure/postgres/init/01-databases.sh`
- `helpdesk-api/src/main/resources/db/migration/V1__esquema_inicial.sql`
- `helpdesk-api/src/main/resources/db/migration/V2__datos_referencia.sql`

## Cómo ejecutar

```bash
docker compose up -d postgres
docker compose up -d helpdesk-api   # Flyway aplica V1 y V2 al iniciar
```

## Cómo probar

`docker compose exec postgres psql -U helpdesk -d helpdesk -c '\dt'` y `mvn verify -Pintegration` (MigrationsIT).
