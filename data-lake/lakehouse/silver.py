"""SILVER: datos limpios, tipados, homologados, deduplicados y validados.

Se reconstruye completo en cada ejecución desde Bronze (idempotente por construcción);
a la escala del proyecto toma segundos y elimina cualquier deriva incremental.
"""
from __future__ import annotations

from pyspark.sql import DataFrame, Window
from pyspark.sql import functions as F

from .config import config
from .io import LakeIO
from .ops import StepContext
from .quality import QualityGate

# Esquemas de los payloads (contrato de eventos: contracts/events/eventos.md)
P = {
    "TicketCreated": "ticket_id string, numero string, solicitante_id string, cargo string, area string, "
                     "sede_codigo string, equipo_codigo string, canal string, impacto string, bloquea_trabajo boolean, "
                     "prioridad_codigo string, estado_codigo string, adjuntos int, longitud_descripcion int, "
                     "es_demo boolean, creado_en string",
    "TicketStatusChanged": "ticket_id string, estado_anterior string, estado_nuevo string, cambiado_por string, "
                           "rol_actor string, es_demo boolean, cambiado_en string",
    "TicketPriorityChanged": "ticket_id string, prioridad_anterior string, prioridad_nueva string, origen string, "
                             "es_demo boolean, cambiado_en string",
    "TicketClassified": "ticket_id string, categoria_codigo string, tipo_falla_codigo string, origen string, "
                        "confianza double, requiere_revision boolean, es_demo boolean, clasificado_en string",
    "TechnicianAssigned": "asignacion_id string, ticket_id string, modo string, tecnico_recomendado_id string, "
                          "tecnico_asignado_id string, tecnico_anterior_id string, puntaje_recomendado double, "
                          "puntaje_asignado double, motivo string, pesos_version string, es_demo boolean, asignado_en string",
    "ActivityLogged": "actividad_id string, ticket_id string, tecnico_id string, tipo string, minutos int, "
                      "es_demo boolean, registrado_en string",
    "TicketClosed": "ticket_id string, tecnico_id string, categoria_codigo string, tipo_falla_final string, "
                    "ia_categoria_propuesta string, ia_tipo_falla_propuesto string, reaperturas int, es_demo boolean, "
                    "finalizado_en string",
    "SurveySubmitted": "ticket_id string, calificacion int, tiene_comentario boolean, es_demo boolean, enviado_en string",
    "AttachmentUploaded": "adjunto_id string, ticket_id string, mime string, tamano long, sha256 string, "
                          "durante_creacion boolean, es_demo boolean, subido_en string",
    "AiDiagnosisCompleted": "ticket_id string, trigger string, proveedor string, modelo string, es_demo boolean, "
                            "diagnosticos array<struct<origen:string, adjunto_id:string, categoria_codigo:string, "
                            "tipo_falla_codigo:string, confianza:double, concluyente:boolean, latencia_ms:int, "
                            "tipo_equipo:string>>, "
                            "combinado struct<categoria_codigo:string, tipo_falla_codigo:string, confianza:double, regla:string>",
    "AgentSessionEnded": "sesion_id string, usuario_id string, inicio string, fin string, duracion_seg long, turnos int, "
                         "intencion string, ticket_id string, uso_voz boolean, consentimiento boolean, proveedor string",
    "ReassignmentRequested": "ticket_id string, tecnico_id string, es_demo boolean, solicitado_en string",
}

ESTADOS = ["PENDIENTE", "EN_CURSO", "FINALIZADA", "CANCELADA"]
PRIORIDADES = ["BAJA", "MEDIA", "ALTA", "CRITICA"]


def ts(col: str):
    """Fechas ISO-8601 en UTC (formato de Instant.toString) -> timestamp."""
    return F.to_timestamp(F.regexp_replace(F.col(col), "Z$", "+00:00"))


def events(bronze: DataFrame, etype: str) -> DataFrame:
    """Eventos de un tipo con su payload tipado (columnas en el nivel superior)."""
    df = bronze.where(F.col("event_type") == etype).select(
        "event_id", "occurred_at", "source", F.from_json("payload", P[etype]).alias("p"))
    return df.select("event_id", "occurred_at", "source", "p.*")


def pseudonym(col):
    return F.sha2(F.concat(F.lit(config.pseudonym_salt), F.coalesce(col.cast("string"), F.lit(""))), 256)


def latest(df: DataFrame, key: list[str], order_col: str) -> DataFrame:
    w = Window.partitionBy(*key).orderBy(F.col(order_col).desc_nulls_last())
    return df.withColumn("_rn", F.row_number().over(w)).where("_rn = 1").drop("_rn")


