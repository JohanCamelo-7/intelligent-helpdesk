"""Lo que el agente sabe del Data Lake: tablas, columnas, significado y reglas de negocio.

Las columnas se leen de Trino (information_schema) cuando está disponible; si no, se usa la lista de este archivo,
que refleja data-lake/lakehouse/gold.py. Las descripciones y reglas siguen docs/datos/reglas-de-contabilizacion.md.
"""
from __future__ import annotations

import logging
import threading
import time

log = logging.getLogger("ai-service.lake")

# tabla -> (descripción, {columna: descripción o tipo})
TABLAS: dict[str, tuple[str, dict[str, str]]] = {
    "gold.fact_tickets": ("Un registro por ticket (hecho principal). Fechas en hora de Bogotá.", {
        "ticket_id": "varchar", "numero": "varchar · SOP-000123",
        "fecha_creacion": "date · día de creación", "fecha_cierre": "date · día de finalización (NULL si no se finalizó)",
        "creado_en": "timestamp", "asignado_en": "timestamp", "inicio_atencion_en": "timestamp", "finalizado_en": "timestamp",
        "usuario_key": "varchar · seudónimo del solicitante", "tecnico_id": "varchar · técnico actual (join dim_tecnico)",
        "tecnico_key": "varchar",
        "categoria_codigo": "varchar · HARDWARE, SOFTWARE, REDES, IMPRESION, PERIFERICOS, ACCESOS",
        "tipo_falla_codigo": "varchar · ver dim_tipo_falla", "prioridad_codigo": "varchar · BAJA, MEDIA, ALTA, CRITICA",
        "estado_codigo": "varchar · PENDIENTE, EN_CURSO, FINALIZADA, CANCELADA",
        "sede_codigo": "varchar · SEDE_BOGOTA, SEDE_BELLO, SEDE_GIRARDOT", "equipo_codigo": "varchar · PC-BOG-101, IMP-GIR-11…",
        "canal": "varchar · WEB, AGENTE, DEMO", "impacto": "varchar · INDIVIDUAL, AREA, SEDE",
        "min_hasta_asignacion": "integer · minutos de creación a primera asignación",
        "min_hasta_atencion": "integer · minutos de asignación a inicio de atención",
        "min_resolucion": "integer · minutos de creación a finalización (solo FINALIZADA)",
        "reaperturas": "integer", "es_reincidente": "boolean · misma falla del mismo equipo/usuario ≤ 30 días tras un cierre",
        "asignado_por_ia": "boolean", "requirio_revision": "boolean", "ia_confianza": "double 0..1",
        "ia_tipo_falla_propuesto": "varchar",
        "ia_acerto": "boolean · la IA propuso el tipo de falla confirmado al cerrar (NULL si no aplica)",
        "asignacion_efectiva": "boolean", "calificacion": "integer 1..5 · encuesta (NULL sin encuesta)",
        "es_demo": "boolean · dato generado de demostración",
    }),
    "gold.fact_asignaciones": ("Una fila por asignación de técnico.", {
        "asignacion_id": "varchar", "ticket_id": "varchar", "fecha": "date", "asignado_en": "timestamp",
        "modo": "varchar · AUTO o MANUAL", "tecnico_recomendado_id": "varchar", "tecnico_asignado_id": "varchar",
        "puntaje_recomendado": "double", "puntaje_asignado": "double", "coincide_con_recomendacion": "boolean",
        "categoria_codigo": "varchar", "prioridad_codigo": "varchar", "es_demo": "boolean",
    }),
    "gold.fact_atenciones": ("Una fila por actividad registrada por un técnico.", {
        "actividad_id": "varchar", "ticket_id": "varchar", "tecnico_id": "varchar", "fecha": "date",
        "tipo": "varchar · DIAGNOSTICO, REPARACION, OBSERVACION, SEGUIMIENTO, SOLUCION", "minutos": "integer",
        "categoria_codigo": "varchar", "es_demo": "boolean",
    }),
    "gold.fact_diagnosticos_ia": ("Una fila por diagnóstico de IA (texto o imagen).", {
        "diagnostico_id": "varchar", "ticket_id": "varchar", "fecha": "date", "origen": "varchar · TEXTO o IMAGEN",
        "proveedor": "varchar", "modelo": "varchar", "categoria_propuesta": "varchar", "tipo_falla_propuesto": "varchar",
        "tipo_falla_final": "varchar", "confianza": "double", "concluyente": "boolean",
        "resultado": "varchar · ACERTADO, CORREGIDO, PENDIENTE, NO_CONCLUYENTE", "latencia_ms": "integer", "es_demo": "boolean",
    }),
    "gold.fact_snapshot_diario": ("Foto diaria de tickets abiertos al final de cada día.", {
        "fecha": "date", "ticket_id": "varchar", "estado_codigo": "varchar · PENDIENTE o EN_CURSO",
        "prioridad_codigo": "varchar", "sede_codigo": "varchar", "categoria_codigo": "varchar", "tecnico_id": "varchar",
        "antiguedad_horas": "double", "es_demo": "boolean",
    }),
    "gold.dim_sede": ("Sedes reales de UNIMINUTO.", {"sede_key": "varchar", "sede_codigo": "varchar", "nombre": "varchar",
                                                    "ciudad": "varchar · Bogotá, Bello, Girardot"}),
    "gold.dim_tecnico": ("Técnicos con historia (SCD2): usar es_actual = true para la versión vigente.", {
        "tecnico_key": "varchar", "tecnico_id": "varchar", "nombre": "varchar",
        "nivel": "varchar · JUNIOR, SEMI_SENIOR, SENIOR", "especialidades": "varchar", "categorias": "varchar",
        "valido_desde": "timestamp", "valido_hasta": "timestamp", "es_actual": "boolean"}),
    "gold.dim_categoria": ("Categorías de falla.", {"categoria_key": "varchar", "categoria_codigo": "varchar", "nombre": "varchar"}),
    "gold.dim_tipo_falla": ("Tipos de falla.", {"tipo_falla_key": "varchar", "tipo_falla_codigo": "varchar", "nombre": "varchar",
                                                "categoria_codigo": "varchar"}),
    "gold.dim_equipo": ("Equipos inventariados.", {"equipo_key": "varchar", "equipo_codigo": "varchar",
                                                   "tipo": "varchar · PC, IMPRESORA, PROYECTOR…", "marca": "varchar",
                                                   "modelo": "varchar", "sede_codigo": "varchar"}),
    "gold.dim_usuario": ("Solicitantes seudonimizados (sin nombre ni correo).", {
        "usuario_key": "varchar", "usuario_hash": "varchar", "cargo": "varchar", "area": "varchar", "sede_codigo": "varchar"}),
    "gold.dim_prioridad": ("Prioridades.", {"prioridad_codigo": "varchar", "nombre": "varchar", "peso": "integer"}),
    "gold.dim_estado": ("Estados.", {"estado_codigo": "varchar", "nombre": "varchar", "abierto": "boolean"}),
    "gold.dim_fecha": ("Calendario con festivos de Colombia.", {
        "fecha_key": "integer · AAAAMMDD", "fecha": "date", "anio": "integer", "mes": "integer", "nombre_mes": "varchar",
        "semana_iso": "integer", "dia": "integer", "dia_semana": "integer · 1 = lunes … 7 = domingo", "nombre_dia": "varchar",
        "es_fin_semana": "boolean", "es_festivo": "boolean"}),
    "gold.agg_rendimiento_tecnico": ("Desempeño por técnico y categoría en los últimos 90 días.", {
        "tecnico_id": "varchar", "nombre": "varchar", "categoria_codigo": "varchar", "resueltos_90d": "bigint",
        "sin_reapertura_90d": "bigint", "min_resolucion_prom": "double", "desempeno": "double 0..1"}),
    "gold.agg_tickets_diarios": ("Tickets creados y finalizados por día, sede, categoría y prioridad.", {
        "fecha_creacion": "date", "sede_codigo": "varchar", "categoria_codigo": "varchar", "prioridad_codigo": "varchar",
        "es_demo": "boolean", "creados": "bigint", "finalizados": "bigint", "min_resolucion_prom": "double"}),
    "ops.pipeline_runs": ("Ejecuciones del pipeline del lago, por paso.", {
        "run_id": "varchar", "step": "varchar", "status": "varchar", "started_at": "timestamp", "finished_at": "timestamp",
        "rows_in": "bigint", "rows_out": "bigint", "rows_rejected": "bigint", "message": "varchar"}),
    "ops.dq_results": ("Resultados de las reglas de calidad.", {
        "run_id": "varchar", "table_name": "varchar", "rule_name": "varchar", "severity": "varchar", "passed": "boolean",
        "failed_rows": "bigint", "checked_at": "timestamp"}),
}

