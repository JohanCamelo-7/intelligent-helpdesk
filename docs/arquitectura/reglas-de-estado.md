# Reglas de cambio de estado de un ticket

Implementadas en `helpdesk-api/.../ticket/TicketStateMachine.java` y probadas en `TicketStateMachineTest`.

| Desde | Hacia | Quién | Condición |
| --- | --- | --- | --- |
| PENDIENTE | EN_CURSO | Técnico asignado o ADMIN | Debe tener técnico asignado |
| PENDIENTE | CANCELADA | ADMIN o el solicitante | — |
| EN_CURSO | FINALIZADA | Técnico asignado o ADMIN | Solución de 10 caracteres o más y tipo de falla confirmado |
| EN_CURSO | CANCELADA | ADMIN | — |
| FINALIZADA | EN_CURSO (reapertura) | ADMIN, o el solicitante | El solicitante solo hasta 7 días después del cierre. Suma una reapertura. |
| CANCELADA | — | — | Estado final |

Efectos: al iniciar se fija `fecha_inicio_atencion` (la primera vez); al finalizar se fijan `fecha_finalizacion`, la
solución y la clasificación definitiva, y cada diagnóstico de IA propuesto queda **ACEPTADO** o **CORREGIDO** según
coincida con el tipo confirmado. Cada cambio queda en `ticket_historial` y emite `TicketStatusChanged` (y
`TicketClosed` al finalizar).

## Prioridad (reglas definidas, no las decide la IA)

`PriorityRules.java`: puntaje = impacto (individual 1, área 2, sede 3) + 1 si impide trabajar.
1 → BAJA, 2 → MEDIA, 3 → ALTA, 4 → CRÍTICA. Si la categoría es REDES o ACCESOS y el impacto es de área o sede,
sube un nivel. Al clasificar, la prioridad solo puede subir, nunca bajar automáticamente. El ADMIN puede cambiarla
indicando el motivo.
