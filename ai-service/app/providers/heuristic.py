"""Proveedor por reglas: funciona sin GPU, sin internet y sin costo.

Es deliberadamente conservador: su confianza nunca supera 0,60, de modo que con el
umbral por defecto (0,75) ningún ticket se asigna automáticamente solo por reglas;
todos pasan por revisión del ADMIN. No analiza imágenes.
"""
from __future__ import annotations

import time

from ..taxonomy import ESPECIALIDAD_POR_CATEGORIA, KEYWORDS, SAFE_CHECKS, normalize, taxonomy
from .base import Diagnosis, ModelProvider

MAX_CONFIDENCE = 0.60


class HeuristicProvider(ModelProvider):
    name = "heuristic"
    model = "reglas-palabras-clave-v1"
    supports_vision = False

    def classify_text(self, descripcion: str, contexto: dict) -> Diagnosis:
        start = time.perf_counter()
        text = normalize(descripcion)
        by_code = taxonomy.by_code
        best_code, best_score, hits_best = None, 0.0, []
        for code, words in KEYWORDS.items():
            if code not in by_code:
                continue
            hits = [w for w in words if w in text]
            # Las frases de varias palabras son más específicas que una palabra suelta.
            score = sum(2.0 if " " in w else 1.0 for w in hits)
            if score > best_score:
                best_code, best_score, hits_best = code, score, hits
        latency = int((time.perf_counter() - start) * 1000)
        if best_code is None:
            return Diagnosis(origen="TEXTO", categoria_codigo=None, tipo_falla_codigo=None, confianza=0.2,
                             concluyente=False, descripcion="No se reconocieron palabras clave suficientes para clasificar.",
                             latencia_ms=latency)
        ft = by_code[best_code]
        conf = min(MAX_CONFIDENCE, 0.35 + 0.08 * best_score)
        return Diagnosis(
            origen="TEXTO", categoria_codigo=ft.categoria, tipo_falla_codigo=ft.codigo, confianza=round(conf, 3),
            concluyente=True,
            descripcion=f"Clasificación por reglas: posible «{ft.nombre}».",
            evidencias=[f"Palabra clave: «{h}»" for h in hits_best[:5]],
            recomendacion=SAFE_CHECKS.get(ft.categoria, [None])[0],
            especialidad_sugerida=ESPECIALIDAD_POR_CATEGORIA.get(ft.categoria),
            latencia_ms=latency,
        )

    def analyze_image(self, image_jpeg: bytes, descripcion: str, contexto: dict) -> Diagnosis:
        return Diagnosis(origen="IMAGEN", categoria_codigo=None, tipo_falla_codigo=None, confianza=0.0,
                         concluyente=False,
                         descripcion="El análisis visual no está disponible en modo por reglas. Configura un modelo multimodal.")
