"""Configuración del servicio de IA, leída de variables de entorno."""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).strip().lower() in {"1", "true", "yes", "si", "sí"}


@dataclass(frozen=True)
class Settings:
    rabbitmq_url: str = field(default_factory=lambda: os.getenv("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/"))
    helpdesk_url: str = field(default_factory=lambda: os.getenv("HELPDESK_URL", "http://localhost:8080"))
    service_key: str = field(default_factory=lambda: os.getenv("SERVICE_KEY", "cambia-clave-interna-servicios"))
    jwt_secret: str = field(default_factory=lambda: os.getenv("JWT_SECRET", "cambia-este-secreto-jwt-de-al-menos-32-caracteres"))
    s3_endpoint: str = field(default_factory=lambda: os.getenv("S3_ENDPOINT", "http://localhost:8333"))
    s3_region: str = field(default_factory=lambda: os.getenv("S3_REGION", "us-east-1"))
    s3_access_key: str = field(default_factory=lambda: os.getenv("S3_ACCESS_KEY", "ai"))
    s3_secret_key: str = field(default_factory=lambda: os.getenv("S3_SECRET_KEY", "cambia-s3-ai"))
    attachments_bucket: str = "helpdesk-attachments"
    llm_provider: str = field(default_factory=lambda: os.getenv("LLM_PROVIDER", "heuristic").strip().lower())
    llm_base_url: str = field(default_factory=lambda: os.getenv("LLM_BASE_URL", "https://api.openai.com/v1"))
    llm_api_key: str = field(default_factory=lambda: os.getenv("LLM_API_KEY", ""))
    llm_model: str = field(default_factory=lambda: os.getenv("LLM_MODEL", "gpt-4o-mini"))
    vision_model: str = field(default_factory=lambda: os.getenv("VISION_MODEL", ""))
    llm_timeout: float = field(default_factory=lambda: float(os.getenv("LLM_TIMEOUT", "60")))
    stt_enabled: bool = field(default_factory=lambda: _bool("STT_ENABLED", True))
    stt_model: str = field(default_factory=lambda: os.getenv("STT_MODEL", "small"))
    tts_enabled: bool = field(default_factory=lambda: _bool("TTS_ENABLED", True))
    tts_voice: str = field(default_factory=lambda: os.getenv("TTS_VOICE", "es_MX-ald-medium"))
    models_dir: str = field(default_factory=lambda: os.getenv("MODELS_DIR", "/models"))
    consume_events: bool = field(default_factory=lambda: _bool("CONSUME_EVENTS", True))
    # Agente de IA del Data Lake
    trino_url: str = field(default_factory=lambda: os.getenv("TRINO_HTTP_URL", "http://trino:8080"))
    trino_user: str = field(default_factory=lambda: os.getenv("TRINO_USER", "analytics"))   # solo lectura en gold y ops
    lake_agent_mode: str = field(default_factory=lambda: os.getenv("LAKE_AGENT_MODE", "auto").strip().lower())  # auto | plantillas
    lake_llm_model: str = field(default_factory=lambda: os.getenv("LAKE_LLM_MODEL", ""))   # vacío = LLM_MODEL
    lake_timeout: float = field(default_factory=lambda: float(os.getenv("LAKE_QUERY_TIMEOUT", "30")))
    lake_max_filas: int = field(default_factory=lambda: int(os.getenv("LAKE_MAX_ROWS", "500")))
    lake_max_intentos: int = field(default_factory=lambda: int(os.getenv("LAKE_MAX_ATTEMPTS", "3")))
    session_ttl_seconds: int = 30 * 60
    max_audio_bytes: int = 8 * 1024 * 1024
    max_audio_seconds: int = 60

    @property
    def effective_vision_model(self) -> str:
        return self.vision_model or self.llm_model


settings = Settings()
