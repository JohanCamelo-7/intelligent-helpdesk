# Reglas de contabilización

Aplican a la capa Gold, los tableros de **Analítica** y todos los reportes.

- **Zona horaria:** todas las fechas de negocio se calculan en America/Bogota. Las marcas de tiempo se guardan en UTC.
- **Fecha de un ticket:** el día en que se creó. Los indicadores de cierre usan la fecha de finalización.
- **Tiempo hasta asignación** = primera asignación − creación.
- **Tiempo de atención** = inicio de atención − asignación.
- **Tiempo de resolución** = finalización − creación. Solo se calcula para tickets FINALIZADOS.
- **Cancelados:** cuentan en el total, pero se excluyen de los promedios de resolución.
- **Reincidente:** mismo equipo (o el mismo solicitante, si no hay equipo) y misma categoría, creado dentro de los
  30 días siguientes al cierre del ticket anterior de ese grupo.
- **Acierto de la IA:** el tipo de falla propuesto por la IA coincide con el que el técnico confirma al cerrar.
  Los diagnósticos no concluyentes se reportan aparte.
- **Asignación automática efectiva:** la primera asignación fue automática, no hubo reasignaciones y el ticket se finalizó.
- **Pendientes del día:** tickets creados antes de la medianoche y no finalizados ni cancelados a esa hora. Es una
  aproximación: un ticket que se cerró y se reabrió después cuenta como abierto entre ambos momentos.
- **Desempeño del técnico (valor D):** tickets de la categoría resueltos sin reapertura ÷ tickets resueltos, en 90 días.
- **Datos de demostración:** se marcan `es_demo = true` y se pueden excluir en todos los filtros.

Indicadores **operativos** (panel operativo y panel del técnico) salen de PostgreSQL en tiempo real.
Indicadores **históricos** (Analítica y reportes) salen de la capa Gold, que se actualiza cada hora. La interfaz
rotula ambos tipos.
