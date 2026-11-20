"""Voz a texto (faster-whisper) y texto a voz (Piper), ejecutados localmente.

Los modelos se descargan la primera vez a MODELS_DIR (volumen Docker). El audio del
usuario se procesa en memoria y se descarta: no se guarda en disco ni en el Data Lake.
"""
from __future__ import annotations

import base64
import io
import json
import logging
import threading
import urllib.request
import wave
from pathlib import Path

from .config import settings

log = logging.getLogger(__name__)
PIPER_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0"


class ModeloCargando(RuntimeError):
    """El modelo todavía se está descargando o cargando en segundo plano."""


class VoiceEngine:
    def __init__(self) -> None:
        self._stt = None
        self._tts = None
        # Un candado por modelo: la descarga de Whisper no debe bloquear la síntesis de voz (ni al revés).
        self._stt_lock = threading.Lock()
        self._tts_lock = threading.Lock()
        self.stt_error: str | None = None
        self.tts_error: str | None = None

    # ---------------- Voz a texto ----------------
    def _load_stt(self, esperar: bool = True):
        if self._stt is not None or not settings.stt_enabled:
            return self._stt
        if not self._stt_lock.acquire(blocking=esperar):
            raise ModeloCargando("El modelo de voz se está descargando (solo pasa la primera vez, unos minutos). "
                                 "Intenta de nuevo en un momento o escribe tu mensaje")
        try:
            if self._stt is None:
                try:
                    from faster_whisper import WhisperModel
                    self._stt = WhisperModel(settings.stt_model, device="cpu", compute_type="int8",
                                             download_root=str(Path(settings.models_dir) / "whisper"))
                    self.stt_error = None
                except Exception as exc:  # noqa: BLE001
                    self.stt_error = str(exc)
                    log.warning("Voz a texto no disponible: %s", exc)
        finally:
            self._stt_lock.release()
        return self._stt

    def transcribe(self, audio: bytes) -> str:
        model = self._load_stt(esperar=False)
        if model is None:
            raise RuntimeError("El reconocimiento de voz no está disponible en el servidor")
        try:
            segments, _info = model.transcribe(io.BytesIO(audio), language="es", vad_filter=True, beam_size=1)
            return " ".join(s.text.strip() for s in segments).strip()
        except Exception as exc:  # noqa: BLE001 - audio corrupto, códec no soportado, etc.
            log.exception("Error al transcribir el audio")
            raise RuntimeError(f"No se pudo transcribir el audio ({type(exc).__name__})") from exc

    # ---------------- Texto a voz ----------------
    def _voice_paths(self) -> tuple[Path, Path]:
        name = settings.tts_voice                      # p. ej. es_MX-ald-medium
        lang, speaker, quality = name.split("-", 2)
        family = lang.split("_")[0]
        folder = Path(settings.models_dir) / "piper"
        folder.mkdir(parents=True, exist_ok=True)
        onnx, cfg = folder / f"{name}.onnx", folder / f"{name}.onnx.json"
        for path in (onnx, cfg):
            if not path.exists():
                url = f"{PIPER_BASE}/{family}/{lang}/{speaker}/{quality}/{path.name}"
                log.info("Descargando voz %s", url)
                tmp = path.with_suffix(path.suffix + ".part")
                urllib.request.urlretrieve(url, tmp)  # noqa: S310 - URL fija de HuggingFace
                tmp.rename(path)
        self._normalizar_config(cfg)
        return onnx, cfg

    @staticmethod
    def _normalizar_config(cfg: Path) -> None:
        """Algunas voces publicadas traen "phoneme_type": "PhonemeType.ESPEAK" (nombre del enum en lugar de su
        valor), que piper-tts 1.3 rechaza. Se corrige a "espeak"/"text" una sola vez sobre el archivo descargado."""
        data = json.loads(cfg.read_text(encoding="utf-8"))
        valor = str(data.get("phoneme_type", "espeak"))
        if valor.startswith("PhonemeType."):
            data["phoneme_type"] = valor.split(".", 1)[1].lower()
            cfg.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            log.info("Configuración de voz corregida: phoneme_type=%s", data["phoneme_type"])

    def _load_tts(self, esperar: bool = True):
        if self._tts is not None or not settings.tts_enabled:
            return self._tts
        if not self._tts_lock.acquire(blocking=esperar):
            return None  # se está cargando: esta vez responde la voz del navegador
        try:
            if self._tts is None:
                try:
                    from piper import PiperVoice
                    onnx, cfg = self._voice_paths()
                    self._tts = PiperVoice.load(onnx, config_path=cfg)
                    self.tts_error = None
                except Exception as exc:  # noqa: BLE001
                    self.tts_error = str(exc)
                    log.warning("Texto a voz no disponible: %s", exc)
        finally:
            self._tts_lock.release()
        return self._tts

    def synthesize_b64(self, text: str) -> str | None:
        """WAV en base64, o None si no hay TTS (el navegador usa entonces su propia voz)."""
        voice = self._load_tts(esperar=False)
        if voice is None or not text:
            return None
        try:
            buf = io.BytesIO()
            with wave.open(buf, "wb") as wav:
                voice.synthesize_wav(text[:800], wav)
            return base64.b64encode(buf.getvalue()).decode()
        except Exception:  # noqa: BLE001 - nunca tumbar el turno por la voz: responde la del navegador
            log.exception("Error al sintetizar la voz")
            return None

    def status(self) -> dict:
        return {"stt_habilitado": settings.stt_enabled, "stt_cargado": self._stt is not None, "stt_error": self.stt_error,
                "tts_habilitado": settings.tts_enabled, "tts_cargado": self._tts is not None, "tts_error": self.tts_error}

    def warm_up(self) -> None:
        """Precarga en segundo plano para que el primer turno de voz no espere la descarga."""
        threading.Thread(target=self._load_tts, daemon=True).start()   # voz pequeña: lista en segundos
        threading.Thread(target=self._load_stt, daemon=True).start()   # Whisper: puede tardar minutos la 1.ª vez


voice = VoiceEngine()
