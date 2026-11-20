# 4. Hito 3 · Solución integrada y tarea de punta a punta

## 4.1 Cómo se integran las piezas

El usuario solo ve la aplicación web (Angular, servida por Nginx). Detrás, una única API REST (`/api/v1`) coordina
la base transaccional, la IA y el lago:

| Paso de la tarea | Endpoint | Componentes que intervienen |
| --- | --- | --- |
| Iniciar sesión | `POST /api/v1/auth/login` | helpdesk-api, PostgreSQL (BCrypt, JWT) |
| Reportar la falla | `POST /api/v1/tickets` | helpdesk-api → PostgreSQL (ticket + historial + outbox) |
| Clasificar | (asíncrono) | outbox → RabbitMQ → ai-service → RabbitMQ → helpdesk-api |
| Asignar técnico | automático o `POST /api/v1/tickets/{id}/asignacion` | módulo de asignación (puntaje con desempeño leído de Gold) |
| Atender y cerrar | `POST /api/v1/tickets/{id}/estado`, `/actividades` | máquina de estados; cada cambio emite un evento |
| Calificar | `POST /api/v1/tickets/{id}/encuesta` | helpdesk-api → PostgreSQL |
| Preguntar en lenguaje natural | `POST /api/v1/agent/lago/preguntar` | ai-service → guardia SQL → Trino (usuario de solo lectura) → Gold |
| Ver en la analítica nacional | `POST /api/v1/analitica/pipeline/ejecutar`, `GET /api/v1/analitica/resumen?sede=…` | Airflow → Spark → Iceberg → Trino |

## 4.2 La tarea de punta a punta

**Historia de usuario.** Una docente de UNIMINUTO Girardot reporta que la impresora de la sala de profesores no
imprime y que eso bloquea la entrega de notas. El sistema clasifica el caso, lo asigna a un técnico, el técnico lo
atiende y lo cierra, la docente califica la atención y la coordinación nacional de TI ve el caso en la analítica,
filtrada por la sede Girardot.

**Ejecución** (requiere haber generado el escenario de demostración):

```bash
./pruebas/hito3-flujo-integrado.sh
```

El script usa la misma API que la aplicación web: inicia sesión con una cuenta de Girardot, crea el ticket con la
impresora de la sede, espera la clasificación y la asignación (si no hay asignación automática, la hace el
administrador), inicia sesión como el técnico asignado, registra la reparación, cierra el ticket, envía la encuesta,
lanza el pipeline y espera a que Gold muestre el ticket **FINALIZADA**. Escribe el resultado en
`pruebas/resultados/hito3-AAAAMMDD-HHMM.md`.

## 4.3 Prueba contra la meta

| Medición | Meta (definida antes) | Criterio |
| --- | --- | --- |
| Registro del ticket | < 2 s | C10 |
| Clasificación por IA | < 60 s | — |
| Categoría propuesta | IMPRESION | — |
| Técnico asignado | < 2 min | — |
| Trazabilidad | ≥ 4 cambios en el historial y ≥ 5 eventos | C3 |
| Eventos en Bronze = eventos en el outbox | Iguales, sin duplicados | C5 |
| Ticket finalizado visible en Gold | < 10 min desde que se lanza el pipeline | C9 |
| Consulta analítica por sede | < 3 s | C10 |
| Tarea completa, del reporte a la analítica | < 15 min | C9 |

La columna «Observado» y el veredicto los escribe el script en el archivo de resultados; esos valores se copian en
la tabla del informe técnico (`informe-tecnico.md`, sección 5).

## 4.4 Hacerlo en vivo frente al jurado

La misma tarea se puede mostrar con la interfaz:

1. Entrar como `usuarioN@demo.helpdesk.local` de Girardot (ver `usuarios-y-contrasenas.csv`) → **Nuevo ticket**.
2. Entrar como el técnico asignado → **Mi trabajo** → iniciar, registrar actividad, finalizar.
3. Volver como la docente → calificar.
4. Entrar como administrador → **Analítica (Data Lake)** → **Pipeline del Data Lake → Ejecutar ahora** → filtrar por la sede
   Girardot y la fecha de hoy.
5. En **Preguntar al Data Lake**: «¿cuántos tickets finalizados hay hoy en Girardot?» y mostrar el SQL generado.
6. En Trino: `SELECT numero, sede_codigo, estado_codigo, min_resolucion FROM gold.fact_tickets WHERE numero = 'SOP-…'`.
