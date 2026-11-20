"""Agente de IA del Data Lake: responde preguntas en español consultando la capa Gold con Trino.

Ciclo (harness):
  1. Planear: el modelo de lenguaje recibe el catálogo, las reglas de negocio y ejemplos, y devuelve la consulta en JSON.
     Sin modelo configurado (LLM_PROVIDER=heuristic) se usan las plantillas de plantillas.py.
  2. Validar: la guardia SQL acepta solo una consulta de lectura sobre gold y ops, con límite de filas.
  3. Ejecutar: Trino con el usuario de solo lectura `analytics` y tiempo máximo.
  4. Corregir: si la guardia o Trino rechazan la consulta, el error vuelve al modelo (hasta LAKE_MAX_INTENTOS).
  5. Responder: el modelo redacta la respuesta usando SOLO las filas obtenidas; si falla, se redacta con reglas.
Cada paso queda en `intentos` y en el registro del servicio, para que el usuario vea qué consulta se ejecutó.
"""
from __future__ import annotations

import json
import logging
import re
import time
from datetime import date

import httpx

from ..config import settings
from .catalogo import EJEMPLOS, PREGUNTAS_SUGERIDAS, REGLAS, catalogo
from .guardia import SQLRechazado, validar
from .plantillas import combinar, construir, interpretar
from .trino import TrinoClient, TrinoError

log = logging.getLogger("ai-service.lake")

SISTEMA_SQL = """Eres el analista de datos de la mesa de ayuda de TI de UNIMINUTO (sedes Bogotá, Bello y Girardot).
Respondes preguntas consultando el Data Lake (capa Gold) con SQL de Trino.

Responde SOLO con un objeto JSON, sin texto adicional:
- Si la pregunta se puede responder con los datos:
  {{"sql": "SELECT ...", "explicacion": "qué calcula la consulta, en una frase", "grafico": {{"tipo": "barras|linea|ninguno", "x": "columna", "y": "columna"}}}}
- Si no se puede responder con estas tablas, o no es una pregunta sobre los datos:
  {{"aclaracion": "explica brevemente qué sí se puede preguntar"}}

Reglas de la consulta:
1. Una sola consulta SELECT (puede usar WITH). Nunca modifiques datos.
2. Usa solo estas tablas, siempre con su esquema (gold.tabla u ops.tabla), y solo sus columnas:
{catalogo}
3. Reglas de negocio:
{reglas}
4. Alias de columnas en español y snake_case. Redondea promedios a 1 o 2 decimales. Ordena el resultado de forma útil.
   Devuelve como máximo {limite} filas; para gráficos usa la primera columna como etiqueta y la última como valor.
5. Sintaxis de Trino: year(), month(), date_trunc('month', x), current_date, INTERVAL '30' DAY, count_if(), round().
6. Hoy es {hoy}.

Ejemplos:
{ejemplos}"""

SISTEMA_RESPUESTA = """Redactas la respuesta para la coordinación de TI de UNIMINUTO a partir del resultado de una consulta.
- Usa SOLO las cifras del resultado; no inventes ni estimes datos.
- De 1 a 3 frases en español, directas. Números en formato colombiano (punto de miles, coma decimal).
- Si el resultado está vacío, dilo. Si está truncado, dilo.
- Si los datos son de demostración, no lo repitas: la interfaz ya lo indica.
Responde SOLO con JSON: {"respuesta": "..."}"""


