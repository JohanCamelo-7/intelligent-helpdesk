"""Consumo de eventos (ticket.created, attachment.uploaded) y publicación de AiDiagnosisCompleted."""
from __future__ import annotations

import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone

import aio_pika

from .analysis import analyze, combine
from .config import settings
from .helpdesk_client import HelpdeskClient, HelpdeskError
from .providers import get_provider

log = logging.getLogger(__name__)

EXCHANGE = "helpdesk.events"
DLX = "helpdesk.dlx"
QUEUE = "ai.requests"
# event_id determinista: si el mismo evento de origen llega dos veces, el resultado tiene el mismo id
# y helpdesk-api lo descarta por idempotencia.
NAMESPACE = uuid.UUID("6f1c8a52-1f7e-4d67-9a3b-2b7cfb0a1d11")


def build_result(ticket_id: str, trigger: str, source_event_id: str, text, images, provider) -> dict:
    diags = ([text] if text is not None else []) + list(images)
    comb = combine(text, images) if text is not None else combine(images[0], images[1:]) if images else {}
    return {
        "ticket_id": ticket_id,
        "trigger": trigger,
        "evento_origen_id": source_event_id,
        "proveedor": provider.name,
        "modelo": provider.model,
        "diagnosticos": [d.to_dict() for d in diags],
        "combinado": comb,
    }


def envelope(event_type: str, aggregate_id: str, payload: dict, correlation_id: str | None, event_id: str) -> dict:
    return {
        "event_id": event_id,
        "event_type": event_type,
        "event_version": 1,
        "occurred_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "aggregate_type": "Ticket",
        "aggregate_id": aggregate_id,
        "correlation_id": correlation_id or str(uuid.uuid4()),
        "source": "ai-service",
        "payload": payload,
    }


def process_event(env: dict, helpdesk: HelpdeskClient, provider=None) -> dict | None:
    """Lógica síncrona (testeable): devuelve el sobre del resultado o None si no aplica."""
    provider = provider or get_provider()
    etype = env.get("event_type")
    p = env.get("payload") or {}
    if p.get("es_demo"):
        return None  # los escenarios de demostración no consumen IA real
    ticket_id = p.get("ticket_id")
    if not ticket_id:
        return None
    if etype == "TicketCreated":
        ctx = helpdesk.contexto(ticket_id)
        text, imgs = analyze(provider, ctx, ctx.get("adjuntos", []))
        payload = build_result(ticket_id, "TICKET_CREATED", env["event_id"], text, imgs, provider)
    elif etype == "AttachmentUploaded":
        if p.get("durante_creacion"):
            return None  # ya se analizan junto con TicketCreated
        ctx = helpdesk.contexto(ticket_id)
        adj = [a for a in ctx.get("adjuntos", []) if a.get("id") == p.get("adjunto_id")]
        _, imgs = analyze(provider, {**ctx, "descripcion": ctx.get("descripcion", "")}, adj)
        if not imgs:
            return None
        payload = build_result(ticket_id, "ATTACHMENT_UPLOADED", env["event_id"], None, imgs, provider)
    else:
        return None
    event_id = str(uuid.uuid5(NAMESPACE, env["event_id"]))
    return envelope("AiDiagnosisCompleted", ticket_id, payload, env.get("correlation_id"), event_id)


class EventBus:
    def __init__(self) -> None:
        self.connection: aio_pika.abc.AbstractRobustConnection | None = None
        self.exchange: aio_pika.abc.AbstractExchange | None = None
        self.helpdesk = HelpdeskClient()

    async def start(self) -> None:
        for attempt in range(30):
            try:
                self.connection = await aio_pika.connect_robust(settings.rabbitmq_url)
                break
            except Exception as exc:  # noqa: BLE001
                log.info("RabbitMQ no disponible (%s), reintento %d/30", exc, attempt + 1)
                await asyncio.sleep(3)
        if self.connection is None:
            log.error("No se pudo conectar a RabbitMQ; el análisis por eventos queda desactivado")
            return
        channel = await self.connection.channel()
        await channel.set_qos(prefetch_count=2)
        self.exchange = await channel.declare_exchange(EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True)
        await channel.declare_exchange(DLX, aio_pika.ExchangeType.FANOUT, durable=True)
        queue = await channel.declare_queue(QUEUE, durable=True, arguments={"x-dead-letter-exchange": DLX})
        await queue.bind(self.exchange, routing_key="ticket.created")
        await queue.bind(self.exchange, routing_key="attachment.uploaded")
        await queue.consume(self._on_message)
        log.info("Consumiendo eventos de %s", QUEUE)

    async def _on_message(self, message: aio_pika.abc.AbstractIncomingMessage) -> None:
        async with message.process(requeue=False):  # si falla, va a la cola de mensajes muertos
            env = json.loads(message.body)
            try:
                result = await asyncio.to_thread(process_event, env, self.helpdesk)
            except HelpdeskError as exc:
                if exc.status == 404:
                    log.info("Ticket del evento %s no existe; se descarta", env.get("event_id"))
                    return
                raise
            if result is not None:
                await self.publish(result)
                log.info("Diagnóstico publicado para ticket %s (%s)", result["aggregate_id"],
                         result["payload"]["combinado"].get("regla"))

    async def publish(self, env: dict) -> None:
        assert self.exchange is not None
        await self.exchange.publish(
            aio_pika.Message(body=json.dumps(env, ensure_ascii=False).encode(), content_type="application/json",
                             message_id=env["event_id"], delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                             headers={"event_type": env["event_type"]}),
            routing_key="ai.diagnosis.completed",
        )

    async def stop(self) -> None:
        if self.connection:
            await self.connection.close()
