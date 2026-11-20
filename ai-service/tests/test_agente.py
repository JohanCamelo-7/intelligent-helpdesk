import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.agent import Agent, estimar_prioridad
from app.providers.heuristic import HeuristicProvider
from app.security import decode_token

from .conftest import SECRET, make_token


def usuario(sub="11111111-1111-1111-1111-111111111111"):
    return decode_token(make_token(sub=sub), SECRET)


def test_flujo_completo_crea_ticket_solo_tras_confirmacion(fake_helpdesk):
    ag = Agent(helpdesk=fake_helpdesk, provider=HeuristicProvider())
    u = usuario()
    s, saludo = ag.nueva_sesion(u, consentimiento=True)
    assert "Hola Ana" in saludo.texto

    r = ag.turno(s, "La impresora tiene papel atascado y no deja imprimir", u)
    assert r.etapa == "EQUIPO" and "Mientras tanto" in r.texto
    r = ag.turno(s, "Es la IMP-BOG-01", u)
    assert r.etapa == "DESDE"
    r = ag.turno(s, "Desde esta mañana", u)
    assert r.etapa == "IMPACTO"
    r = ag.turno(s, "A mi área, no podemos imprimir ni trabajar", u)
    assert r.etapa == "BORRADOR"
    assert r.borrador["equipoId"] == 7 and r.borrador["impacto"] == "AREA" and r.borrador["bloqueaTrabajo"]
    assert r.borrador["prioridadEstimada"] == "ALTA"
    assert fake_helpdesk.creados == []           # nada se crea sin confirmación

    r = ag.turno(s, "Sí, confirmo", u)
    assert r.etapa == "CREADO" and "SOP-000100" in r.texto
    token, datos = fake_helpdesk.creados[0]
    assert token == u.token                      # usa el JWT del propio usuario
    assert datos["sedeId"] == 1 and datos["equipoId"] == 7 and "Desde esta mañana" in datos["descripcion"]


def test_decir_no_en_el_borrador_no_crea_nada(fake_helpdesk):
    ag = Agent(helpdesk=fake_helpdesk, provider=HeuristicProvider())
    u = usuario()
    s, _ = ag.nueva_sesion(u, False)
    ag.turno(s, "El correo de Outlook no me sincroniza los mensajes", u)
    ag.turno(s, "ninguno", u)
    ag.turno(s, "desde ayer", u)
    r = ag.turno(s, "solo a mí, puedo seguir trabajando", u)
    assert r.etapa == "BORRADOR" and r.borrador["prioridadEstimada"] == "BAJA"
    r = ag.turno(s, "no, quiero editar", u)
    assert r.etapa == "PROBLEMA" and fake_helpdesk.creados == []


def test_confirmar_borrador_vencido_no_crea(fake_helpdesk):
    ag = Agent(helpdesk=fake_helpdesk, provider=HeuristicProvider())
    u = usuario()
    s, _ = ag.nueva_sesion(u, False)
    r = ag.confirmar(s, "borrador-falso", u, {})
    assert "no está vigente" in r.texto and fake_helpdesk.creados == []


def test_consulta_de_estado_y_mis_tickets(fake_helpdesk):
    ag = Agent(helpdesk=fake_helpdesk, provider=HeuristicProvider())
    u = usuario()
    s, _ = ag.nueva_sesion(u, False)
    assert "en curso" in ag.turno(s, "¿cuál es el estado de SOP-42?", u).texto
    assert "Laura Gómez" in ag.turno(s, "muéstrame mis tickets", u).texto
    assert "No encontré" in ag.turno(s, "estado de SOP-000999", u).texto


def test_la_sesion_pertenece_a_su_usuario(fake_helpdesk):
    ag = Agent(helpdesk=fake_helpdesk, provider=HeuristicProvider())
    s, _ = ag.nueva_sesion(usuario(), False)
    with pytest.raises(KeyError):
        ag.obtener(s.id, usuario(sub="22222222-2222-2222-2222-222222222222"))


def test_fin_de_sesion_envia_solo_metadatos(fake_helpdesk):
    ag = Agent(helpdesk=fake_helpdesk, provider=HeuristicProvider())
    u = usuario()
    s, _ = ag.nueva_sesion(u, True)
    ag.turno(s, "mis tickets", u)
    datos = ag.terminar(s.id, u)
    assert set(datos) == {"sesionId", "inicio", "fin", "turnos", "intencion", "ticketId", "usoVoz", "consentimiento", "proveedor"}
    assert fake_helpdesk.sesiones[0]["turnos"] == 1


@pytest.mark.parametrize("impacto,bloquea,cat,esperado", [
    ("INDIVIDUAL", False, None, "BAJA"), ("SEDE", True, None, "CRITICA"),
    ("AREA", False, "REDES", "ALTA"), ("INDIVIDUAL", False, "REDES", "BAJA")])
def test_prioridad_igual_que_el_backend(impacto, bloquea, cat, esperado):
    assert estimar_prioridad(impacto, bloquea, cat) == esperado


def test_tokens_invalidos():
    with pytest.raises(HTTPException):
        decode_token(make_token(secret="otro-secreto-de-al-menos-treinta-y-dos-caracteres"), SECRET)
    with pytest.raises(HTTPException):
        decode_token(make_token(exp_delta=-10), SECRET)
    with pytest.raises(HTTPException):
        decode_token(make_token(iss="otro"), SECRET)


def test_api_exige_autenticacion_y_responde(fake_helpdesk, monkeypatch):
    from app import main
    monkeypatch.setattr(main.agent, "helpdesk", fake_helpdesk)
    monkeypatch.setattr(main.agent, "_provider", HeuristicProvider())
    client = TestClient(main.app)
    assert client.get("/health").json()["estado"] == "ok"
    assert client.post("/api/v1/agent/sesiones", json={}).status_code == 401
    h = {"Authorization": f"Bearer {make_token()}"}
    s = client.post("/api/v1/agent/sesiones", json={"consentimiento": False}, headers=h).json()
    r = client.post("/api/v1/agent/chat", json={"sesion_id": s["sesion_id"], "mensaje": "mis tickets"}, headers=h)
    assert r.status_code == 200 and "SOP-000042" in r.json()["respuesta"]
    otro = {"Authorization": f"Bearer {make_token(sub='22222222-2222-2222-2222-222222222222')}"}
    assert client.post("/api/v1/agent/chat", json={"sesion_id": s["sesion_id"], "mensaje": "hola"}, headers=otro).status_code == 404
