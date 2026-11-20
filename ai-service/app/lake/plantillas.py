"""Modo sin modelo de lenguaje: interpreta la pregunta con reglas y arma la consulta desde plantillas.

Cubre las preguntas frecuentes (cuántos, tiempos, satisfacción, acierto de la IA, reincidencia, abiertos…)
agrupadas por sede, categoría, técnico, mes, año, día, hora, prioridad, estado o tipo de falla, con filtros de sede,
año, mes, periodo, categoría y prioridad. Las preguntas cortas de seguimiento («¿y en Bello?») reutilizan la anterior.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from ..taxonomy import normalize


@dataclass(frozen=True)
class Metrica:
    clave: str
    nombre: str
    expr: str
    alias: str
    where: str = ""
    fecha: str = "t.fecha_creacion"


METRICAS = {
    "tickets": Metrica("tickets", "cantidad de tickets", "count(*)", "tickets"),
    "finalizados": Metrica("finalizados", "tickets finalizados", "count(*)", "finalizados",
                           "t.estado_codigo = 'FINALIZADA'", "t.fecha_cierre"),
    "abiertos": Metrica("abiertos", "tickets abiertos (pendientes o en curso)", "count(*)", "abiertos",
                        "t.estado_codigo IN ('PENDIENTE', 'EN_CURSO')"),
    "cancelados": Metrica("cancelados", "tickets cancelados", "count(*)", "cancelados", "t.estado_codigo = 'CANCELADA'"),
    "reabiertos": Metrica("reabiertos", "tickets reabiertos", "count_if(t.reaperturas > 0)", "reabiertos"),
    "resolucion_h": Metrica("resolucion_h", "tiempo promedio de resolución (horas)", "round(avg(t.min_resolucion) / 60.0, 1)",
                            "horas_resolucion", "t.estado_codigo = 'FINALIZADA'"),
    "resolucion_d": Metrica("resolucion_d", "tiempo promedio de resolución (días)", "round(avg(t.min_resolucion) / 1440.0, 2)",
                            "dias_resolucion", "t.estado_codigo = 'FINALIZADA'"),
    "asignacion": Metrica("asignacion", "tiempo promedio hasta la asignación (minutos)",
                          "round(avg(t.min_hasta_asignacion), 1)", "minutos_asignacion", "t.min_hasta_asignacion IS NOT NULL"),
    "atencion": Metrica("atencion", "tiempo promedio hasta iniciar la atención (horas)",
                        "round(avg(t.min_hasta_atencion) / 60.0, 1)", "horas_atencion", "t.min_hasta_atencion IS NOT NULL"),
    "satisfaccion": Metrica("satisfaccion", "satisfacción promedio (1 a 5)", "round(avg(t.calificacion), 2)",
                            "satisfaccion", "t.calificacion IS NOT NULL"),
    "acierto_ia": Metrica("acierto_ia", "porcentaje de acierto de la IA",
                          "round(100.0 * avg(CASE WHEN t.ia_acerto THEN 1.0 ELSE 0.0 END), 1)", "porcentaje_acierto_ia",
                          "t.ia_acerto IS NOT NULL"),
    "reincidencia": Metrica("reincidencia", "tickets reincidentes", "count_if(t.es_reincidente)", "reincidentes"),
}

DIA_SEMANA = ("CASE day_of_week(t.fecha_creacion) WHEN 1 THEN '1 lunes' WHEN 2 THEN '2 martes' WHEN 3 THEN '3 miércoles' "
              "WHEN 4 THEN '4 jueves' WHEN 5 THEN '5 viernes' WHEN 6 THEN '6 sábado' ELSE '7 domingo' END")

# clave -> (nombre, expresión, alias, join, temporal)
DIMENSIONES = {
    "sede": ("sede", "s.ciudad", "sede", "JOIN gold.dim_sede s ON s.sede_codigo = t.sede_codigo", False),
    "categoria": ("categoría", "t.categoria_codigo", "categoria", "", False),
    "tecnico": ("técnico", "d.nombre", "tecnico",
                "JOIN gold.dim_tecnico d ON d.tecnico_id = t.tecnico_id AND d.es_actual", False),
    "mes": ("mes", "substr(CAST(date_trunc('month', {f}) AS varchar), 1, 7)", "mes", "", True),
    "anio": ("año", "year({f})", "anio", "", True),
    "dia_semana": ("día de la semana", DIA_SEMANA, "dia_semana", "", True),
    "hora": ("hora del día", "hour(t.creado_en)", "hora", "", True),
    "prioridad": ("prioridad", "t.prioridad_codigo", "prioridad", "", False),
    "estado": ("estado", "t.estado_codigo", "estado", "", False),
    "tipo_falla": ("tipo de falla", "t.tipo_falla_codigo", "tipo_falla", "", False),
    "equipo": ("equipo", "t.equipo_codigo", "equipo", "", False),
    "canal": ("canal", "t.canal", "canal", "", False),
}

SEDES = {"bogota": ("SEDE_BOGOTA", "Bogotá"), "bello": ("SEDE_BELLO", "Bello"), "medellin": ("SEDE_BELLO", "Bello"),
         "antioquia": ("SEDE_BELLO", "Bello"), "girardot": ("SEDE_GIRARDOT", "Girardot")}
CATEGORIAS = [
    (r"\bimpres\w*", "IMPRESION"), (r"\b(redes?|internet|wifi|conexion|conectividad)\b", "REDES"),
    (r"\b(accesos?|contrasenas?|claves?|cuentas?|bloquead\w*)\b", "ACCESOS"),
    (r"\b(hardware|computador\w*|portatil\w*|pc)\b", "HARDWARE"),
    (r"\b(software|programas?|windows|office|sistema operativo)\b", "SOFTWARE"),
    (r"\b(periferic\w*|mouse|teclados?|videobeam|proyector\w*|monitor\w*)\b", "PERIFERICOS"),
]
MESES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre",
         "diciembre"]


@dataclass
class Intencion:
    metrica: str | None = None
    dimension: str | None = None
    filtros: dict[str, tuple[str, str]] = field(default_factory=dict)   # tipo -> (sql con {f}, descripción)
    top: int | None = None
    ascendente: bool = False
    reconocida: bool = False


def _metrica(t: str) -> str | None:
    if re.search(r"satisf|calificac|encuesta|nota promedio", t):
        return "satisfaccion"
    if re.search(r"acierto|acert|precision|efectividad|exactitud", t):
        return "acierto_ia"
    if re.search(r"reincid|recurren|repetid", t):
        return "reincidencia"
    if re.search(r"tiempo|demor|tard|dura|rapid|lent|cuanto se", t):
        if "asign" in t:
            return "asignacion"
        if re.search(r"atencion|atender|inicio|empez", t):
            return "atencion"
        return "resolucion_d" if re.search(r"\bdias\b", t) and not re.search(r"por dia|dia de la semana", t) else "resolucion_h"
    if re.search(r"reabiert|reapert", t):
        return "reabiertos"
    if re.search(r"cancelad", t):
        return "cancelados"
    if re.search(r"abiert|pendiente|sin resolver|en curso|backlog|sin cerrar", t):
        return "abiertos"
    if re.search(r"resuelt|finalizad|cerrad|solucionad|atendid", t):
        return "finalizados"
    if re.search(r"ticket|caso|solicitud|reporte|incidente|cuant|volumen|demanda|falla", t):
        return "tickets"
    return None


def _dimension(t: str) -> str | None:
    reglas = [
        ("dia_semana", r"dias? de la semana"),
        ("hora", r"\bpor horas?\b|hora del dia|horas del dia|a que hora"),
        ("mes", r"\bpor mes|mensual|cada mes|tendencia|mes a mes|evolucion|por meses"),
        ("anio", r"\bpor anos?\b|anual|cada ano|ano a ano"),
        ("tecnico", r"\btecnicos\b|(por|cada|que|cual|mejor|peor)\s+tecnico"),
        ("sede", r"\bsedes\b|(por|cada|que|cual|segun|entre)\s+(la\s+)?sede\b|ciudades"),
        ("tipo_falla", r"tipos? de falla|fallas mas|que fallas|cuales fallas"),
        ("categoria", r"categorias|(por|cada|que|cual|segun)\s+(la\s+)?categoria"),
        ("prioridad", r"prioridades|(por|cada|segun)\s+prioridad"),
        ("estado", r"\bestados\b|(por|cada|segun)\s+estado"),
        ("equipo", r"\bequipos\b|(que|cual|por)\s+equipo"),
        ("canal", r"\bcanal(es)?\b"),
    ]
    for clave, patron in reglas:
        if re.search(patron, t):
            return clave
    return None


def _filtros(t: str) -> dict[str, tuple[str, str]]:
    f: dict[str, tuple[str, str]] = {}
    sedes = sorted({v for k, v in SEDES.items() if re.search(rf"\b{k}\b", t)})
    if sedes:
        lista = ", ".join(f"'{c}'" for c, _ in sedes)
        f["sede"] = (f"t.sede_codigo IN ({lista})", "sede " + " y ".join(n for _, n in sedes))
    anios = sorted(set(re.findall(r"\b(20[2-3]\d)\b", t)))
    if anios:
        f["periodo"] = (f"year({{f}}) IN ({', '.join(anios)})", "año " + " y ".join(anios))
    elif re.search(r"este ano|ano actual", t):
        f["periodo"] = ("year({f}) = year(current_date)", "este año")
    elif re.search(r"ano pasado|ano anterior", t):
        f["periodo"] = ("year({f}) = year(current_date) - 1", "el año pasado")
    elif m := re.search(r"ultimos?\s+(\d{1,3})\s+dias", t):
        f["periodo"] = (f"{{f}} >= current_date - INTERVAL '{int(m.group(1))}' DAY", f"últimos {m.group(1)} días")
    elif re.search(r"ultimo mes|ultimos 30|ultimas 4 semanas", t):
        f["periodo"] = ("{f} >= current_date - INTERVAL '30' DAY", "últimos 30 días")
    elif re.search(r"este mes|mes actual", t):
        f["periodo"] = ("{f} >= date_trunc('month', current_date)", "este mes")
    elif re.search(r"esta semana", t):
        f["periodo"] = ("{f} >= date_trunc('week', current_date)", "esta semana")
    elif re.search(r"\bhoy\b", t):
        f["periodo"] = ("{f} = current_date", "hoy")
    meses = [i + 1 for i, m in enumerate(MESES) if re.search(rf"\b{m}\b", t)]
    if meses:
        f["mes"] = (f"month({{f}}) IN ({', '.join(map(str, meses))})", "mes de " + " y ".join(MESES[m - 1] for m in meses))
    cats = sorted({c for patron, c in CATEGORIAS if re.search(patron, t)})
    if cats:
        f["categoria"] = (f"t.categoria_codigo IN ({', '.join(repr(c) for c in cats)})", "categoría " + ", ".join(cats))
    prios = [p for p, pat in (("CRITICA", r"critic"), ("ALTA", r"prioridad alta|alta prioridad|\baltas?\b"),
                              ("MEDIA", r"prioridad media|\bmedias?\b"), ("BAJA", r"prioridad baja|baja prioridad|\bbajas?\b"))
             if re.search(pat, t)]
    if prios:
        f["prioridad"] = (f"t.prioridad_codigo IN ({', '.join(repr(p) for p in prios)})", "prioridad " + ", ".join(prios))
    if re.search(r"sin demo|excluy\w* (la |los )?demo|datos reales|no demo", t):
        f["demo"] = ("NOT t.es_demo", "sin datos de demostración")
    return f


def interpretar(pregunta: str) -> Intencion:
    t = normalize(pregunta)
    t = t.replace("ñ", "n")
    it = Intencion(metrica=_metrica(t), dimension=_dimension(t), filtros=_filtros(t))
    if m := re.search(r"\b(?:top|los|las|primer\w*|mejores|peores)\s+(\d{1,2})\b", t):
        it.top = int(m.group(1))
    elif re.search(r"\b(cual|que|quien)\b.*\b(mas|mayor|mejor|peor|menos|menor)\b", t) and it.dimension:
        it.top = 1 if not re.search(r"\b(sedes|categorias|tecnicos)\b", t) else None
    it.ascendente = bool(re.search(r"\b(menos|menor|peores|mas rapid\w*|mas bajo)\b", t)) and it.metrica not in ("resolucion_h", "resolucion_d", "asignacion", "atencion")
    if it.metrica in ("resolucion_h", "resolucion_d", "asignacion", "atencion"):
        it.ascendente = bool(re.search(r"mas rapid|menos tiempo|menor tiempo|mejor", t))
    it.reconocida = bool(it.metrica or it.dimension or it.filtros)
    return it


def combinar(nueva: Intencion, previa: Intencion | None) -> Intencion:
    """Para preguntas de seguimiento: lo que falte en la nueva se toma de la anterior."""
    if previa is None or (nueva.metrica and nueva.dimension):
        return nueva
    filtros = dict(previa.filtros)
    filtros.update(nueva.filtros)
    return Intencion(metrica=nueva.metrica or previa.metrica, dimension=nueva.dimension or previa.dimension,
                     filtros=filtros, top=nueva.top or previa.top, ascendente=nueva.ascendente or previa.ascendente,
                     reconocida=True)


@dataclass
class Plan:
    sql: str
    explicacion: str
    grafico: dict
    metrica: Metrica
    dimension: str | None


def construir(it: Intencion) -> Plan:
    m = METRICAS[it.metrica or "tickets"]
    where = [m.where] if m.where else []
    where += [sql.format(f=m.fecha) for sql, _ in it.filtros.values()]
    partes = []
    if it.dimension:
        nombre_dim, expr, alias, join, temporal = DIMENSIONES[it.dimension]
        expr = expr.format(f=m.fecha)
        if it.dimension == "tecnico":
            where.append("t.tecnico_id IS NOT NULL")
        sql = f"SELECT {expr} AS {alias}, {m.expr} AS {m.alias}\nFROM gold.fact_tickets t {join}".rstrip()
        if where:
            sql += "\nWHERE " + " AND ".join(where)
        sql += f"\nGROUP BY {expr}"
        if temporal and not it.top:
            sql += f"\nORDER BY {alias}"
        else:
            sql += f"\nORDER BY {m.alias} {'ASC' if it.ascendente else 'DESC'} NULLS LAST"
        if it.top:
            sql += f"\nLIMIT {it.top}"
        grafico = {"tipo": "linea" if temporal and it.dimension in ("mes", "anio") else "barras", "x": alias, "y": m.alias}
        partes.append(f"agrupado por {nombre_dim}")
    else:
        sql = f"SELECT {m.expr} AS {m.alias}\nFROM gold.fact_tickets t"
        if where:
            sql += "\nWHERE " + " AND ".join(where)
        grafico = {"tipo": "ninguno"}
    filtros = [d for _, d in it.filtros.values()]
    explicacion = f"Calculé {m.nombre}" + (f", {partes[0]}" if partes else "")
    if filtros:
        explicacion += f" · filtros: {', '.join(filtros)}"
    if it.top == 1:
        explicacion += f" · solo el {'menor' if it.ascendente else 'mayor'}"
    elif it.top:
        explicacion += f" · los {it.top} {'menores' if it.ascendente else 'mayores'}"
    if m.fecha == "t.fecha_cierre" and (it.filtros.get("periodo") or it.filtros.get("mes") or it.dimension in ("mes", "anio")):
        explicacion += " (por fecha de cierre)"
    return Plan(sql=sql, explicacion=explicacion + ".", grafico=grafico, metrica=m, dimension=it.dimension)
