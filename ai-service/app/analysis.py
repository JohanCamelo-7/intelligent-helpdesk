"""Orquestación del diagnóstico: texto + imágenes -> resultado combinado y explicable."""
from __future__ import annotations

import io
import logging

import boto3
from botocore.config import Config
from PIL import Image, ImageOps

from .config import settings
from .providers import Diagnosis, ModelProvider

log = logging.getLogger(__name__)

MAX_SIDE = 1600
Image.MAX_IMAGE_PIXELS = 40_000_000  # mismo límite que helpdesk-api (bombas de descompresión)


def s3_client():
    # SeaweedFS: estilo de ruta y checksums solo cuando se requieren (compatibilidad S3).
    return boto3.client(
        "s3", endpoint_url=settings.s3_endpoint, region_name=settings.s3_region,
        aws_access_key_id=settings.s3_access_key, aws_secret_access_key=settings.s3_secret_key,
        config=Config(s3={"addressing_style": "path"}, request_checksum_calculation="when_required",
                      response_checksum_validation="when_required", retries={"max_attempts": 3}),
    )


def sanitize_image(data: bytes) -> bytes:
    """Vuelve a codificar la imagen: elimina EXIF/GPS, neutraliza archivos políglotas y limita el tamaño."""
    with Image.open(io.BytesIO(data)) as probe:
        probe.verify()
    with Image.open(io.BytesIO(data)) as img:
        img = ImageOps.exif_transpose(img)
        img = img.convert("RGB")
        img.thumbnail((MAX_SIDE, MAX_SIDE))
        out = io.BytesIO()
        img.save(out, format="JPEG", quality=85, optimize=True)
        return out.getvalue()


def combine(text: Diagnosis, images: list[Diagnosis]) -> dict:
    """Regla de combinación (documentada en docs/arquitectura/ia.md):

    - Solo texto: se usa el resultado del texto.
    - Texto e imagen coinciden en categoría: confianza = min(0,98; máx + 0,10) — la coincidencia de dos fuentes refuerza.
    - Discrepan: gana la fuente de mayor confianza, penalizada ×0,7 (el desacuerdo es señal de duda).
    """
    concl = [d for d in images if d.concluyente and d.categoria_codigo]
    if not concl or not text.concluyente:
        candidates = [d for d in [text, *concl] if d.concluyente and d.categoria_codigo]
        if not candidates:
            return {"categoria_codigo": None, "tipo_falla_codigo": None, "confianza": 0.0,
                    "especialidad_sugerida": None, "regla": "SIN_RESULTADO_CONCLUYENTE"}
        best = max(candidates, key=lambda d: d.confianza)
        return {"categoria_codigo": best.categoria_codigo, "tipo_falla_codigo": best.tipo_falla_codigo,
                "confianza": round(best.confianza, 3), "especialidad_sugerida": best.especialidad_sugerida,
                "regla": "FUENTE_UNICA_" + best.origen}
    img = max(concl, key=lambda d: d.confianza)
    if img.categoria_codigo == text.categoria_codigo:
        tipo_src = img if img.confianza >= text.confianza else text
        return {"categoria_codigo": text.categoria_codigo, "tipo_falla_codigo": tipo_src.tipo_falla_codigo,
                "confianza": round(min(0.98, max(img.confianza, text.confianza) + 0.10), 3),
                "especialidad_sugerida": tipo_src.especialidad_sugerida, "regla": "TEXTO_E_IMAGEN_COINCIDEN"}
    best = img if img.confianza > text.confianza else text
    return {"categoria_codigo": best.categoria_codigo, "tipo_falla_codigo": best.tipo_falla_codigo,
            "confianza": round(best.confianza * 0.7, 3), "especialidad_sugerida": best.especialidad_sugerida,
            "regla": "TEXTO_E_IMAGEN_DISCREPAN"}


def analyze(provider: ModelProvider, contexto: dict, adjuntos: list[dict], s3=None) -> tuple[Diagnosis, list[Diagnosis]]:
    ctx = {"equipo_tipo": contexto.get("equipoTipo"), "sede": contexto.get("sedeCodigo")}
    descripcion = contexto.get("descripcion") or ""
    text = provider.classify_text(descripcion, ctx)
    results: list[Diagnosis] = []
    if adjuntos and provider.supports_vision:
        s3 = s3 or s3_client()
        for a in adjuntos[:5]:
            try:
                raw = s3.get_object(Bucket=settings.attachments_bucket, Key=a["objectKey"])["Body"].read()
                clean = sanitize_image(raw)
                d = provider.analyze_image(clean, descripcion, ctx)
            except Exception as exc:  # noqa: BLE001
                log.warning("No se pudo analizar la imagen %s: %s", a.get("id"), exc)
                d = Diagnosis(origen="IMAGEN", categoria_codigo=None, tipo_falla_codigo=None, confianza=0.0,
                              concluyente=False, descripcion="No se pudo procesar la imagen.")
            d.adjunto_id = a.get("id")
            results.append(d)
    elif adjuntos:
        for a in adjuntos[:5]:
            d = provider.analyze_image(b"", descripcion, ctx)
            d.adjunto_id = a.get("id")
            results.append(d)
    return text, results