class AgenteDataLake:
    def __init__(self, trino: TrinoClient | None = None, llm: "ClienteLLM | None" = None) -> None:
        self.trino = trino or TrinoClient()
        self._llm = llm

    # ------------------------------------------------------------------ modelo
    @property
    def llm(self) -> "ClienteLLM | None":
        if self._llm is None and usa_llm():
            self._llm = ClienteLLM.desde_config()
        return self._llm

    @property
    def modo(self) -> str:
        return "ia" if self.llm else "plantillas"

    def estado(self) -> dict:
        return {"modo": self.modo, "modelo": self.llm.modelo if self.llm else None,
                "tablas": sorted(catalogo.tablas), "sugerencias": PREGUNTAS_SUGERIDAS,
                "max_filas": settings.lake_max_filas}

    # ------------------------------------------------------------------ entrada
    def preguntar(self, pregunta: str, historial: list[dict] | None = None, usuario: str = "") -> dict:
        inicio = time.perf_counter()
        historial = (historial or [])[-4:]
        catalogo.refrescar(self.trino)
        advertencias: list[str] = []
        res = None
        if self.llm:
            try:
                res = self._con_llm(pregunta, historial)
            except LLMNoDisponible as exc:
                advertencias.append(f"El modelo de lenguaje no respondió ({exc}); se usaron las plantillas.")
        if res is None:
            res = self._con_plantillas(pregunta, historial)
        res["advertencias"] = advertencias + res.get("advertencias", [])
        res["pregunta"] = pregunta
        res["duracion_ms"] = int((time.perf_counter() - inicio) * 1000)
        log.info("agente-lago usuario=%s modo=%s filas=%s intentos=%d ms=%d pregunta=%r sql=%r", usuario, res["modo"],
                 len(res.get("filas") or []), len(res.get("intentos") or []), res["duracion_ms"], pregunta[:200],
                 (res.get("sql") or "")[:500])
        return res

    # ------------------------------------------------------------------ modo IA
    def _con_llm(self, pregunta: str, historial: list[dict]) -> dict:
        sistema = SISTEMA_SQL.format(
            catalogo=catalogo.para_prompt(), reglas=REGLAS, limite=settings.lake_max_filas, hoy=date.today().isoformat(),
            ejemplos="\n".join(f"P: {p}\nJSON: {json.dumps({'sql': s}, ensure_ascii=False)}" for p, s in EJEMPLOS))
        mensajes: list[dict] = [{"role": "system", "content": sistema}]
        for turno in historial:
            mensajes.append({"role": "user", "content": str(turno.get("pregunta", ""))[:500]})
            if turno.get("sql"):
                mensajes.append({"role": "assistant", "content": json.dumps({"sql": turno["sql"]}, ensure_ascii=False)})
        mensajes.append({"role": "user", "content": pregunta})

        intentos: list[dict] = []
        limite = time.monotonic() + 70          # Nginx corta a los 90 s
        for _ in range(settings.lake_max_intentos):
            if time.monotonic() > limite:
                break
            plan = self.llm.json(mensajes)
            if plan.get("aclaracion") and not plan.get("sql"):
                return self._aclaracion(str(plan["aclaracion"]), "ia", intentos)
            sql_propuesto = str(plan.get("sql") or "")
            try:
                sql = validar(sql_propuesto, catalogo.tablas, settings.lake_max_filas)
                resultado = self.trino.consultar(sql)
            except (SQLRechazado, TrinoError) as exc:
                motivo = str(exc)
                intentos.append({"sql": sql_propuesto, "error": motivo})
                mensajes.append({"role": "assistant", "content": json.dumps(plan, ensure_ascii=False)})
                mensajes.append({"role": "user", "content": f"La consulta fue rechazada: {motivo}\n"
                                 "Corrígela respetando las tablas y columnas indicadas y responde de nuevo con el JSON."})
                continue
            intentos.append({"sql": sql, "error": None})
            respuesta = (self._redactar(pregunta, str(plan.get("explicacion") or ""), resultado)
                         if time.monotonic() < limite else resumen_basico(str(plan.get("explicacion") or "Resultado"), resultado))
            return self._salida(respuesta, str(plan.get("explicacion") or ""), sql, resultado,
                                _grafico(plan.get("grafico"), resultado.columnas), "ia", intentos)
        return {"modo": "ia", "modelo": self.llm.modelo, "respuesta":
                "No logré construir una consulta válida para esa pregunta. Prueba a formularla de otra manera o "
                "con una de las preguntas sugeridas.", "aclaracion": True, "intentos": intentos,
                "sugerencias": PREGUNTAS_SUGERIDAS, "sql": None, "columnas": [], "filas": []}

    def _redactar(self, pregunta: str, explicacion: str, r) -> str:
        datos = {"pregunta": pregunta, "explicacion": explicacion, "columnas": [c["nombre"] for c in r.columnas],
                 "filas": r.filas[:40], "total_filas": len(r.filas), "truncado": r.truncado}
        try:
            out = self.llm.json([{"role": "system", "content": SISTEMA_RESPUESTA},
                                 {"role": "user", "content": json.dumps(datos, ensure_ascii=False, default=str)}])
            texto = str(out.get("respuesta") or "").strip()
            if texto:
                return texto
        except LLMNoDisponible:
            pass
        return resumen_basico(explicacion, r)

    # ------------------------------------------------------------------ modo plantillas
    def _con_plantillas(self, pregunta: str, historial: list[dict]) -> dict:
        previa = None
        for turno in historial:
            it_prev = interpretar(str(turno.get("pregunta", "")))
            if it_prev.reconocida:
                previa = combinar(it_prev, previa)
        it = interpretar(pregunta)
        if not it.reconocida:
            return self._aclaracion("No entendí la pregunta. Puedo calcular cantidades de tickets, tiempos de "
                                    "asignación y resolución, satisfacción, acierto de la IA, reincidencias y abiertos, "
                                    "por sede, categoría, técnico, mes, año, día, hora, prioridad o estado.", "plantillas", [])
        if not (it.metrica and it.dimension):
            it = combinar(it, previa)
        plan = construir(it)
        try:
            sql = validar(plan.sql, catalogo.tablas, settings.lake_max_filas)
            resultado = self.trino.consultar(sql)
        except (SQLRechazado, TrinoError) as exc:
            return {"modo": "plantillas", "respuesta": f"No se pudo consultar el Data Lake: {exc}", "aclaracion": True,
                    "intentos": [{"sql": plan.sql, "error": str(exc)}], "sql": plan.sql, "columnas": [], "filas": [],
                    "sugerencias": PREGUNTAS_SUGERIDAS}
        titulo = plan.metrica.nombre[0].upper() + plan.metrica.nombre[1:]
        return self._salida(resumen_basico(titulo, resultado), plan.explicacion, sql, resultado,
                            _grafico(plan.grafico, resultado.columnas), "plantillas", [{"sql": sql, "error": None}])

    # ------------------------------------------------------------------ salida
    def _salida(self, respuesta, explicacion, sql, r, grafico, modo, intentos) -> dict:
        return {"modo": modo, "modelo": self.llm.modelo if (modo == "ia" and self.llm) else None,
                "respuesta": respuesta, "explicacion": explicacion, "sql": sql, "columnas": r.columnas,
                "filas": r.filas, "truncado": r.truncado, "consulta_ms": r.duracion_ms, "grafico": grafico,
                "intentos": intentos, "aclaracion": False}

    def _aclaracion(self, texto: str, modo: str, intentos: list) -> dict:
        return {"modo": modo, "modelo": self.llm.modelo if (modo == "ia" and self.llm) else None, "respuesta": texto,
                "aclaracion": True, "sugerencias": PREGUNTAS_SUGERIDAS, "intentos": intentos, "sql": None,
                "columnas": [], "filas": []}