def snapshots(bronze_master: DataFrame | None, entity: str, schema: str) -> DataFrame | None:
    """Última foto de cada entidad maestra, con columnas tipadas."""
    if bronze_master is None:
        return None
    df = bronze_master.where(F.col("entity") == entity)
    df = latest(df, ["entity_id"], "snapshot_ts")
    return df.select("entity_id", "snapshot_ts", F.from_json("data", schema).alias("d")).select("entity_id", "snapshot_ts", "d.*")


def build(io: LakeIO, ctx: StepContext) -> dict[str, int]:
    bronze = io.read("bronze.domain_events")
    if bronze is None:
        raise RuntimeError("bronze.domain_events no existe: ejecuta primero la carga a Bronze")
    master = io.read("bronze.master_snapshots")
    bronze = bronze.dropDuplicates(["event_id"]).cache()
    ctx.rows_in = bronze.count()
    gate = QualityGate(io, ctx)
    out: dict[str, int] = {}

    # ---------------- Catálogos (última foto) ----------------
    cat_specs = {
        "sedes": "nombre string, ciudad string, activo boolean",
        "equipos": "tipo string, marca string, modelo string, sede_codigo string, activo boolean",
        "categorias": "nombre string",
        "tipos_falla": "nombre string, categoria_codigo string",
        "prioridades": "nombre string, peso int",
        "estados": "nombre string, abierto boolean",
    }
    for entity, schema in cat_specs.items():
        df = snapshots(master, entity, schema)
        if df is not None:
            df = df.withColumnRenamed("entity_id", "codigo")
            if entity == "equipos":
                df = df.withColumn("tipo", F.upper(F.trim("tipo")))
            out[f"silver.{entity}"] = _save(io, ctx, gate, f"silver.{entity}", df, "bronze.master_snapshots")

    # ---------------- Usuarios (seudonimizados) ----------------
    users = snapshots(master, "usuarios", "cargo string, area string, sede_codigo string, rol string, activo boolean")
    if users is not None:
        users = users.select(pseudonym(F.col("entity_id")).alias("usuario_hash"), "cargo",
                             F.initcap(F.trim("area")).alias("area"), "sede_codigo", "rol", "activo", "snapshot_ts")
        out["silver.users"] = _save(io, ctx, gate, "silver.users", users, "bronze.master_snapshots")

    # ---------------- Técnicos con historial (SCD tipo 2) ----------------
    if master is not None:
        tec = (master.where(F.col("entity") == "tecnicos")
               .select("entity_id", "snapshot_ts", F.from_json("data", "nombre string, nivel string, carga_maxima int, "
                       "especialidades string, categorias string, activo boolean, disponible boolean").alias("d"))
               .select(F.col("entity_id").alias("tecnico_id"), "snapshot_ts", "d.*")
               .withColumn("_hash", F.sha2(F.concat_ws("|", "nombre", "nivel", "carga_maxima", "especialidades", "activo"), 256)))
        w = Window.partitionBy("tecnico_id").orderBy("snapshot_ts")
        tec = (tec.withColumn("_prev", F.lag("_hash").over(w))
               .where(F.col("_prev").isNull() | (F.col("_prev") != F.col("_hash")))
               .withColumn("valido_desde", F.col("snapshot_ts"))
               .withColumn("valido_hasta", F.lead("snapshot_ts").over(w))
               .withColumn("es_actual", F.col("valido_hasta").isNull())
               .drop("_hash", "_prev", "snapshot_ts"))
        out["silver.technicians"] = _save(io, ctx, gate, "silver.technicians", tec, "bronze.master_snapshots")

    # ---------------- Eventos de negocio ----------------
    created = events(bronze, "TicketCreated").withColumn("creado_en", ts("creado_en"))
    status = events(bronze, "TicketStatusChanged").withColumn("cambiado_en", ts("cambiado_en"))
    prio = events(bronze, "TicketPriorityChanged").withColumn("cambiado_en", ts("cambiado_en"))
    classified = events(bronze, "TicketClassified").withColumn("clasificado_en", ts("clasificado_en"))
    assigned = events(bronze, "TechnicianAssigned").withColumn("asignado_en", ts("asignado_en"))
    activity = events(bronze, "ActivityLogged").withColumn("registrado_en", ts("registrado_en"))
    closed = events(bronze, "TicketClosed").withColumn("finalizado_en", ts("finalizado_en"))
    surveys = events(bronze, "SurveySubmitted").withColumn("enviado_en", ts("enviado_en"))
    attach = events(bronze, "AttachmentUploaded").withColumn("subido_en", ts("subido_en"))
    ai = events(bronze, "AiDiagnosisCompleted")
    sessions = events(bronze, "AgentSessionEnded")
    review = events(bronze, "ReassignmentRequested")

    # Historial de estados
    hist = status.select("event_id", "ticket_id", "estado_anterior", "estado_nuevo", "rol_actor", "cambiado_en",
                         F.coalesce("es_demo", F.lit(False)).alias("es_demo"))
    out["silver.ticket_status_history"] = _save(io, ctx, gate, "silver.ticket_status_history", hist, "bronze.domain_events")

    # Asignaciones
    asg = assigned.select("asignacion_id", "ticket_id", "modo", "tecnico_recomendado_id", "tecnico_asignado_id",
                          "tecnico_anterior_id", "puntaje_recomendado", "puntaje_asignado", "pesos_version", "asignado_en",
                          F.coalesce("es_demo", F.lit(False)).alias("es_demo"))
    asg = latest(asg, ["asignacion_id"], "asignado_en")
    out["silver.assignments"] = _save(io, ctx, gate, "silver.assignments", asg, "bronze.domain_events")

    # Actividades
    act = latest(activity.select("actividad_id", "ticket_id", "tecnico_id", F.upper("tipo").alias("tipo"),
                                 F.greatest(F.lit(0), F.coalesce("minutos", F.lit(0))).alias("minutos"), "registrado_en",
                                 F.coalesce("es_demo", F.lit(False)).alias("es_demo")), ["actividad_id"], "registrado_en")
    out["silver.activities"] = _save(io, ctx, gate, "silver.activities", act, "bronze.domain_events")

    # Encuestas (una por ticket: la primera)
    sv = surveys.select("ticket_id", "calificacion", "tiene_comentario", "enviado_en",
                        F.coalesce("es_demo", F.lit(False)).alias("es_demo"))
    sv = sv.withColumn("_rn", F.row_number().over(Window.partitionBy("ticket_id").orderBy("enviado_en"))).where("_rn = 1").drop("_rn")
    out["silver.surveys"] = _save(io, ctx, gate, "silver.surveys", sv, "bronze.domain_events")

    # Adjuntos (metadatos; la imagen vive en el bucket operativo)
    att = latest(attach.select("adjunto_id", "ticket_id", "mime", "tamano", "sha256", "durante_creacion", "subido_en",
                               F.coalesce("es_demo", F.lit(False)).alias("es_demo")), ["adjunto_id"], "subido_en")
    out["silver.attachments"] = _save(io, ctx, gate, "silver.attachments", att, "bronze.domain_events")

    # Sesiones del agente (usuario seudonimizado)
    ses = latest(sessions.select("sesion_id", pseudonym(F.col("usuario_id")).alias("usuario_hash"),
                                 ts("inicio").alias("inicio"), ts("fin").alias("fin"), "duracion_seg", "turnos",
                                 "intencion", "ticket_id", "uso_voz", "consentimiento", "proveedor"), ["sesion_id"], "fin")
    out["silver.agent_sessions"] = _save(io, ctx, gate, "silver.agent_sessions", ses, "bronze.domain_events")

    # Diagnósticos de IA (uno por fila)
    diag = (ai.select("event_id", "occurred_at", "ticket_id", "trigger", "proveedor", "modelo",
                      F.coalesce("es_demo", F.lit(False)).alias("es_demo"), F.posexplode("diagnosticos").alias("pos", "d"))
            .select(F.concat_ws("-", "event_id", F.col("pos").cast("string")).alias("diagnostico_id"), "ticket_id", "trigger",
                    "proveedor", "modelo", "es_demo", F.col("occurred_at").alias("diagnosticado_en"),
                    F.col("d.origen").alias("origen"), F.col("d.adjunto_id").alias("adjunto_id"),
                    F.col("d.categoria_codigo").alias("categoria_propuesta"),
                    F.col("d.tipo_falla_codigo").alias("tipo_falla_propuesto"),
                    F.least(F.lit(1.0), F.greatest(F.lit(0.0), F.col("d.confianza"))).alias("confianza"),
                    F.coalesce(F.col("d.concluyente"), F.lit(False)).alias("concluyente"),
                    F.col("d.latencia_ms").alias("latencia_ms")))
    out["silver.ai_diagnoses"] = _save(io, ctx, gate, "silver.ai_diagnoses", diag, "bronze.domain_events")

    # ---------------- Ticket integrado (estado actual reconstruido desde eventos) ----------------
    base = latest(created, ["ticket_id"], "creado_en").select(
        "ticket_id", "numero", pseudonym(F.col("solicitante_id")).alias("solicitante_hash"), "cargo", "sede_codigo",
        F.upper(F.trim("equipo_codigo")).alias("equipo_codigo"), "canal", "impacto", "bloquea_trabajo",
        F.col("prioridad_codigo").alias("prioridad_inicial"), "creado_en",
        F.coalesce("es_demo", F.lit(False)).alias("es_demo"))

    last_class = latest(classified, ["ticket_id"], "clasificado_en").select(
        "ticket_id", "categoria_codigo", "tipo_falla_codigo")
    ia_class = latest(classified.where(F.col("origen") == "IA"), ["ticket_id"], "clasificado_en").select(
        "ticket_id", F.col("confianza").alias("ia_confianza"))
    revision = (classified.where(F.col("requiere_revision")).select("ticket_id")
                .unionByName(review.select("ticket_id")).distinct().withColumn("requirio_revision", F.lit(True)))
    last_prio = latest(prio, ["ticket_id"], "cambiado_en").select("ticket_id", F.col("prioridad_nueva").alias("prioridad_actual"))
    last_status = latest(status, ["ticket_id"], "cambiado_en").select("ticket_id", F.col("estado_nuevo").alias("estado_codigo"))
    st_agg = status.groupBy("ticket_id").agg(
        F.min(F.when(F.col("estado_nuevo") == "EN_CURSO", F.col("cambiado_en"))).alias("inicio_atencion_en"),
        F.max(F.when(F.col("estado_nuevo") == "CANCELADA", F.col("cambiado_en"))).alias("cancelado_en"),
        F.sum(F.when((F.col("estado_anterior") == "FINALIZADA") & (F.col("estado_nuevo") == "EN_CURSO"), 1).otherwise(0))
        .alias("reaperturas"))
    last_close = latest(closed, ["ticket_id"], "finalizado_en").select(
        "ticket_id", "finalizado_en", "tipo_falla_final", F.col("categoria_codigo").alias("categoria_final"),
        "ia_tipo_falla_propuesto", "ia_categoria_propuesta")
    asg_w = Window.partitionBy("ticket_id").orderBy("asignado_en")
    asg_agg = (assigned.withColumn("_n", F.row_number().over(asg_w))
               .groupBy("ticket_id").agg(
                   F.min("asignado_en").alias("asignado_en"),
                   F.count("*").alias("asignaciones"),
                   F.max_by("tecnico_asignado_id", "asignado_en").alias("tecnico_id"),
                   F.max(F.when(F.col("_n") == 1, F.col("modo"))).alias("primer_modo"),
                   F.max(F.when(F.col("_n") == 1, F.col("tecnico_asignado_id"))).alias("primer_tecnico")))
    rating = sv.select("ticket_id", "calificacion")

    t = (base.join(last_class, "ticket_id", "left").join(ia_class, "ticket_id", "left")
         .join(revision, "ticket_id", "left").join(last_prio, "ticket_id", "left")
         .join(last_status, "ticket_id", "left").join(st_agg, "ticket_id", "left")
         .join(last_close, "ticket_id", "left").join(asg_agg, "ticket_id", "left").join(rating, "ticket_id", "left"))
    t = (t.withColumn("estado_codigo", F.coalesce("estado_codigo", F.lit("PENDIENTE")))
         .withColumn("prioridad_codigo", F.coalesce("prioridad_actual", "prioridad_inicial"))
         .withColumn("categoria_codigo", F.coalesce("categoria_final", "categoria_codigo"))
         .withColumn("tipo_falla_codigo", F.coalesce("tipo_falla_final", "tipo_falla_codigo"))
         # Un ticket reabierto y aún abierto no tiene fecha de finalización vigente
         .withColumn("finalizado_en", F.when(F.col("estado_codigo") == "FINALIZADA", F.col("finalizado_en")))
         .withColumn("reaperturas", F.coalesce("reaperturas", F.lit(0)))
         .withColumn("asignaciones", F.coalesce("asignaciones", F.lit(0)))
         .withColumn("requirio_revision", F.coalesce("requirio_revision", F.lit(False)))
         .withColumn("asignado_por_ia", F.coalesce(F.col("primer_modo") == "AUTO", F.lit(False)))
         .drop("prioridad_actual", "categoria_final", "tipo_falla_final"))
    t = t.select("ticket_id", "numero", "solicitante_hash", "cargo", "sede_codigo", "equipo_codigo", "canal", "impacto",
                 "bloquea_trabajo", "categoria_codigo", "tipo_falla_codigo", "prioridad_inicial", "prioridad_codigo",
                 "estado_codigo", "tecnico_id", "primer_tecnico", "primer_modo", "asignaciones", "asignado_por_ia",
                 "requirio_revision", "ia_confianza", "ia_categoria_propuesta", "ia_tipo_falla_propuesto",
                 "creado_en", "asignado_en", "inicio_atencion_en", "finalizado_en", "cancelado_en", "reaperturas",
                 "calificacion", "es_demo")
    out["silver.tickets"] = _save(io, ctx, gate, "silver.tickets", t, "bronze.domain_events")
    bronze.unpersist()
    ctx.message = ", ".join(f"{k}={v}" for k, v in out.items())
    return out


def _save(io: LakeIO, ctx: StepContext, gate: QualityGate, table: str, df: DataFrame, source: str) -> int:
    valid = gate.apply(table, df)
    n = io.overwrite(table, valid)
    ctx.link(source, table, n)
    return n
