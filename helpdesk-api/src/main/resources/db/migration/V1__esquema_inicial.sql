-- =====================================================================
-- Esquema transaccional de helpdesk-api (PostgreSQL 17)
-- Cada tabla de negocio tiene updated_at para la extracción incremental
-- hacia el Data Lake. Los eventos de dominio salen por la tabla outbox.
-- =====================================================================

-- ---------- Catálogos ----------
CREATE TABLE sedes (
    id          BIGSERIAL PRIMARY KEY,
    codigo      VARCHAR(30)  NOT NULL UNIQUE,
    nombre      VARCHAR(120) NOT NULL,
    ciudad      VARCHAR(80)  NOT NULL,
    activo      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE equipos (
    id                BIGSERIAL PRIMARY KEY,
    codigo_inventario VARCHAR(40)  NOT NULL UNIQUE,
    tipo              VARCHAR(40)  NOT NULL,
    marca             VARCHAR(60),
    modelo            VARCHAR(80),
    sede_id           BIGINT       NOT NULL REFERENCES sedes(id),
    activo            BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_equipos_sede ON equipos(sede_id);

CREATE TABLE categorias (
    id          BIGSERIAL PRIMARY KEY,
    codigo      VARCHAR(30)  NOT NULL UNIQUE,
    nombre      VARCHAR(80)  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE tipos_falla (
    id            BIGSERIAL PRIMARY KEY,
    codigo        VARCHAR(40)  NOT NULL UNIQUE,
    nombre        VARCHAR(120) NOT NULL,
    categoria_id  BIGINT       NOT NULL REFERENCES categorias(id),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE especialidades (
    id             BIGSERIAL PRIMARY KEY,
    codigo         VARCHAR(40)  NOT NULL UNIQUE,
    nombre         VARCHAR(120) NOT NULL,
    categoria_id   BIGINT       NOT NULL REFERENCES categorias(id),
    tipo_falla_id  BIGINT       REFERENCES tipos_falla(id),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE prioridades (
    codigo  VARCHAR(20) PRIMARY KEY,
    nombre  VARCHAR(40) NOT NULL,
    peso    SMALLINT    NOT NULL CHECK (peso BETWEEN 1 AND 4),
    orden   SMALLINT    NOT NULL
);

CREATE TABLE estados (
    codigo  VARCHAR(20) PRIMARY KEY,
    nombre  VARCHAR(40) NOT NULL,
    orden   SMALLINT    NOT NULL,
    abierto BOOLEAN     NOT NULL
);

-- ---------- Identidad ----------
CREATE TABLE usuarios (
    id             UUID PRIMARY KEY,
    email          VARCHAR(160) NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,
    nombre         VARCHAR(120) NOT NULL,
    cargo          VARCHAR(80)  NOT NULL,
    area           VARCHAR(80),
    sede_id        BIGINT       REFERENCES sedes(id),
    rol            VARCHAR(20)  NOT NULL CHECK (rol IN ('ADMIN','TECNICO','USER')),
    activo         BOOLEAN      NOT NULL DEFAULT TRUE,
    intentos_fallidos INT       NOT NULL DEFAULT 0,
    bloqueado_hasta TIMESTAMPTZ,
    version        BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    usuario_id  UUID         NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expira_en   TIMESTAMPTZ  NOT NULL,
    revocado    BOOLEAN      NOT NULL DEFAULT FALSE,
    creado_en   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_usuario ON refresh_tokens(usuario_id);

-- ---------- Técnicos ----------
CREATE TABLE tecnicos (
    usuario_id    UUID PRIMARY KEY REFERENCES usuarios(id),
    nivel         VARCHAR(20) NOT NULL CHECK (nivel IN ('JUNIOR','SEMI_SENIOR','SENIOR')),
    disponible    BOOLEAN     NOT NULL DEFAULT TRUE,
    carga_maxima  INT         NOT NULL DEFAULT 12 CHECK (carga_maxima > 0),
    version       BIGINT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tecnico_especialidades (
    tecnico_id      UUID   NOT NULL REFERENCES tecnicos(usuario_id) ON DELETE CASCADE,
    especialidad_id BIGINT NOT NULL REFERENCES especialidades(id),
    PRIMARY KEY (tecnico_id, especialidad_id)
);

-- ---------- Tickets ----------
CREATE SEQUENCE ticket_numero_seq START 1;

CREATE TABLE tickets (
    id                     UUID PRIMARY KEY,
    numero                 VARCHAR(20)  NOT NULL UNIQUE,
    solicitante_id         UUID         NOT NULL REFERENCES usuarios(id),
    cargo_solicitante      VARCHAR(80)  NOT NULL,
    correo_solicitante     VARCHAR(160) NOT NULL,
    sede_id                BIGINT       NOT NULL REFERENCES sedes(id),
    equipo_id              BIGINT       REFERENCES equipos(id),
    descripcion            TEXT         NOT NULL CHECK (length(descripcion) BETWEEN 10 AND 4000),
    impacto                VARCHAR(20)  NOT NULL CHECK (impacto IN ('INDIVIDUAL','AREA','SEDE')),
    bloquea_trabajo        BOOLEAN      NOT NULL DEFAULT FALSE,
    canal                  VARCHAR(20)  NOT NULL CHECK (canal IN ('WEB','AGENTE','DEMO')),
    categoria_id           BIGINT       REFERENCES categorias(id),
    tipo_falla_id          BIGINT       REFERENCES tipos_falla(id),
    prioridad_codigo       VARCHAR(20)  NOT NULL REFERENCES prioridades(codigo),
    estado_codigo          VARCHAR(20)  NOT NULL REFERENCES estados(codigo),
    tecnico_id             UUID         REFERENCES tecnicos(usuario_id),
    tecnico_recomendado_id UUID         REFERENCES tecnicos(usuario_id),
    recomendacion_json     TEXT,
    requiere_revision      BOOLEAN      NOT NULL DEFAULT FALSE,
    ai_confianza           NUMERIC(4,3),
    ia_categoria_codigo    VARCHAR(30),
    ia_tipo_falla_codigo   VARCHAR(40),
    reaperturas            INT          NOT NULL DEFAULT 0,
    solucion               TEXT,
    es_demo                BOOLEAN      NOT NULL DEFAULT FALSE,
    fecha_creacion         TIMESTAMPTZ  NOT NULL,
    fecha_asignacion       TIMESTAMPTZ,
    fecha_inicio_atencion  TIMESTAMPTZ,
    fecha_finalizacion     TIMESTAMPTZ,
    version                BIGINT,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_fechas CHECK (fecha_finalizacion IS NULL OR fecha_finalizacion >= fecha_creacion)
);
CREATE INDEX ix_tickets_solicitante ON tickets(solicitante_id);
CREATE INDEX ix_tickets_tecnico     ON tickets(tecnico_id);
CREATE INDEX ix_tickets_estado      ON tickets(estado_codigo);
CREATE INDEX ix_tickets_creacion    ON tickets(fecha_creacion);
CREATE INDEX ix_tickets_revision    ON tickets(requiere_revision) WHERE requiere_revision;

CREATE TABLE ticket_historial (
    id              UUID PRIMARY KEY,
    ticket_id       UUID         NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    tipo            VARCHAR(40)  NOT NULL,
    valor_anterior  VARCHAR(200),
    valor_nuevo     VARCHAR(200),
    detalle         TEXT,
    usuario_id      UUID         REFERENCES usuarios(id),
    fecha           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_historial_ticket ON ticket_historial(ticket_id, fecha);

CREATE TABLE adjuntos (
    id               UUID PRIMARY KEY,
    ticket_id        UUID         NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    object_key       VARCHAR(300) NOT NULL UNIQUE,
    nombre_original  VARCHAR(200) NOT NULL,
    mime             VARCHAR(60)  NOT NULL,
    tamano           BIGINT       NOT NULL CHECK (tamano > 0),
    sha256           CHAR(64)     NOT NULL,
    ancho            INT,
    alto             INT,
    subido_por       UUID         NOT NULL REFERENCES usuarios(id),
    fecha            TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_adjuntos_ticket ON adjuntos(ticket_id);

CREATE TABLE diagnosticos_ia (
    id                     UUID PRIMARY KEY,
    ticket_id              UUID          NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    origen                 VARCHAR(10)   NOT NULL CHECK (origen IN ('TEXTO','IMAGEN')),
    adjunto_id             UUID          REFERENCES adjuntos(id),
    proveedor              VARCHAR(40)   NOT NULL,
    modelo                 VARCHAR(80)   NOT NULL,
    tipo_equipo            VARCHAR(60),
    categoria_codigo       VARCHAR(30),
    tipo_falla_codigo      VARCHAR(40),
    descripcion            TEXT,
    causa_probable         TEXT,
    confianza              NUMERIC(4,3)  NOT NULL CHECK (confianza BETWEEN 0 AND 1),
    concluyente            BOOLEAN       NOT NULL,
    evidencias             TEXT,
    recomendacion          TEXT,
    especialidad_sugerida  VARCHAR(60),
    estado                 VARCHAR(12)   NOT NULL DEFAULT 'PROPUESTO' CHECK (estado IN ('PROPUESTO','ACEPTADO','CORREGIDO')),
    latencia_ms            INT,
    fecha                  TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_diag_ticket ON diagnosticos_ia(ticket_id);

CREATE TABLE asignaciones (
    id                       UUID PRIMARY KEY,
    ticket_id                UUID          NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    tecnico_recomendado_id   UUID          REFERENCES tecnicos(usuario_id),
    tecnico_asignado_id      UUID          NOT NULL REFERENCES tecnicos(usuario_id),
    modo                     VARCHAR(10)   NOT NULL CHECK (modo IN ('AUTO','MANUAL')),
    puntaje_recomendado      NUMERIC(5,4),
    puntaje_asignado         NUMERIC(5,4),
    motivo                   VARCHAR(300),
    explicacion              TEXT,
    pesos_version            VARCHAR(20)   NOT NULL,
    asignado_por             UUID          REFERENCES usuarios(id),
    activa                   BOOLEAN       NOT NULL DEFAULT TRUE,
    fecha                    TIMESTAMPTZ   NOT NULL,
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_asig_ticket ON asignaciones(ticket_id);

CREATE TABLE actividades (
    id           UUID PRIMARY KEY,
    ticket_id    UUID         NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    tecnico_id   UUID         NOT NULL REFERENCES usuarios(id),
    tipo         VARCHAR(20)  NOT NULL CHECK (tipo IN ('DIAGNOSTICO','REPARACION','OBSERVACION','SEGUIMIENTO','SOLUCION')),
    descripcion  TEXT         NOT NULL,
    minutos      INT          NOT NULL DEFAULT 0 CHECK (minutos BETWEEN 0 AND 1440),
    fecha        TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_act_ticket ON actividades(ticket_id);

CREATE TABLE encuestas (
    id            UUID PRIMARY KEY,
    ticket_id     UUID        NOT NULL UNIQUE REFERENCES tickets(id) ON DELETE CASCADE,
    usuario_id    UUID        NOT NULL REFERENCES usuarios(id),
    calificacion  SMALLINT    NOT NULL CHECK (calificacion BETWEEN 1 AND 5),
    comentario    VARCHAR(1000),
    fecha         TIMESTAMPTZ NOT NULL
);

-- ---------- Agente ----------
CREATE TABLE agente_sesiones (
    id              UUID PRIMARY KEY,
    usuario_id      UUID        NOT NULL REFERENCES usuarios(id),
    inicio          TIMESTAMPTZ NOT NULL,
    fin             TIMESTAMPTZ NOT NULL,
    turnos          INT         NOT NULL,
    intencion       VARCHAR(40),
    ticket_id       UUID        REFERENCES tickets(id),
    uso_voz         BOOLEAN     NOT NULL,
    consentimiento  BOOLEAN     NOT NULL,
    proveedor       VARCHAR(40)
);

-- ---------- Notificaciones ----------
CREATE TABLE notificaciones (
    id          UUID PRIMARY KEY,
    usuario_id  UUID         NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    titulo      VARCHAR(160) NOT NULL,
    mensaje     VARCHAR(600) NOT NULL,
    ticket_id   UUID         REFERENCES tickets(id) ON DELETE SET NULL,
    leida       BOOLEAN      NOT NULL DEFAULT FALSE,
    fecha       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_notif_usuario ON notificaciones(usuario_id, leida, fecha DESC);

-- ---------- Configuración ----------
CREATE TABLE configuracion (
    clave       VARCHAR(60) PRIMARY KEY,
    valor       VARCHAR(200) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Outbox de eventos ----------
CREATE TABLE outbox (
    id              UUID PRIMARY KEY,           -- = event_id
    aggregate_type  VARCHAR(40)  NOT NULL,
    aggregate_id    VARCHAR(60)  NOT NULL,
    event_type      VARCHAR(60)  NOT NULL,
    routing_key     VARCHAR(80)  NOT NULL,
    envelope        TEXT         NOT NULL,      -- JSON completo del evento
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_pending ON outbox(occurred_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Auditoría ----------
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    usuario_id  UUID,
    accion      VARCHAR(60)  NOT NULL,
    recurso     VARCHAR(60),
    recurso_id  VARCHAR(60),
    ip          VARCHAR(60),
    detalle     VARCHAR(500),
    fecha       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_fecha ON audit_log(fecha);

-- ---------- Reportes asíncronos ----------
CREATE TABLE report_jobs (
    id             UUID PRIMARY KEY,
    solicitado_por UUID         NOT NULL REFERENCES usuarios(id),
    tipo           VARCHAR(30)  NOT NULL,
    formato        VARCHAR(10)  NOT NULL CHECK (formato IN ('CSV','XLSX','PDF')),
    parametros     TEXT         NOT NULL,
    estado         VARCHAR(12)  NOT NULL CHECK (estado IN ('EN_COLA','PROCESANDO','LISTO','ERROR')),
    object_key     VARCHAR(300),
    error          VARCHAR(500),
    creado_en      TIMESTAMPTZ  NOT NULL,
    terminado_en   TIMESTAMPTZ
);
