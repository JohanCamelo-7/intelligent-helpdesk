"""data-ingestion: consume TODOS los eventos (#) y los escribe en la zona Landing del lago.

Garantías:
  - Sin pérdida: el mensaje se confirma (ack) solo después de escribir el archivo en S3.
  - Sin duplicados obvios: caché de event_id; Bronze vuelve a deduplicar con anti-join.
  - Lo inválido no se pierde: va a quarantine/ con el motivo.
"""
from __future__ import annotations

import asyncio
import logging
import os

import aio_pika
import boto3
from botocore.config import Config

from .batcher import Batcher

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("data-ingestion")

RABBITMQ_URL = os.getenv("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/")
BUCKET = os.getenv("LAKE_BUCKET", "lake")
FLUSH_SECONDS = float(os.getenv("FLUSH_SECONDS", "10"))
FLUSH_MAX = int(os.getenv("FLUSH_MAX_EVENTS", "500"))
QUEUE = "lake.ingestion"


def s3_client():
    return boto3.client(
        "s3", endpoint_url=os.getenv("S3_ENDPOINT", "http://localhost:8333"), region_name=os.getenv("S3_REGION", "us-east-1"),
        aws_access_key_id=os.getenv("S3_ACCESS_KEY"), aws_secret_access_key=os.getenv("S3_SECRET_KEY"),
        config=Config(s3={"addressing_style": "path"}, request_checksum_calculation="when_required",
                      response_checksum_validation="when_required", retries={"max_attempts": 5}))


class Ingestor:
    def __init__(self) -> None:
        self.batcher = Batcher()
        self.pending: list[aio_pika.abc.AbstractIncomingMessage] = []
        self.s3 = s3_client()
        self.lock = asyncio.Lock()

    async def on_message(self, msg: aio_pika.abc.AbstractIncomingMessage) -> None:
        async with self.lock:
            self.batcher.add(msg.body, msg.routing_key or "")
            self.pending.append(msg)
            if len(self.pending) >= FLUSH_MAX:
                await self._flush()

    async def periodic(self) -> None:
        while True:
            await asyncio.sleep(FLUSH_SECONDS)
            async with self.lock:
                if self.pending:
                    await self._flush()

    async def _flush(self) -> None:
        files = self.batcher.drain()
        try:
            for key, body in files.items():
                await asyncio.to_thread(self.s3.put_object, Bucket=BUCKET, Key=key, Body=body,
                                        ContentType="application/x-ndjson")
        except Exception as exc:  # noqa: BLE001
            # No se confirma nada: RabbitMQ volverá a entregar los mensajes.
            log.error("No se pudo escribir en Landing (%s); %d mensajes se reintentarán", exc, len(self.pending))
            for m in self.pending:
                await m.nack(requeue=True)
            self.pending = []
            self.batcher = Batcher()
            return
        for m in self.pending:
            await m.ack()
        log.info("Landing: %d archivos, %d mensajes confirmados, %d duplicados descartados",
                 len(files), len(self.pending), self.batcher.duplicates)
        self.pending = []


async def main() -> None:
    connection = None
    for attempt in range(60):
        try:
            connection = await aio_pika.connect_robust(RABBITMQ_URL)
            break
        except Exception as exc:  # noqa: BLE001
            log.info("Esperando RabbitMQ (%s) %d/60", exc, attempt + 1)
            await asyncio.sleep(3)
    if connection is None:
        raise SystemExit("RabbitMQ no disponible")
    ing = Ingestor()
    channel = await connection.channel()
    await channel.set_qos(prefetch_count=FLUSH_MAX * 2)
    exchange = await channel.declare_exchange("helpdesk.events", aio_pika.ExchangeType.TOPIC, durable=True)
    await channel.declare_exchange("helpdesk.dlx", aio_pika.ExchangeType.FANOUT, durable=True)
    queue = await channel.declare_queue(QUEUE, durable=True, arguments={"x-dead-letter-exchange": "helpdesk.dlx"})
    await queue.bind(exchange, routing_key="#")
    await queue.consume(ing.on_message)
    log.info("Ingesta activa: %s -> s3://%s/landing", QUEUE, BUCKET)
    await ing.periodic()


if __name__ == "__main__":
    asyncio.run(main())
