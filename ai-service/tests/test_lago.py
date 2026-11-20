"""Pruebas del agente de IA del Data Lake: guardia SQL, plantillas, ciclo de corrección con LLM y API."""
import datetime as dt
import random

import pytest
from fastapi.testclient import TestClient

from app.lake.agente import AgenteDataLake, LLMNoDisponible, extraer_json, numero_co
from app.lake.catalogo import TABLAS, catalogo
from app.lake.guardia import SQLRechazado, validar
from app.lake.plantillas import combinar, construir, interpretar
from app.lake.trino import Resultado, TrinoError

from .conftest import make_token

TABLAS_GOLD = set(TABLAS)


# ---------------------------------------------------------------------- dobles de prueba
class FakeTrino:
    """Ejecuta el SQL (dialecto Trino) en DuckDB sobre tablas Gold sintéticas, o devuelve resultados fijos."""

    def __init__(self, fijo: Resultado | None = None):
        self.consultas: list[str] = []
        self.fijo = fijo
        self.con = None

    def _duckdb(self):
        duckdb = pytest.importorskip("duckdb")
        if self.con is None:
            con = duckdb.connect()
            con.execute("CREATE SCHEMA gold")
            con.execute("CREATE TABLE gold.dim_sede AS SELECT * FROM (VALUES ('k1','SEDE_BOGOTA','Sede Bogotá','Bogotá'),"
                        "('k2','SEDE_BELLO','Sede Bello','Bello'),('k3','SEDE_GIRARDOT','CR Girardot','Girardot')) "
                        "v(sede_key, sede_codigo, nombre, ciudad)")
            con.execute("CREATE TABLE gold.dim_tecnico AS SELECT 't' || i AS tecnico_id, 'Técnico ' || i AS nombre, "
                        "true AS es_actual FROM range(1, 6) r(i)")
            con.execute("CREATE TABLE gold.fact_tickets (numero varchar, fecha_creacion date, fecha_cierre date, "
                        "creado_en timestamp, sede_codigo varchar, categoria_codigo varchar, prioridad_codigo varchar, "
                        "estado_codigo varchar, tecnico_id varchar, min_hasta_asignacion int, min_hasta_atencion int, "
                        "min_resolucion int, reaperturas int, es_reincidente boolean, ia_acerto boolean, calificacion int, "
                        "es_demo boolean, equipo_codigo varchar, tipo_falla_codigo varchar, canal varchar)")
            rnd, filas = random.Random(7), []
            for i in range(1500):
                c = dt.datetime(2024, 1, 1) + dt.timedelta(minutes=rnd.randint(0, 1000 * 24 * 60))
                est = rnd.choice(["FINALIZADA"] * 8 + ["PENDIENTE", "CANCELADA"])
                fin = c + dt.timedelta(minutes=rnd.randint(30, 5000)) if est == "FINALIZADA" else None
                filas.append((f"SOP-{i}", c.date(), fin.date() if fin else None, c,
                              rnd.choice(["SEDE_BOGOTA", "SEDE_BELLO", "SEDE_GIRARDOT"]),
                              rnd.choice(["REDES", "IMPRESION", "HARDWARE"]), rnd.choice(["BAJA", "MEDIA", "ALTA", "CRITICA"]),
                              est, f"t{rnd.randint(1, 5)}", rnd.randint(1, 90), rnd.randint(1, 300),
                              int((fin - c).total_seconds() // 60) if fin else None, rnd.randint(0, 1), rnd.random() < .2,
                              rnd.choice([True, False, None]), rnd.choice([1, 2, 3, 4, 5, None]), True, "IMP-GIR-11",
                              "IMP_ATASCO", "WEB"))
            con.executemany("INSERT INTO gold.fact_tickets VALUES (" + ",".join("?" * 20) + ")", filas)
            self.con = con
        return self.con

    def consultar(self, sql, max_filas=None, schema="gold"):
        if "information_schema" in sql:
            raise TrinoError("sin catálogo en la prueba")
        self.consultas.append(sql)
        if self.fijo is not None:
            return self.fijo
        import sqlglot
        try:
            cur = self._duckdb().execute(sqlglot.transpile(sql, read="trino", write="duckdb")[0])
        except Exception as exc:  # noqa: BLE001
            raise TrinoError(str(exc)) from exc
        tipos = {"BIGINT": "bigint", "INTEGER": "integer", "DOUBLE": "double", "VARCHAR": "varchar", "DATE": "date"}
        cols = [{"nombre": d[0], "tipo": tipos.get(str(d[1]).split("(")[0], "double" if "DECIMAL" in str(d[1]) else "varchar")}
                for d in cur.description]
        filas = [[(float(v) if hasattr(v, "as_tuple") else v) for v in f] for f in cur.fetchall()]
        return Resultado(columnas=cols, filas=filas[: max_filas or 500], truncado=len(filas) > (max_filas or 500))


class FakeLLM:
    modelo = "modelo-de-prueba"

    def __init__(self, respuestas):
        self.respuestas = list(respuestas)
        self.llamadas: list[list[dict]] = []

    def json(self, mensajes):
        self.llamadas.append(list(mensajes))
        r = self.respuestas.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


@pytest.fixture(autouse=True)
def _catalogo_estatico():
    catalogo._cargado = 10 ** 12   # no intentar leer information_schema


# ---------------------------------------------------------------------- guardia SQL
@pytest.mark.parametrize("sql", [
    "SELECT count(*) FROM gold.fact_tickets",
    "select sede_codigo, count(*) from fact_tickets group by 1",
    "WITH x AS (SELECT * FROM gold.fact_tickets) SELECT count(*) FROM x",
    "SELECT step, status FROM ops.pipeline_runs",
])
def test_guardia_acepta_lecturas_y_pone_limite(sql):
    assert validar(sql, TABLAS_GOLD, 100).rstrip().endswith("LIMIT 100")


def test_guardia_respeta_limite_menor():
    assert validar("SELECT ciudad FROM gold.dim_sede LIMIT 3", TABLAS_GOLD, 100).rstrip().endswith("LIMIT 3")


@pytest.mark.parametrize("sql,motivo", [
    ("DELETE FROM gold.fact_tickets", "lectura"),
    ("INSERT INTO gold.dim_sede SELECT * FROM gold.dim_sede", "lectura"),
    ("DROP TABLE gold.dim_sede", "lectura"),
    ("SELECT 1 FROM gold.dim_sede; DROP TABLE gold.dim_sede", "una consulta"),
    ("SELECT * FROM silver.tickets", "Esquema no permitido"),
    ("SELECT * FROM bronze.domain_events", "Esquema no permitido"),
    ("SELECT * FROM system.runtime.nodes", "Catálogo no permitido"),
    ("SELECT * FROM postgresql.public.usuarios", "Catálogo no permitido"),
    ("SELECT * FROM lake.information_schema.columns", "Esquema no permitido"),
    ("SELECT * FROM gold.tickets", "no existe"),
    ("SELECT 1", "al menos una tabla"),
    ("", "vacía"),
])
def test_guardia_rechaza(sql, motivo):
    with pytest.raises(SQLRechazado, match=motivo):
        validar(sql, TABLAS_GOLD, 100)


# ---------------------------------------------------------------------- plantillas
@pytest.mark.parametrize("pregunta,metrica,dimension,filtros", [
    ("¿Cuántos tickets hubo por sede?", "tickets", "sede", set()),
    ("Tendencia mensual de tickets en 2025", "tickets", "mes", {"periodo"}),
    ("Tiempo promedio de resolución por sede", "resolucion_h", "sede", set()),
    ("¿Qué categoría tiene más tickets en Girardot?", "tickets", "categoria", {"sede"}),
    ("Los 5 técnicos con más tickets resueltos", "finalizados", "tecnico", set()),
    ("Satisfacción promedio por sede", "satisfaccion", "sede", set()),
    ("Acierto de la IA por año", "acierto_ia", "anio", set()),
    ("Reincidencias por categoría en Bello", "reincidencia", "categoria", {"sede"}),
    ("cuantos tickets de impresoras hubo en marzo de 2025 en bogota", "tickets", None, {"sede", "periodo", "mes", "categoria"}),
    ("cuanto se demora la asignacion en girardot", "asignacion", None, {"sede"}),
])
def test_plantillas_interpretan(pregunta, metrica, dimension, filtros):
    it = interpretar(pregunta)
    assert (it.metrica, it.dimension, set(it.filtros)) == (metrica, dimension, filtros)


def test_plantillas_no_inventan_respuesta():
    assert not interpretar("hola, ¿cómo estás?").reconocida


def test_seguimiento_reutiliza_pregunta_anterior():
    it = combinar(interpretar("y en Bello?"), interpretar("tiempo promedio de resolución por categoría"))
    assert (it.metrica, it.dimension) == ("resolucion_h", "categoria")
    assert "SEDE_BELLO" in it.filtros["sede"][0]


def test_todas_las_sugeridas_se_ejecutan():
    from app.lake.catalogo import PREGUNTAS_SUGERIDAS
    trino = FakeTrino()
    agente = AgenteDataLake(trino=trino, llm=None)
    for pregunta in PREGUNTAS_SUGERIDAS:
        r = agente._con_plantillas(pregunta, [])
        assert not r["aclaracion"], (pregunta, r["respuesta"])
        assert r["filas"], pregunta
        assert r["respuesta"].strip()


def test_respuesta_plantilla_por_sede():
    agente = AgenteDataLake(trino=FakeTrino(), llm=None)
    r = agente._con_plantillas("¿Cuántos tickets hubo por sede?", [])
    assert {f[0] for f in r["filas"]} == {"Bogotá", "Bello", "Girardot"}
    assert sum(f[1] for f in r["filas"]) == 1500
    assert r["grafico"] == {"tipo": "barras", "x": "sede", "y": "tickets"}
    assert "1.500" in r["respuesta"]


# ---------------------------------------------------------------------- ciclo con LLM
def test_llm_corrige_su_consulta_con_el_error():
    trino = FakeTrino()
    llm = FakeLLM([
        {"sql": "SELECT count(*) AS tickets FROM gold.tickets"},                       # tabla inexistente
        {"sql": "SELECT count(*) AS tickets FROM gold.fact_tickets WHERE sede_codigo = 'SEDE_GIRARDOT'",
         "explicacion": "Tickets de Girardot"},
        {"respuesta": "Girardot registró 500 tickets."},
    ])
    r = AgenteDataLake(trino=trino, llm=llm).preguntar("¿cuántos tickets tiene Girardot?")
    assert r["modo"] == "ia" and not r["aclaracion"]
    assert len(r["intentos"]) == 2 and "no existe" in r["intentos"][0]["error"]
    assert "rechazada" in llm.llamadas[1][-1]["content"]          # el error volvió al modelo
    assert len(trino.consultas) == 1                               # la consulta inválida nunca llegó a Trino
    assert r["respuesta"] == "Girardot registró 500 tickets."


def test_llm_malicioso_nunca_llega_a_trino():
    trino = FakeTrino()
    llm = FakeLLM([{"sql": "DELETE FROM gold.fact_tickets"}] * 3)
    r = AgenteDataLake(trino=trino, llm=llm).preguntar("borra todo")
    assert r["aclaracion"] and trino.consultas == []
    assert all("lectura" in i["error"] for i in r["intentos"])


def test_error_de_trino_tambien_se_corrige():
    trino = FakeTrino()
    llm = FakeLLM([
        {"sql": "SELECT columna_que_no_existe FROM gold.fact_tickets"},
        {"sql": "SELECT count(*) AS tickets FROM gold.fact_tickets"},
        {"respuesta": "Hay 1.500 tickets."},
    ])
    r = AgenteDataLake(trino=trino, llm=llm).preguntar("total de tickets")
    assert r["filas"] == [[1500]] and r["intentos"][0]["error"]


def test_aclaracion_del_modelo():
    llm = FakeLLM([{"aclaracion": "No tengo datos del clima."}])
    r = AgenteDataLake(trino=FakeTrino(), llm=llm).preguntar("¿va a llover mañana?")
    assert r["aclaracion"] and "clima" in r["respuesta"]


def test_sin_modelo_disponible_usa_plantillas():
    llm = FakeLLM([LLMNoDisponible("conexión rechazada")])
    r = AgenteDataLake(trino=FakeTrino(), llm=llm).preguntar("¿Cuántos tickets hubo por sede?")
    assert r["modo"] == "plantillas" and r["filas"] and "plantillas" in r["advertencias"][0]


def test_si_falla_la_redaccion_se_responde_con_reglas():
    llm = FakeLLM([{"sql": "SELECT count(*) AS tickets FROM gold.fact_tickets", "explicacion": "Total de tickets"},
                   LLMNoDisponible("tiempo agotado")])
    r = AgenteDataLake(trino=FakeTrino(), llm=llm).preguntar("total")
    assert r["respuesta"] == "Total de tickets: 1.500."


def test_historial_se_envia_al_modelo():
    llm = FakeLLM([{"sql": "SELECT count(*) AS t FROM gold.fact_tickets"}, {"respuesta": "ok"}])
    AgenteDataLake(trino=FakeTrino(), llm=llm).preguntar(
        "¿y en Bello?", [{"pregunta": "tickets en Girardot", "sql": "SELECT count(*) FROM gold.fact_tickets"}])
    roles = [m["role"] for m in llm.llamadas[0]]
    assert roles == ["system", "user", "assistant", "user"]


def test_utilidades():
    assert extraer_json('```json\n{"sql": "SELECT 1"}\n```') == {"sql": "SELECT 1"}
    assert extraer_json("texto sin json") == {}
    assert numero_co(1234567.5) == "1.234.567,5" and numero_co(1500) == "1.500"


# ---------------------------------------------------------------------- API
def test_api_solo_admin(monkeypatch):
    from app import main
    fijo = Resultado(columnas=[{"nombre": "tickets", "tipo": "bigint"}], filas=[[42]])
    monkeypatch.setattr(main, "agente_lago", AgenteDataLake(trino=FakeTrino(fijo), llm=None))
    client = TestClient(main.app)
    cuerpo = {"pregunta": "¿cuántos tickets hay?"}
    assert client.post("/api/v1/agent/lago/preguntar", json=cuerpo).status_code == 401
    usuario = {"Authorization": f"Bearer {make_token(rol='USER')}"}
    assert client.post("/api/v1/agent/lago/preguntar", json=cuerpo, headers=usuario).status_code == 403
    admin = {"Authorization": f"Bearer {make_token(rol='ADMIN')}"}
    r = client.post("/api/v1/agent/lago/preguntar", json=cuerpo, headers=admin)
    assert r.status_code == 200 and r.json()["filas"] == [[42]] and "42" in r.json()["respuesta"]
    e = client.get("/api/v1/agent/lago/estado", headers=admin).json()
    assert e["modo"] == "plantillas" and "gold.fact_tickets" in e["tablas"]
