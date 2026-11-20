"""Agrupa eventos en archivos JSONL para la zona Landing del Data Lake (lógica pura, testeable).

Ruta: landing/events/{event_type}/dt={fecha de ingesta UTC}/{marca}-{uuid}.jsonl
La partición es por fecha de INGESTA (no de negocio) para que la carga a Bronze sea
incremental aunque lleguen eventos con fecha antigua.
"""
from __future__ import annotations

import json
import re
import uuid
from collections import OrderedDict, defaultdict
from datetime import datetime, timezone

UUID_RE = re.compile(r"^[0-9a-fA-F-]{36}$")
TYPE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9]{2,59}$")
REQUIRED = ("event_id", "event_type", "occurred_at", "source", "payload")


def validate(env: object) -> str | None:
    """Devuelve el motivo de rechazo, o None si el sobre es válido."""
    if not isinstance(env, dict):
        return "no es un objeto JSON"
    for k in REQUIRED:
        if k not in env or env[k] in (None, ""):
            return f"falta el campo {k}"
    if not UUID_RE.match(str(env["event_id"])):
        return "event_id no es un UUID"
    if not TYPE_RE.match(str(env["event_type"])):
        return "event_type inválido"
    if not isinstance(env["payload"], dict):
        return "payload no es un objeto"
    try:
        datetime.fromisoformat(str(env["occurred_at"]).replace("Z", "+00:00"))
    except ValueError:
        return "occurred_at no es una fecha ISO-8601"
    return None


class SeenCache:
    """Recuerda los últimos N event_id para descartar duplicados obvios (Bronze vuelve a deduplicar)."""

    def __init__(self, capacity: int = 50_000):
        self.capacity = capacity
        self.items: OrderedDict[str, None] = OrderedDict()

    def seen(self, key: str) -> bool:
        if key in self.items:
            self.items.move_to_end(key)
            return True
        self.items[key] = None
        if len(self.items) > self.capacity:
            self.items.popitem(last=False)
        return False


class Batcher:
    def __init__(self) -> None:
        self.valid: dict[str, list[dict]] = defaultdict(list)
        self.rejected: list[dict] = []
        self.seen = SeenCache()
        self.duplicates = 0

    def __len__(self) -> int:
        return sum(len(v) for v in self.valid.values()) + len(self.rejected)

    def add(self, raw: bytes, routing_key: str, now: datetime | None = None) -> str:
        now = now or datetime.now(timezone.utc)
        try:
            env = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            self.rejected.append({"motivo": "JSON inválido", "raw": raw[:2000].decode("utf-8", "replace"),
                                  "_routing_key": routing_key, "_ingested_at": now.isoformat()})
            return "rechazado"
        motivo = validate(env)
        if motivo:
            self.rejected.append({"motivo": motivo, "raw": env, "_routing_key": routing_key, "_ingested_at": now.isoformat()})
            return "rechazado"
        if self.seen.seen(str(env["event_id"])):
            self.duplicates += 1
            return "duplicado"
        env = dict(env)
        env["_routing_key"] = routing_key
        env["_ingested_at"] = now.isoformat().replace("+00:00", "Z")
        self.valid[env["event_type"]].append(env)
        return "aceptado"

    def drain(self, now: datetime | None = None) -> dict[str, bytes]:
        """Devuelve {clave_s3: contenido JSONL} y vacía el lote."""
        now = now or datetime.now(timezone.utc)
        dt = now.strftime("%Y-%m-%d")
        stamp = now.strftime("%Y%m%dT%H%M%S")
        files: dict[str, bytes] = {}
        for etype, events in self.valid.items():
            if events:
                key = f"landing/events/{etype}/dt={dt}/{stamp}-{uuid.uuid4().hex[:12]}.jsonl"
                files[key] = ("\n".join(json.dumps(e, ensure_ascii=False) for e in events) + "\n").encode()
        if self.rejected:
            key = f"quarantine/landing/dt={dt}/{stamp}-{uuid.uuid4().hex[:12]}.jsonl"
            files[key] = ("\n".join(json.dumps(e, ensure_ascii=False, default=str) for e in self.rejected) + "\n").encode()
        self.valid = defaultdict(list)
        self.rejected = []
        return files
