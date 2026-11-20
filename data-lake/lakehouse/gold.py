"""GOLD: modelo estrella y agregados listos para Business Intelligence.

Reglas de contabilización (docs/datos/reglas-de-contabilizacion.md):
  - Fechas de negocio en America/Bogota (spark.sql.session.timeZone).
  - min_hasta_atencion = inicio de atención − asignación; min_resolucion = finalización − creación.
  - Reincidente: mismo equipo (o mismo solicitante si no hay equipo) y categoría, creado dentro de
    30 días después del cierre del ticket anterior de ese grupo.
  - ia_acerto: el tipo de falla propuesto por la IA coincide con el confirmado al cerrar.
  - asignacion_efectiva: primera asignación AUTO, sin reasignaciones y ticket finalizado.
"""
from __future__ import annotations

from datetime import date, datetime, timedelta

from pyspark.sql import DataFrame, Window
from pyspark.sql import functions as F
from pyspark.sql import types as T

from .config import config
from .io import LakeIO
from .ops import StepContext

DIAS = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]
MESES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
         "noviembre", "diciembre"]


def key(*cols) -> F.Column:
    """Clave sustituta estable (misma entrada -> misma clave en cada reconstrucción)."""
    return F.xxhash64(*[F.coalesce(F.col(c).cast("string"), F.lit("∅")) for c in cols])


def minutes(a: str, b: str) -> F.Column:
    return F.round((F.col(b).cast("double") - F.col(a).cast("double")) / 60.0, 2)


def date_key(col) -> F.Column:
    return F.date_format(col, "yyyyMMdd").cast("int")


def dim_fecha(spark, start: date, end: date) -> DataFrame:
    try:
        import holidays
        festivos = holidays.country_holidays("CO", years=range(start.year, end.year + 1))
    except Exception:  # noqa: BLE001 - sin la librería, no se marcan festivos
        festivos = {}
    rows, d = [], start
    while d <= end:
        iso = d.isocalendar()
        rows.append((int(d.strftime("%Y%m%d")), d, d.year, d.month, MESES[d.month - 1], iso[1], d.day,
                     d.isoweekday(), DIAS[d.weekday()], d.isoweekday() >= 6, d in festivos))
        d += timedelta(days=1)
    schema = T.StructType([
        T.StructField("fecha_key", T.IntegerType()), T.StructField("fecha", T.DateType()),
        T.StructField("anio", T.IntegerType()), T.StructField("mes", T.IntegerType()),
        T.StructField("nombre_mes", T.StringType()), T.StructField("semana_iso", T.IntegerType()),
        T.StructField("dia", T.IntegerType()), T.StructField("dia_semana", T.IntegerType()),
        T.StructField("nombre_dia", T.StringType()), T.StructField("es_fin_semana", T.BooleanType()),
        T.StructField("es_festivo", T.BooleanType()),
    ])
    return spark.createDataFrame(rows, schema)


def _req(io: LakeIO, table: str) -> DataFrame:
    df = io.read(table)
    if df is None:
        raise RuntimeError(f"{table} no existe: ejecuta primero la capa Silver")
    return df


def _opt(io: LakeIO, table: str, schema: str) -> DataFrame:
    df = io.read(table)
    return df if df is not None else io.spark.createDataFrame([], schema)


