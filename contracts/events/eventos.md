# Catálogo de eventos de dominio

Exchange `helpdesk.events` (topic, durable). Routing key = tipo en minúsculas con puntos
(`TicketCreated` → `ticket.created`). Entrega *al menos una vez*: los consumidores deduplican por `event_id`.

## Sobre común

```json
{
  "event_id": "uuid", "event_type": "TicketCreated", "event_version": 1,
  "occurred_at": "2026-11-12T15:00:00.123Z", "aggregate_type": "Ticket", "aggregate_id": "uuid",
  "correlation_id": "uuid", "source": "helpdesk-api | ai-service | demo-simulado", "payload": { }
}
```

## Eventos

| Evento | Emisor | Payload principal | Consumidores |
| --- | --- | --- | --- |
| TicketCreated | helpdesk-api | ticket_id, numero, solicitante_id, cargo, area, sede_codigo, equipo_codigo, canal, impacto, bloquea_trabajo, prioridad_codigo, estado_codigo, adjuntos, longitud_descripcion, es_demo, creado_en | ai-service, lago |
| AttachmentUploaded | helpdesk-api | adjunto_id, ticket_id, mime, tamano, sha256, object_key, durante_creacion, es_demo, subido_en | ai-service (si no es de creación), lago |
| AiDiagnosisCompleted | ai-service | ticket_id, trigger, evento_origen_id, proveedor, modelo, diagnosticos[], combinado{categoria_codigo, tipo_falla_codigo, confianza, regla} | helpdesk-api, lago |
| TicketClassified | helpdesk-api | ticket_id, categoria_codigo, tipo_falla_codigo, origen (IA/MANUAL), confianza, requiere_revision | lago |
| TicketPriorityChanged | helpdesk-api | ticket_id, prioridad_anterior, prioridad_nueva, origen (REGLAS/ADMIN) | lago |
| TechnicianAssigned | helpdesk-api | asignacion_id, ticket_id, modo (AUTO/MANUAL), tecnico_recomendado_id, tecnico_asignado_id, tecnico_anterior_id, puntaje_recomendado, puntaje_asignado, motivo, pesos_version | lago |
| ReassignmentRequested | helpdesk-api | ticket_id, tecnico_id | lago |
| TicketStatusChanged | helpdesk-api | ticket_id, estado_anterior, estado_nuevo, cambiado_por, rol_actor | lago |
| ActivityLogged | helpdesk-api | actividad_id, ticket_id, tecnico_id, tipo, minutos | lago |
| TicketClosed | helpdesk-api | ticket_id, tecnico_id, categoria_codigo, tipo_falla_final, ia_categoria_propuesta, ia_tipo_falla_propuesto, reaperturas | lago |
| AiDiagnosisValidated | helpdesk-api | diagnostico_id, ticket_id, origen, resultado (ACEPTADO/CORREGIDO), tipo_falla_propuesto, tipo_falla_final | lago |
| SurveySubmitted | helpdesk-api | ticket_id, calificacion, tiene_comentario | lago |
| AgentSessionEnded | helpdesk-api | sesion_id, usuario_id, inicio, fin, duracion_seg, turnos, intencion, ticket_id, uso_voz, consentimiento, proveedor | lago |

Ningún evento lleva la descripción, el nombre ni el correo del solicitante. El ai-service obtiene la descripción por
el endpoint interno `/internal/tickets/{id}/contexto`, que Nginx no publica.

## Colas

| Cola | Enlaces | Dueño |
| --- | --- | --- |
| `ai.requests` | ticket.created, attachment.uploaded | ai-service |
| `helpdesk.ai-results` | ai.# | helpdesk-api |
| `lake.ingestion` | # | data-ingestion |
| `helpdesk.dlq` | (mensajes muertos de todas las colas) | operación |