# ---------------------------------------------------------------------- utilidades
def usa_llm() -> bool:
    modo = settings.lake_agent_mode
    if modo == "plantillas":
        return False
    return settings.llm_provider in ("openai", "ollama")


class LLMNoDisponible(Exception):
    pass


class ClienteLLM:
    """Llamada mínima a una API compatible con OpenAI (OpenAI, Ollama, LM Studio, vLLM…) que devuelve JSON."""

    def __init__(self, base_url: str, api_key: str, modelo: str, client: httpx.Client | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        self.modelo = modelo
        self.headers = {"Authorization": f"Bearer {api_key or 'sin-clave'}"}
        self.http = client or httpx.Client(timeout=min(settings.llm_timeout, 30))

    @classmethod
    def desde_config(cls) -> "ClienteLLM":
        base = settings.llm_base_url
        if settings.llm_provider == "ollama" and not ("ollama" in base or "11434" in base):
            base = "http://ollama:11434/v1"
        modelo = settings.lake_llm_model or settings.llm_model
        return cls(base, settings.llm_api_key if settings.llm_provider == "openai" else "ollama", modelo)

    def json(self, mensajes: list[dict]) -> dict:
        body = {"model": self.modelo, "temperature": 0, "response_format": {"type": "json_object"}, "messages": mensajes}
        try:
            resp = self.http.post(f"{self.base_url}/chat/completions", json=body, headers=self.headers)
            resp.raise_for_status()
            contenido = resp.json()["choices"][0]["message"]["content"]
        except (httpx.HTTPError, KeyError, IndexError, ValueError) as exc:
            raise LLMNoDisponible(str(exc)[:200]) from exc
        return extraer_json(contenido)


def extraer_json(texto: str) -> dict:
    texto = (texto or "").strip()
    texto = re.sub(r"^```(?:json)?\s*|\s*```$", "", texto)
    try:
        data = json.loads(texto)
    except json.JSONDecodeError:
        m = re.search(r"\{.*\}", texto, re.S)
        if not m:
            return {}
        try:
            data = json.loads(m.group(0))
        except json.JSONDecodeError:
            return {}
    return data if isinstance(data, dict) else {}


def _es_numero(v) -> bool:
    if isinstance(v, bool):
        return False
    if isinstance(v, (int, float)):
        return True
    try:
        float(str(v))
        return True
    except (TypeError, ValueError):
        return False


def _grafico(propuesto, columnas: list[dict]) -> dict:
    nombres = [c["nombre"] for c in columnas]
    if isinstance(propuesto, dict) and propuesto.get("tipo") in ("barras", "linea") \
            and propuesto.get("x") in nombres and propuesto.get("y") in nombres:
        return {"tipo": propuesto["tipo"], "x": propuesto["x"], "y": propuesto["y"]}
    numericos = {"bigint", "integer", "double", "real", "smallint", "tinyint"}
    if len(columnas) >= 2 and (columnas[-1]["tipo"] in numericos or columnas[-1]["tipo"].startswith("decimal")):
        x = nombres[0]
        tipo = "linea" if re.search(r"mes|anio|ano|fecha|semana|dia$", x) else "barras"
        return {"tipo": tipo, "x": x, "y": nombres[-1]}
    return {"tipo": "ninguno"}


def numero_co(v) -> str:
    """1234567.5 -> «1.234.567,5» (formato colombiano)."""
    try:
        f = float(v)
    except (TypeError, ValueError):
        return str(v)
    if f.is_integer():
        return f"{int(f):,}".replace(",", ".")
    texto = f"{f:,.2f}".rstrip("0").rstrip(".")
    return texto.replace(",", "§").replace(".", ",").replace("§", ".")


def resumen_basico(explicacion: str, r) -> str:
    base = (explicacion or "Resultado").rstrip(".")
    if not r.filas:
        return f"{base}: no hay datos que cumplan esos filtros."
    if len(r.filas) == 1 and len(r.columnas) == 1:
        return f"{base}: {numero_co(r.filas[0][0])}."
    if len(r.columnas) >= 2 and _es_numero(r.filas[0][-1]):
        etiquetas = [str(f[0]) for f in r.filas]
        valores = [f[-1] for f in r.filas]
        if len(r.filas) == 1:
            return f"{base}: {etiquetas[0]} con {numero_co(valores[0])}."
        temporal = re.search(r"mes|anio|ano|fecha|dia|hora", r.columnas[0]["nombre"])
        nums = [float(v) for v in valores if _es_numero(v)]
        if temporal:
            i_max = max(range(len(nums)), key=nums.__getitem__)
            return (f"{base}: de {etiquetas[0]} ({numero_co(valores[0])}) a {etiquetas[-1]} ({numero_co(valores[-1])}); "
                    f"el valor más alto fue {etiquetas[i_max]} con {numero_co(valores[i_max])}.")
        texto = f"{base}: {etiquetas[0]} encabeza con {numero_co(valores[0])}, seguido de {etiquetas[1]} ({numero_co(valores[1])})"
        if len(r.filas) == 3:
            texto += f" y {etiquetas[2]} ({numero_co(valores[2])})"
        elif len(r.filas) > 3:
            texto += f" y {len(r.filas) - 2} más"
        if r.columnas[-1]["tipo"] == "bigint":
            texto += f"; en total {numero_co(sum(nums))}"
        return texto + "." + (" El resultado se recortó al máximo de filas." if r.truncado else "")
    return f"{base}: {len(r.filas)} filas." + (" El resultado se recortó al máximo de filas." if r.truncado else "")


agente_lago = AgenteDataLake()
