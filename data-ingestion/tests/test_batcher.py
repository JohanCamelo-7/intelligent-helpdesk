import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ingestion.batcher import Batcher, validate  # noqa: E402

NOW = datetime(2026, 11, 12, 15, 30, tzinfo=timezone.utc)


def env(eid="0b6c3f57-7a3d-4f0e-9c6e-6b5b1c2d3e4f", etype="TicketCreated"):
    return {"event_id": eid, "event_type": etype, "occurred_at": "2026-11-12T15:00:00Z", "source": "helpdesk-api",
            "payload": {"ticket_id": "t1"}}


def test_valida_sobre():
    assert validate(env()) is None
    assert "event_id" in validate({**env(), "event_id": "x"})
    assert "payload" in validate({**env(), "payload": "texto"})
    assert "occurred_at" in validate({**env(), "occurred_at": "ayer"})


def test_agrupa_por_tipo_y_fecha_de_ingesta():
    b = Batcher()
    assert b.add(json.dumps(env()).encode(), "ticket.created", NOW) == "aceptado"
    assert b.add(json.dumps(env("1b6c3f57-7a3d-4f0e-9c6e-6b5b1c2d3e4f", "TicketClosed")).encode(), "ticket.closed", NOW) == "aceptado"
    files = b.drain(NOW)
    keys = sorted(files)
    assert keys[0].startswith("landing/events/TicketClosed/dt=2026-11-12/")
    assert keys[1].startswith("landing/events/TicketCreated/dt=2026-11-12/")
    linea = json.loads(files[keys[1]].decode().strip())
    assert linea["_routing_key"] == "ticket.created" and linea["_ingested_at"].endswith("Z")
    assert len(b) == 0


def test_descarta_duplicados_y_pone_en_cuarentena_lo_invalido():
    b = Batcher()
    raw = json.dumps(env()).encode()
    assert b.add(raw, "x", NOW) == "aceptado"
    assert b.add(raw, "x", NOW) == "duplicado"
    assert b.add(b"{no es json", "x", NOW) == "rechazado"
    assert b.add(json.dumps({**env(), "payload": None}).encode(), "x", NOW) == "rechazado"
    files = b.drain(NOW)
    q = [k for k in files if k.startswith("quarantine/")]
    assert len(q) == 1 and files[q[0]].decode().count("\n") == 2
    assert b.duplicates == 1
