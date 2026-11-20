# FASE 17. Pruebas, validaciones y seguridad

## Pruebas incluidas

| Componente | Pruebas | Comando |
| --- | --- | --- |
| helpdesk-api (unitarias) | `AssignmentScorerTest` (7), `TicketStateMachineTest` (6), `FileValidatorTest` (4), `PriorityRulesTest` (parametrizada), `OutboxAndReportTest` (4) | `cd helpdesk-api && mvn test` |
| helpdesk-api (integración) | `MigrationsIT`: Flyway V1+V2 en PostgreSQL real (Testcontainers) | `mvn verify -Pintegration` |
| ai-service | 28 pruebas: clasificación heurística, combinación texto+imagen, saneamiento de imágenes, idempotencia, seguridad JWT, flujo completo del agente y confirmación explícita | `cd ai-service && pytest` |
| data-ingestion | 3 pruebas del agrupador de lotes | `cd data-ingestion && pytest` |
| data-lake | 2 pruebas: pipeline Landing → Bronze → Silver → Gold completo en Spark local, y regla crítica de calidad que detiene el paso | `cd data-lake && pytest` |
| frontend | `format.spec.ts` y compilación de producción | `cd frontend && npx ng test --watch=false && npx ng build` |

## Qué se verificó realmente al construir este entregable

Verificado y en verde:

- ai-service: 28 de 28 pruebas.
- data-ingestion: 3 de 3.
- data-lake: 2 de 2, con PySpark 4.1.4 real. El pipeline corrió sobre Parquet local, no sobre Iceberg ni SeaweedFS.
- Frontend: `ng build` de producción sin errores.
- SQL: las migraciones V1 y V2 y el script de bases de datos se ejecutaron en PostgreSQL. Las consultas SQL nativas
  del backend se probaron contra ese esquema.
- Lógica Java pura (puntaje de asignación, máquina de estados y reglas de prioridad): se compiló de forma aislada
  y se ejecutó con un arnés propio. Todas las comprobaciones pasaron.

**No verificado en este entorno** (no había acceso a Maven Central ni a Docker):

- **La compilación completa de `helpdesk-api` con Maven.** El código usa APIs estables de Spring Boot 4.1, pero
  puede requerir ajustes menores en la primera ejecución de `docker compose up --build`. Si falla, el error de
  compilación indica el archivo y la línea.
- La pila completa con Docker Compose: health checks, orden de arranque y redes.
- Iceberg + catálogo JDBC + SeaweedFS con Trino en modo real.
- Los modelos de voz (faster-whisper y Piper) y los proveedores de modelos remotos.
- El túnel de Cloudflare y Cloudflare Access.

## Plan de pruebas manuales (aceptación)

1. Login correcto e incorrecto. Tras 5 intentos fallidos, la cuenta se bloquea durante 15 minutos.
2. Un USUARIO no puede ver los tickets de otro usuario: `GET /api/v1/tickets/{id}` de otro usuario responde 404.
3. Un TECNICO solo cambia el estado de los tickets que tiene asignados.
4. Crear un ticket con 2 imágenes. Subir un archivo no válido responde 415, y uno de más de 5 MB responde 413.
5. La clasificación por IA aparece en el detalle del ticket. Con confianza baja, el ticket queda en revisión.
6. Asistente de IA: crear un ticket por voz. Sin confirmar, el ticket no se crea.
7. Reasignar un ticket con motivo; el historial y la auditoría lo reflejan.
8. Generar el escenario de demostración, ejecutar el pipeline y ver Analítica con datos.
9. Generar los reportes Excel y PDF y descargarlos.
10. Desde una red externa, a través del túnel: login, imágenes y voz.

## Controles de seguridad implementados

- Contraseñas con BCrypt; JWT de corta duración; token de refresco rotativo en una cookie HttpOnly con SameSite=Strict.
- Autorización por rol y por propiedad del ticket en el servicio, no solo en la interfaz.
- Validación del tipo real de archivo, re-codificación de imágenes, clave aleatoria y descarga solo con permiso.
- Límites de peticiones en Nginx y cabeceras CSP, X-Frame-Options, nosniff y Permissions-Policy.
- `/internal` y `/actuator` no se publican. Los servicios internos usan `X-Service-Key`.
- Los eventos no llevan datos personales. Al modelo de lenguaje solo se envían la descripción y las imágenes
  sin metadatos.
- Auditoría de las acciones sensibles. Usuario de solo lectura para Trino. Secretos en `.env`, que no se versiona.
- Ningún puerto entrante con el túnel, y Cloudflare Access para las consolas.
