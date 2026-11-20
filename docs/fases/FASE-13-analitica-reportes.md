# FASE 13. Analítica, Business Intelligence y reportes

## Qué se construyó

Trino 482 consulta la capa Gold (usuario de solo lectura `analytics`). El backend expone indicadores con filtros
(fechas, sede, categoría, técnico, prioridad, estado, incluir demo). La interfaz muestra tableros: volumen, tiempos,
cumplimiento por prioridad, acierto de IA, carga por técnico y reincidencia. Los reportes se generan en Excel o PDF de
forma asíncrona y se guardan en `helpdesk-reports`. El generador de demostración crea historia de 60 días.

## Rutas

- `helpdesk-api/src/main/java/com/helpdesk/api/analytics/`
- `frontend/src/app/pages/analytics.ts`
- `frontend/src/app/pages/reports.ts`
- `helpdesk-api/src/main/java/com/helpdesk/api/demo/DemoService.java`
- `infrastructure/trino/`

## Cómo ejecutar

```bash
# Configuración → Generar escenario; luego Pipeline → Ejecutar ahora
```

## Cómo probar

Analítica muestra datos tras la primera ejecución del pipeline; Reportes → Generar → Descargar .xlsx/.pdf. `OutboxAndReportTest`.
