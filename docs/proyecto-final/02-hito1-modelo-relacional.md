# 2. Hito 1 · Modelo relacional y operación de integridad

El componente transaccional es **PostgreSQL 17**. El esquema completo está en
`helpdesk-api/src/main/resources/db/migration/V1__esquema_inicial.sql` (Flyway lo aplica al arrancar) y los catálogos
y las tres sedes en `V2__datos_referencia.sql`. El diccionario de datos está en `docs/datos/diccionario-de-datos.md`.

## 2.1 Diagrama entidad-relación

GitHub dibuja este diagrama automáticamente (versión en imagen: [img/modelo-entidad-relacion.png](img/modelo-entidad-relacion.png)). Se muestran las claves y las columnas que explican las relaciones.

```mermaid
erDiagram
    SEDES ||--o{ EQUIPOS : "tiene"
    SEDES ||--o{ USUARIOS : "agrupa"
    SEDES ||--o{ TICKETS : "recibe"
    CATEGORIAS ||--o{ TIPOS_FALLA : "agrupa"
    CATEGORIAS ||--o{ ESPECIALIDADES : "define"
    USUARIOS ||--o| TECNICOS : "es"
    TECNICOS ||--o{ TECNICO_ESPECIALIDADES : "domina"
    ESPECIALIDADES ||--o{ TECNICO_ESPECIALIDADES : ""
    USUARIOS ||--o{ TICKETS : "reporta"
    TECNICOS |o--o{ TICKETS : "atiende"
    EQUIPOS |o--o{ TICKETS : "afectado en"
    CATEGORIAS |o--o{ TICKETS : "clasifica"
    TIPOS_FALLA |o--o{ TICKETS : "precisa"
    PRIORIDADES ||--o{ TICKETS : ""
    ESTADOS ||--o{ TICKETS : ""
    TICKETS ||--o{ TICKET_HISTORIAL : "registra"
    TICKETS ||--o{ ADJUNTOS : "incluye"
    TICKETS ||--o{ DIAGNOSTICOS_IA : "recibe"
    TICKETS ||--o{ ASIGNACIONES : "se asigna"
    TICKETS ||--o{ ACTIVIDADES : "se trabaja"
    TICKETS ||--o| ENCUESTAS : "se califica"
    TECNICOS ||--o{ ASIGNACIONES : ""
    USUARIOS ||--o{ NOTIFICACIONES : "recibe"

    SEDES {
        bigint id PK
        varchar codigo UK "SEDE_BOGOTA, SEDE_BELLO, SEDE_GIRARDOT"
        varchar nombre
        varchar ciudad
        boolean activo
    }
    EQUIPOS {
        bigint id PK
        varchar codigo_inventario UK "PC-GIR-001"
        varchar tipo
        bigint sede_id FK
    }
    USUARIOS {
        uuid id PK
        varchar email UK
        varchar password_hash "BCrypt"
        varchar rol "ADMIN | TECNICO | USER"
        bigint sede_id FK
    }
    TECNICOS {
        uuid usuario_id PK,FK
        varchar nivel "JUNIOR | SEMI_SENIOR | SENIOR"
        int carga_maxima "CHECK > 0"
    }
    TICKETS {
        uuid id PK
        varchar numero UK "SOP-000123"
        uuid solicitante_id FK
        bigint sede_id FK
        bigint equipo_id FK
        text descripcion "CHECK 10..4000"
        varchar impacto "INDIVIDUAL | AREA | SEDE"
        varchar prioridad_codigo FK
        varchar estado_codigo FK
        uuid tecnico_id FK
        timestamptz fecha_creacion
        timestamptz fecha_finalizacion "CHECK >= fecha_creacion"
        boolean es_demo
    }
    TICKET_HISTORIAL {
        uuid id PK
        uuid ticket_id FK "ON DELETE CASCADE"
        varchar tipo
        varchar valor_anterior
        varchar valor_nuevo
    }
    ASIGNACIONES {
        uuid id PK
        uuid ticket_id FK
        uuid tecnico_asignado_id FK
        varchar modo "AUTO | MANUAL"
        numeric puntaje_asignado
    }
    ACTIVIDADES {
        uuid id PK
        uuid ticket_id FK
        varchar tipo "DIAGNOSTICO | REPARACION | ..."
        int minutos "CHECK 0..1440"
    }
    ENCUESTAS {
        uuid id PK
        uuid ticket_id FK,UK
        smallint calificacion "CHECK 1..5"
    }
    DIAGNOSTICOS_IA {
        uuid id PK
        uuid ticket_id FK
        numeric confianza "CHECK 0..1"
        varchar estado "PROPUESTO | ACEPTADO | CORREGIDO"
    }
    OUTBOX {
        uuid id PK "= event_id"
        varchar aggregate_id
        varchar event_type
        text envelope
        timestamptz published_at "NULL = pendiente"
    }
```

