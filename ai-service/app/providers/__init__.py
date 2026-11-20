"""Selección del proveedor según LLM_PROVIDER."""
from __future__ import annotations

from functools import lru_cache

from ..config import settings
from .base import Diagnosis, ModelProvider
from .heuristic import HeuristicProvider
from .openai_compat import OpenAICompatibleProvider

__all__ = ["Diagnosis", "ModelProvider", "get_provider"]


@lru_cache(maxsize=1)
def get_provider() -> ModelProvider:
    p = settings.llm_provider
    if p == "openai":
        return OpenAICompatibleProvider("openai", settings.llm_base_url, settings.llm_api_key,
                                        settings.llm_model, settings.effective_vision_model)
    if p == "ollama":
        base = settings.llm_base_url if "ollama" in settings.llm_base_url or "11434" in settings.llm_base_url \
            else "http://ollama:11434/v1"
        return OpenAICompatibleProvider("ollama", base, "ollama", settings.llm_model, settings.effective_vision_model)
    return HeuristicProvider()
