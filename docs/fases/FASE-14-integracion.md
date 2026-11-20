# FASE 14. Integración de todos los servicios

## Qué se construyó

Flujo completo: ticket → outbox → RabbitMQ → ai-service → resultado → clasificación y asignación → notificaciones;
en paralelo, todos los eventos → Landing → Bronze/Silver/Gold → Trino → tableros. Hay un identificador de correlación
de extremo a extremo (`X-Correlation-Id`), cola de mensajes muertos y deduplicación por evento en cada consumidor.

## Rutas

- `contracts/events/eventos.md`
- `helpdesk-api/src/main/java/com/helpdesk/api/shared/OutboxPublisher.java`
- `helpdesk-api/src/main/java/com/helpdesk/api/config/RabbitConfig.java`
- `ai-service/app/events.py`

## Cómo ejecutar

```bash
docker compose up -d --build
```

## Cómo probar

Crear un ticket con descripción «la impresora tiene papel atascado»: en segundos aparece la clasificación y, tras el pipeline, en Analítica.