`OUTBOX` no tiene clave foránea a `TICKETS` a propósito: guarda eventos de varios agregados (tickets, técnicos,
usuarios) y debe conservarse aunque un registro de negocio se archive.

## 2.2 Decisiones del modelo

| Decisión | Justificación |
| --- | --- |
| 3FN en las tablas de negocio; catálogos (`estados`, `prioridades`, `categorias`, `tipos_falla`) como tablas con FK | Un estado o una prioridad inválidos se rechazan en la base, no solo en la aplicación |
| `CHECK` en dominios cerrados (`rol`, `impacto`, `canal`, `nivel`, calificación 1–5, minutos 0–1440) | La base es la última línea de defensa si un cliente salta las validaciones de la API |
| `ck_fechas`: la fecha de cierre no puede ser anterior a la de creación | Protege los indicadores de tiempo de resolución |
| `ON DELETE CASCADE` solo en los hijos que no tienen sentido sin su ticket (historial, adjuntos, actividades, encuestas) | Borrar una sede, un usuario o un técnico con tickets **se rechaza** |
| `cargo_solicitante` y `correo_solicitante` copiados en el ticket | Desnormalización deliberada: el ticket conserva cómo era la persona al reportar, aunque luego cambie de cargo |
| Tabla `outbox` escrita en la **misma transacción** que el cambio de negocio | El ticket y su evento se guardan juntos o no se guarda nada; nunca hay un ticket sin evento para el lago |
| `version` en `tickets` y `usuarios` | Bloqueo optimista: dos técnicos no pueden sobrescribirse un cambio |
| Índices por solicitante, técnico, estado, fecha y uno parcial para `requiere_revision` | Las consultas operativas filtran por esas columnas |

## 2.3 Operación de integridad demostrada

El script `pruebas/hito1-integridad.sql` se ejecuta contra la base real dentro de una transacción que termina en
`ROLLBACK` (no deja datos):

```bash
docker compose exec -T postgres psql -U postgres -d helpdesk -v ON_ERROR_STOP=1 < pruebas/hito1-integridad.sql
```

| # | Prueba | Resultado esperado |
| --- | --- | --- |
| 1 | Registrar ticket + historial + evento de outbox | Se guardan los tres |
| 2 | **Falla el evento a mitad de la operación** | El ticket también se revierte (atomicidad) |
| 3 | Número de ticket duplicado | Rechazado por `UNIQUE` |
| 4 | Ticket en una sede que no existe | Rechazado por FK |
| 5 | Estado fuera del catálogo | Rechazado por FK |
| 6 | Fecha de cierre anterior a la creación | Rechazado por `CHECK ck_fechas` |
| 7 | Encuesta con calificación 7 | Rechazado por `CHECK` |
| 8 | Borrar una sede que tiene tickets | Rechazado por FK |
| 9 | Borrar el ticket de prueba | Su historial se borra en cascada, sin huérfanos |

Además revisa los datos cargados (tickets sin solicitante, con sede inexistente, cerrados sin fecha o sin evento
de creación: todos deben dar 0) y muestra el reparto de tickets por sede.

**Resultado verificado** (PostgreSQL 17 con las migraciones V1 y V2): las 9 pruebas dieron **OK** y las 4 revisiones
de integridad referencial dieron **0**.
