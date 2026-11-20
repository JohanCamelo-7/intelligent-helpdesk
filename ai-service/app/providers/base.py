"""Capa de abstracción de proveedores de IA.

El resto del servicio solo conoce ModelProvider; cambiar de proveedor o modelo
(reglas locales, API compatible con OpenAI u Ollama) es un cambio de configuración.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import asdict, dataclass, field


@dataclass
class Diagnosis:
    origen: str                       # TEXTO | IMAGEN
    categoria_codigo: str | None
    tipo_falla_codigo: str | None
    confianza: float
    concluyente: bool
    descripcion: str = ""
    causa_probable: str | None = None
    tipo_equipo: str | None = None
    evidencias: list[str] = field(default_factory=list)
    recomendacion: str | None = None
    especialidad_sugerida: str | None = None
    adjunto_id: str | None = None
    latencia_ms: int = 0

    def to_dict(self) -> dict:
        return asdict(self)


class ModelProvider(ABC):
    name: str = "base"
    model: str = "n/a"
    supports_vision: bool = False

    @abstractmethod
    def classify_text(self, descripcion: str, contexto: dict) -> Diagnosis:
        """Clasifica la descripción escrita del problema."""

    @abstractmethod
    def analyze_image(self, image_jpeg: bytes, descripcion: str, contexto: dict) -> Diagnosis:
        """Analiza una imagen (ya saneada, en JPEG) y propone una posible falla."""