REGLAS = """\
- Todas las fechas de negocio están en hora de Bogotá. Para filtrar por fecha usa fecha_creacion (o fecha_cierre para cierres).
- Tiempo de resolución = min_resolucion (minutos); solo existe para tickets FINALIZADA. Para horas divide entre 60.0; para días entre 1440.0.
- Los cancelados cuentan en los totales pero no en los promedios de tiempo.
- Acierto de la IA = avg(CASE WHEN ia_acerto THEN 1.0 ELSE 0.0 END) sobre filas con ia_acerto IS NOT NULL.
- Satisfacción = avg(calificacion) sobre tickets con encuesta (calificacion IS NOT NULL), escala 1 a 5.
- Reincidencia = count_if(es_reincidente).
- Abiertos = estado_codigo IN ('PENDIENTE','EN_CURSO').
- Sedes: Bogotá = 'SEDE_BOGOTA', Bello (Antioquia, área de Medellín) = 'SEDE_BELLO', Girardot = 'SEDE_GIRARDOT'. Para mostrar el nombre une con gold.dim_sede.
- Nombres de técnicos: gold.dim_tecnico con es_actual = true (une por tecnico_id).
- Todos los datos actuales son de demostración (es_demo = true): inclúyelos salvo que el usuario pida excluirlos.
"""

