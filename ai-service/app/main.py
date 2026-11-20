"""ai-service: clasificación de texto, visión artificial, agente conversacional y voz."""
from __future__ import annotations

import asyncio
import logging
import threading
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from .agent import agent
from .config import settings
from .events import EventBus
from .helpdesk_client import HelpdeskError
from .lake.agente import agente_lago
from .providers import get_provider
from .security import User, current_user
from .taxonomy import taxonomy
from .voice import voice

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("ai-service")
bus = EventBus()


def _cargar_catalogo_con_reintentos() -> None:
    import time
    for _ in range(20):
        taxonomy.load()
        if taxonomy.equipos:
            return
        time.sleep(5)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    threading.Thread(target=_cargar_catalogo_con_reintentos, daemon=True).start()
    if settings.consume_events:
        asyncio.create_task(bus.start())
    voice.warm_up()
    log.info("Proveedor de IA: %s (%s)", get_provider().name, get_provider().model)
    yield
    await bus.stop()


app = FastAPI(title="ai-service", version="1.0.0", lifespan=lifespan,
              docs_url="/api/v1/agent/docs", openapi_url="/api/v1/agent/openapi.json")


class NuevaSesion(BaseModel):
    consentimiento: bool = False  # autoriza guardar metadatos de la interacción de voz


class Mensaje(BaseModel):
    sesion_id: str
    mensaje: str = Field(min_length=1, max_length=2000)
    voz: bool = False             # devolver también audio sintetizado


class Confirmacion(BaseModel):
    sesion_id: str
    descripcion: str | None = Field(default=None, max_length=4000)
    sedeId: int | None = None
    equipoId: int | None = None
    impacto: str | None = Field(default=None, pattern="^(INDIVIDUAL|AREA|SEDE)$")
    bloqueaTrabajo: bool | None = None


def _sesion(sesion_id: str, user: User):
    try:
        return agent.obtener(sesion_id, user)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="La sesión expiró. Abre una nueva conversación.") from exc


async def _con_audio(data: dict, quiere_audio: bool) -> dict:
    data["audio_wav_base64"] = await run_in_threadpool(voice.synthesize_b64, data["respuesta"]) if quiere_audio else None
    return data


@app.get("/health")
def health() -> dict:
    p = get_provider()
    return {"estado": "ok", "proveedor": p.name, "modelo": p.model, "vision": p.supports_vision,
            "eventos": bus.exchange is not None, "tipos_falla": len(taxonomy.types)}


@app.get("/api/v1/agent/estado")
def estado(_user: User = Depends(current_user)) -> dict:
    p = get_provider()
    return {"proveedor": p.name, "modelo": p.model, "vision": p.supports_vision, "voz": voice.status()}


@app.post("/api/v1/agent/sesiones")
async def crear_sesion(body: NuevaSesion, user: User = Depends(current_user)) -> dict:
    s, reply = agent.nueva_sesion(user, body.consentimiento)
    return await _con_audio(reply.to_dict(s), False)


@app.post("/api/v1/agent/chat")
async def chat(body: Mensaje, user: User = Depends(current_user)) -> dict:
    s = _sesion(body.sesion_id, user)
    reply = await run_in_threadpool(agent.turno, s, body.mensaje, user, False)
    return await _con_audio(reply.to_dict(s), body.voz)


@app.post("/api/v1/agent/voz")
async def turno_voz(sesion_id: str = Form(...), audio: UploadFile = File(...), user: User = Depends(current_user)) -> dict:
    s = _sesion(sesion_id, user)
    data = await audio.read(settings.max_audio_bytes + 1)
    if len(data) > settings.max_audio_bytes:
        raise HTTPException(status_code=413, detail="El audio es demasiado largo (máximo 60 segundos)")
    if not data:
        raise HTTPException(status_code=400, detail="No se recibió audio")
    try:
        texto = await run_in_threadpool(voice.transcribe, data)
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=f"{exc}. Usa el modo escrito.") from exc
    finally:
        del data  # el audio no se conserva
    if not texto:
        raise HTTPException(status_code=422, detail="No se entendió el audio. Habla un poco más cerca del micrófono "
                                                    "o escribe tu mensaje")
    reply = await run_in_threadpool(agent.turno, s, texto, user, True)
    out = reply.to_dict(s)
    out["transcripcion"] = texto
    return await _con_audio(out, True)


@app.post("/api/v1/agent/borradores/{borrador_id}/confirmar")
async def confirmar(borrador_id: str, body: Confirmacion, user: User = Depends(current_user)) -> dict:
    s = _sesion(body.sesion_id, user)
    cambios = body.model_dump(exclude_none=True, exclude={"sesion_id"})
    reply = await run_in_threadpool(agent.confirmar, s, borrador_id, user, cambios)
    return reply.to_dict(s)


@app.post("/api/v1/agent/sesiones/{sesion_id}/fin")
async def terminar(sesion_id: str, user: User = Depends(current_user)) -> dict:
    _sesion(sesion_id, user)
    try:
        return await run_in_threadpool(agent.terminar, sesion_id, user)
    except HelpdeskError as exc:
        raise HTTPException(status_code=502, detail=f"No se pudieron registrar los metadatos: {exc}") from exc


# ---------------------------------------------------------------------- agente de IA del Data Lake
class TurnoPrevio(BaseModel):
    pregunta: str = Field(max_length=500)
    sql: str | None = Field(default=None, max_length=8000)


class PreguntaLago(BaseModel):
    pregunta: str = Field(min_length=3, max_length=500)
    historial: list[TurnoPrevio] = Field(default_factory=list, max_length=8)


def _solo_admin(user: User) -> None:
    if user.rol != "ADMIN":
        raise HTTPException(status_code=403, detail="Solo los administradores pueden consultar el Data Lake")


@app.get("/api/v1/agent/lago/estado")
def lago_estado(user: User = Depends(current_user)) -> dict:
    _solo_admin(user)
    return agente_lago.estado()


@app.post("/api/v1/agent/lago/preguntar")
async def lago_preguntar(body: PreguntaLago, user: User = Depends(current_user)) -> dict:
    _solo_admin(user)
    historial = [t.model_dump() for t in body.historial]
    return await run_in_threadpool(agente_lago.preguntar, body.pregunta.strip(), historial, user.email or user.id)
