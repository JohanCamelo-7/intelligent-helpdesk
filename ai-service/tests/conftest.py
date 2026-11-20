import os
import sys
import time
from pathlib import Path

import jwt
import pytest

os.environ.setdefault("CONSUME_EVENTS", "false")
os.environ.setdefault("STT_ENABLED", "false")
os.environ.setdefault("TTS_ENABLED", "false")
os.environ.setdefault("LLM_PROVIDER", "heuristic")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

SECRET = "cambia-este-secreto-jwt-de-al-menos-32-caracteres"


def make_token(sub="11111111-1111-1111-1111-111111111111", rol="USER", exp_delta=600, secret=SECRET, iss="helpdesk-api"):
    now = int(time.time())
    return jwt.encode({"sub": sub, "roles": [rol], "nombre": "Ana López", "email": "ana@x.co", "iss": iss,
                       "iat": now, "exp": now + exp_delta}, secret, algorithm="HS256")


class FakeHelpdesk:
    """Doble de prueba de HelpdeskClient: registra las llamadas y responde con datos fijos."""

    def __init__(self):
        self.creados = []
        self.sesiones = []
        self.equipos = [{"id": 7, "codigo": "IMP-BOG-01", "tipo": "IMPRESORA", "marca": "HP", "modelo": "M404", "sedeId": 1}]

    def me(self, token):
        return {"id": "u1", "sedeId": 1}

    def catalogos(self, token):
        return {"sedes": [{"id": 1, "codigo": "SEDE_BOGOTA", "nombre": "UNIMINUTO Sede Principal Bogotá"}], "equipos": self.equipos}

    def ticket_por_numero(self, token, numero):
        from app.helpdesk_client import HelpdeskError
        if numero != "SOP-000042":
            raise HelpdeskError(404, "no")
        return {"resumen": {"id": "t42", "numero": numero, "estado": "EN_CURSO", "prioridad": "ALTA",
                            "tecnicoNombre": "Laura Gómez", "requiereRevision": False}, "solucion": None}

    def mis_tickets(self, token, size=5):
        return {"content": [{"numero": "SOP-000042", "estado": "EN_CURSO", "tecnicoNombre": "Laura Gómez"}]}

    def crear_ticket(self, token, datos):
        self.creados.append((token, datos))
        return {"resumen": {"id": "nuevo", "numero": "SOP-000100", "estado": "PENDIENTE"}}

    def registrar_sesion(self, token, datos):
        self.sesiones.append(datos)

    def contexto(self, ticket_id):
        return {"ticketId": ticket_id, "descripcion": "La impresora tiene papel atascado y no imprime",
                "sedeCodigo": "SEDE_BOGOTA", "equipoTipo": "IMPRESORA", "adjuntos": []}


@pytest.fixture
def fake_helpdesk():
    return FakeHelpdesk()
