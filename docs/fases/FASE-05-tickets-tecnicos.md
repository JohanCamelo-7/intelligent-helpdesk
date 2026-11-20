# FASE 5. Gestión de tickets y técnicos

## Qué se construyó

Tickets con número legible, máquina de estados (PENDIENTE → EN_CURSO → FINALIZADA, con cancelación y reapertura),
prioridad por reglas, historial, actividades del técnico, encuesta de satisfacción y notificaciones. Los técnicos
tienen nivel, especialidades, disponibilidad y carga máxima. Cada cambio emite un evento por outbox.

## Rutas

- `helpdesk-api/src/main/java/com/helpdesk/api/ticket/`
- `helpdesk-api/src/main/java/com/helpdesk/api/technician/`
- `helpdesk-api/src/main/java/com/helpdesk/api/notification/`
- `docs/arquitectura/reglas-de-estado.md`

## Cómo ejecutar

```bash
cd helpdesk-api && mvn test
```

## Cómo probar

`TicketStateMachineTest`, `PriorityRulesTest`. En la interfaz: crear un ticket como usuario, iniciarlo y cerrarlo como técnico.