EJEMPLOS = [
    ("¿Cuántos tickets hubo por sede en 2025?",
     "SELECT s.ciudad AS sede, count(*) AS tickets FROM gold.fact_tickets t JOIN gold.dim_sede s ON s.sede_codigo = t.sede_codigo "
     "WHERE year(t.fecha_creacion) = 2025 GROUP BY s.ciudad ORDER BY tickets DESC"),
    ("Tiempo promedio de resolución en horas por categoría",
     "SELECT categoria_codigo, round(avg(min_resolucion) / 60.0, 1) AS horas_promedio FROM gold.fact_tickets "
     "WHERE estado_codigo = 'FINALIZADA' GROUP BY categoria_codigo ORDER BY horas_promedio DESC"),
    ("Los 5 técnicos con más tickets resueltos este año",
     "SELECT d.nombre AS tecnico, count(*) AS resueltos FROM gold.fact_tickets t JOIN gold.dim_tecnico d "
     "ON d.tecnico_id = t.tecnico_id AND d.es_actual WHERE t.estado_codigo = 'FINALIZADA' "
     "AND year(t.fecha_cierre) = year(current_date) GROUP BY d.nombre ORDER BY resueltos DESC LIMIT 5"),
]

PREGUNTAS_SUGERIDAS = [
    "¿Cuántos tickets hubo por sede?",
    "Tendencia mensual de tickets en 2025",
    "Tiempo promedio de resolución por sede",
    "¿Qué categoría tiene más tickets en Girardot?",
    "Los 5 técnicos con más tickets resueltos",
    "Satisfacción promedio por sede",
    "Acierto de la IA por año",
    "Reincidencias por categoría en Bello",
    "¿Cuántos tickets hay abiertos por prioridad?",
]


class Catalogo:
    """Columnas reales leídas de Trino, con respaldo estático. Se refresca cada 10 minutos."""

    def __init__(self) -> None:
        self._columnas: dict[str, dict[str, str]] = {t: dict(c) for t, (_, c) in TABLAS.items()}
        self._cargado = 0.0
        self._lock = threading.Lock()

    def refrescar(self, trino) -> None:
        if time.time() - self._cargado < 600:
            return
        with self._lock:
            try:
                res = trino.consultar("SELECT table_schema, table_name, column_name, data_type FROM lake.information_schema.columns "
                                      "WHERE table_schema IN ('gold', 'ops')", max_filas=5000)
                reales: dict[str, dict[str, str]] = {}
                for esquema, tabla, col, tipo in res.filas:
                    reales.setdefault(f"{esquema}.{tabla}", {})[col] = tipo
                if reales:
                    for t, cols in reales.items():
                        base = TABLAS.get(t, ("", {}))[1]
                        self._columnas[t] = {c: (base[c] if c in base else tipo) for c, tipo in cols.items()}
                    for t in list(self._columnas):          # tablas que todavía no existen en el lago
                        if t not in reales:
                            self._columnas.pop(t)
            except Exception as exc:  # noqa: BLE001 - sin Trino se usa la lista estática
                log.info("No se pudo leer el esquema de Trino (%s); se usa el catálogo estático", exc)
            self._cargado = time.time()

    @property
    def tablas(self) -> set[str]:
        return set(self._columnas)

    def columnas(self, tabla: str) -> dict[str, str]:
        return self._columnas.get(tabla, {})

    def para_prompt(self) -> str:
        lineas = []
        for t in sorted(self._columnas):
            desc = TABLAS.get(t, ("",))[0]
            cols = ", ".join(f"{c} ({d})" for c, d in self._columnas[t].items())
            lineas.append(f"- {t}: {desc}\n  Columnas: {cols}")
        return "\n".join(lineas)


catalogo = Catalogo()