def build(io: LakeIO, ctx: StepContext, today: date | None = None) -> dict[str, int]:
    spark = io.spark
    today = today or datetime.now().date()
    out: dict[str, int] = {}

    def save(table: str, df: DataFrame, sources: list[str]) -> None:
        n = io.overwrite(table, df)
        for s in sources:
            ctx.link(s, table, n)
        out[table] = n

    tickets = _req(io, "silver.tickets").cache()
    ctx.rows_in = tickets.count()
    asg = _opt(io, "silver.assignments", "asignacion_id string, ticket_id string, modo string, tecnico_recomendado_id string, "
               "tecnico_asignado_id string, tecnico_anterior_id string, puntaje_recomendado double, puntaje_asignado double, "
               "pesos_version string, asignado_en timestamp, es_demo boolean")
    act = _opt(io, "silver.activities", "actividad_id string, ticket_id string, tecnico_id string, tipo string, minutos int, "
               "registrado_en timestamp, es_demo boolean")
    diag = _opt(io, "silver.ai_diagnoses", "diagnostico_id string, ticket_id string, trigger string, proveedor string, "
                "modelo string, es_demo boolean, diagnosticado_en timestamp, origen string, adjunto_id string, "
                "categoria_propuesta string, tipo_falla_propuesto string, confianza double, concluyente boolean, latencia_ms int")
    tec = _opt(io, "silver.technicians", "tecnico_id string, nombre string, nivel string, carga_maxima int, especialidades string, "
               "categorias string, activo boolean, disponible boolean, valido_desde timestamp, valido_hasta timestamp, es_actual boolean")

    # ------------------------------------------------------------ dimensiones
    cats = _opt(io, "silver.categorias", "codigo string, snapshot_ts timestamp, nombre string")
    save("gold.dim_categoria", cats.select(key("codigo").alias("categoria_key"), F.col("codigo").alias("categoria_codigo"), "nombre"),
         ["silver.categorias"])
    tipos = _opt(io, "silver.tipos_falla", "codigo string, snapshot_ts timestamp, nombre string, categoria_codigo string")
    save("gold.dim_tipo_falla", tipos.select(key("codigo").alias("tipo_falla_key"), F.col("codigo").alias("tipo_falla_codigo"),
                                             "nombre", "categoria_codigo"), ["silver.tipos_falla"])
    sedes = _opt(io, "silver.sedes", "codigo string, snapshot_ts timestamp, nombre string, ciudad string, activo boolean")
    save("gold.dim_sede", sedes.select(key("codigo").alias("sede_key"), F.col("codigo").alias("sede_codigo"), "nombre", "ciudad"),
         ["silver.sedes"])
    equipos = _opt(io, "silver.equipos", "codigo string, snapshot_ts timestamp, tipo string, marca string, modelo string, "
                   "sede_codigo string, activo boolean")
    save("gold.dim_equipo", equipos.select(key("codigo").alias("equipo_key"), F.col("codigo").alias("equipo_codigo"),
                                           "tipo", "marca", "modelo", "sede_codigo"), ["silver.equipos"])
    prio = _opt(io, "silver.prioridades", "codigo string, snapshot_ts timestamp, nombre string, peso int")
    save("gold.dim_prioridad", prio.select(F.col("codigo").alias("prioridad_codigo"), "nombre", "peso"), ["silver.prioridades"])
    est = _opt(io, "silver.estados", "codigo string, snapshot_ts timestamp, nombre string, abierto boolean")
    save("gold.dim_estado", est.select(F.col("codigo").alias("estado_codigo"), "nombre", "abierto"), ["silver.estados"])
    users = _opt(io, "silver.users", "usuario_hash string, cargo string, area string, sede_codigo string, rol string, "
                 "activo boolean, snapshot_ts timestamp")
    save("gold.dim_usuario", users.select(key("usuario_hash").alias("usuario_key"), "usuario_hash", "cargo", "area", "sede_codigo"),
         ["silver.users"])
    save("gold.dim_tecnico", tec.select(key("tecnico_id", "valido_desde").alias("tecnico_key"), "tecnico_id", "nombre", "nivel",
                                        "especialidades", "categorias", "valido_desde", "valido_hasta", "es_actual"),
         ["silver.technicians"])

    min_date = tickets.agg(F.min(F.to_date("creado_en"))).first()[0] or today
    start = max(min_date, today - timedelta(days=config.snapshot_max_days))
    save("gold.dim_fecha", dim_fecha(spark, min(min_date, start), today + timedelta(days=1)), ["silver.tickets"])

    # ------------------------------------------------------------ hechos: tickets
    grupo = F.coalesce(F.col("equipo_codigo"), F.col("solicitante_hash"))
    w = Window.partitionBy(grupo, F.col("categoria_codigo")).orderBy("creado_en")
    prev_close = F.lag("finalizado_en").over(w)
    t = (tickets
         .withColumn("_prev_cierre", prev_close)
         .withColumn("es_reincidente", F.coalesce(
             (F.col("categoria_codigo").isNotNull() & F.col("_prev_cierre").isNotNull()
              & (F.col("creado_en") >= F.col("_prev_cierre"))
              & (F.col("creado_en") <= F.col("_prev_cierre") + F.expr(f"INTERVAL {config.reincidence_days} DAYS"))),
             F.lit(False)))
         .withColumn("ia_acerto", F.when(F.col("ia_tipo_falla_propuesto").isNotNull() & F.col("tipo_falla_codigo").isNotNull()
                                         & (F.col("estado_codigo") == "FINALIZADA"),
                                         F.col("ia_tipo_falla_propuesto") == F.col("tipo_falla_codigo")))
         .withColumn("asignacion_efectiva", F.when((F.col("estado_codigo") == "FINALIZADA") & (F.col("primer_modo") == "AUTO"),
                                                   (F.col("asignaciones") == 1) & (F.col("primer_tecnico") == F.col("tecnico_id")))))
    fact = t.select(
        "ticket_id", "numero",
        F.to_date("creado_en").alias("fecha_creacion"), date_key(F.col("creado_en")).alias("fecha_creacion_key"),
        F.to_date("finalizado_en").alias("fecha_cierre"), date_key(F.col("finalizado_en")).alias("fecha_cierre_key"),
        "creado_en", "asignado_en", "inicio_atencion_en", "finalizado_en",
        key("solicitante_hash").alias("usuario_key"), "tecnico_id",
        "categoria_codigo", key("categoria_codigo").alias("categoria_key"),
        "tipo_falla_codigo", key("tipo_falla_codigo").alias("tipo_falla_key"),
        "prioridad_codigo", "estado_codigo", "sede_codigo", key("sede_codigo").alias("sede_key"),
        "equipo_codigo", key("equipo_codigo").alias("equipo_key"), "canal", "impacto",
        minutes("creado_en", "asignado_en").alias("min_hasta_asignacion"),
        minutes("asignado_en", "inicio_atencion_en").alias("min_hasta_atencion"),
        F.when(F.col("estado_codigo") == "FINALIZADA", minutes("creado_en", "finalizado_en")).alias("min_resolucion"),
        "reaperturas", "es_reincidente", "asignado_por_ia", "requirio_revision", "ia_confianza",
        "ia_tipo_falla_propuesto", "ia_acerto", "asignacion_efectiva", "calificacion", "es_demo")
    # Clave del técnico vigente (SCD2) para reportes
    tec_actual = tec.where("es_actual").select("tecnico_id", key("tecnico_id", "valido_desde").alias("tecnico_key"))
    fact = fact.join(tec_actual, "tecnico_id", "left")
    save("gold.fact_tickets", fact, ["silver.tickets", "silver.technicians"])

    # ------------------------------------------------------------ hechos: atenciones
    cat_by_ticket = tickets.select("ticket_id", "categoria_codigo")
    fa = (act.join(cat_by_ticket, "ticket_id", "left").join(tec_actual, "tecnico_id", "left")
          .select("actividad_id", "ticket_id", "tecnico_id", "tecnico_key", F.to_date("registrado_en").alias("fecha"),
                  date_key(F.col("registrado_en")).alias("fecha_key"), "tipo", "minutos", "categoria_codigo", "es_demo"))
    save("gold.fact_atenciones", fa, ["silver.activities"])

    # ------------------------------------------------------------ hechos: asignaciones
    tp = tickets.select("ticket_id", "categoria_codigo", "prioridad_codigo")
    fs = (asg.join(tp, "ticket_id", "left")
          .select("asignacion_id", "ticket_id", F.to_date("asignado_en").alias("fecha"), date_key(F.col("asignado_en")).alias("fecha_key"),
                  "asignado_en", "modo", "tecnico_recomendado_id", "tecnico_asignado_id", "puntaje_recomendado", "puntaje_asignado",
                  (F.col("tecnico_recomendado_id").isNotNull() & (F.col("tecnico_recomendado_id") == F.col("tecnico_asignado_id")))
                  .alias("coincide_con_recomendacion"), "categoria_codigo", "prioridad_codigo", "pesos_version", "es_demo"))
    save("gold.fact_asignaciones", fs, ["silver.assignments"])

    # ------------------------------------------------------------ hechos: diagnósticos de IA
    cierre = tickets.select("ticket_id", F.col("tipo_falla_codigo").alias("tipo_falla_final"), "estado_codigo")
    fd = (diag.join(cierre, "ticket_id", "left")
          .withColumn("resultado", F.when(~F.col("concluyente"), "NO_CONCLUYENTE")
                      .when(F.col("estado_codigo") != "FINALIZADA", "PENDIENTE")
                      .when(F.col("tipo_falla_propuesto") == F.col("tipo_falla_final"), "ACERTADO")
                      .otherwise("CORREGIDO"))
          .select("diagnostico_id", "ticket_id", F.to_date("diagnosticado_en").alias("fecha"),
                  date_key(F.col("diagnosticado_en")).alias("fecha_key"), "origen", "proveedor", "modelo", "trigger",
                  "categoria_propuesta", "tipo_falla_propuesto", "tipo_falla_final", "confianza", "concluyente", "resultado",
                  "latencia_ms", "es_demo"))
    save("gold.fact_diagnosticos_ia", fd, ["silver.ai_diagnoses", "silver.tickets"])

    # ------------------------------------------------------------ hechos: foto diaria de pendientes
    dates = spark.createDataFrame([(start + timedelta(days=i),) for i in range((today - start).days + 1)], "fecha date")
    eod = F.to_timestamp(F.date_add(F.col("fecha"), 1))
    snap = (dates.crossJoin(tickets.select("ticket_id", "creado_en", "inicio_atencion_en", "finalizado_en", "cancelado_en",
                                           "prioridad_codigo", "sede_codigo", "categoria_codigo", "tecnico_id", "es_demo"))
            .where((F.col("creado_en") < eod)
                   & (F.col("finalizado_en").isNull() | (F.col("finalizado_en") >= eod))
                   & (F.col("cancelado_en").isNull() | (F.col("cancelado_en") >= eod)))
            .select("fecha", date_key(F.col("fecha")).alias("fecha_key"), "ticket_id",
                    F.when(F.col("inicio_atencion_en").isNotNull() & (F.col("inicio_atencion_en") < eod), "EN_CURSO")
                    .otherwise("PENDIENTE").alias("estado_codigo"),
                    "prioridad_codigo", "sede_codigo", "categoria_codigo", "tecnico_id",
                    F.round((eod.cast("double") - F.col("creado_en").cast("double")) / 3600.0, 1).alias("antiguedad_horas"),
                    "es_demo"))
    save("gold.fact_snapshot_diario", snap, ["silver.tickets"])

    # ------------------------------------------------------------ agregados
    fact_c = io.read("gold.fact_tickets")
    save("gold.agg_tickets_diarios", fact_c.groupBy("fecha_creacion", "sede_codigo", "categoria_codigo", "prioridad_codigo", "es_demo")
         .agg(F.count("*").alias("creados"), F.count_if(F.col("estado_codigo") == "FINALIZADA").alias("finalizados"),
              F.avg("min_resolucion").alias("min_resolucion_prom")), ["gold.fact_tickets"])

    desde90 = F.lit(today - timedelta(days=90))
    nombres = tec.where("es_actual").select("tecnico_id", "nombre")
    rend = (fact_c.where((F.col("estado_codigo") == "FINALIZADA") & (F.col("fecha_cierre") >= desde90) & F.col("tecnico_id").isNotNull())
            .groupBy("tecnico_id", "categoria_codigo")
            .agg(F.count("*").alias("resueltos_90d"), F.count_if(F.col("reaperturas") == 0).alias("sin_reapertura_90d"),
                 F.avg("min_resolucion").alias("min_resolucion_prom"))
            .withColumn("desempeno", F.round(F.col("sin_reapertura_90d") / F.col("resueltos_90d"), 4))
            .join(nombres, "tecnico_id", "left"))
    save("gold.agg_rendimiento_tecnico", rend, ["gold.fact_tickets"])

    save("gold.agg_reincidencia", fact_c.where("es_reincidente").groupBy("categoria_codigo", "equipo_codigo", "sede_codigo")
         .agg(F.count("*").alias("reincidentes")), ["gold.fact_tickets"])

    fd_c = io.read("gold.fact_diagnosticos_ia")
    save("gold.agg_efectividad_ia", fd_c.groupBy("fecha", "origen", "proveedor", "es_demo")
         .agg(F.count("*").alias("total"), F.count_if(F.col("resultado") == "ACERTADO").alias("acertados"),
              F.count_if(F.col("resultado") == "CORREGIDO").alias("corregidos"),
              F.avg("confianza").alias("confianza_prom")), ["gold.fact_diagnosticos_ia"])

    tickets.unpersist()
    ctx.message = ", ".join(f"{k}={v}" for k, v in out.items())
    return out
