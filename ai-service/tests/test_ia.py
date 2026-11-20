import io
import json

import httpx
import pytest
from PIL import Image

from app.analysis import combine, sanitize_image
from app.events import NAMESPACE, process_event
from app.providers.base import Diagnosis
from app.providers.heuristic import MAX_CONFIDENCE, HeuristicProvider
from app.providers.openai_compat import OpenAICompatibleProvider


@pytest.mark.parametrize("texto,esperado", [
    ("La impresora tiene papel atascado y arruga las hojas", "IMP_ATASCO"),
    ("No tengo internet en el computador, aparece sin conexión", "RED_SIN_CONEXION"),
    ("Mi cuenta quedó bloqueada y olvidé la contraseña", "ACC_CONTRASENA"),
    ("Aparece una pantalla azul de Windows y se reinicia solo", "SW_ERROR_SISTEMA"),
    ("El wifi de la sala se desconecta a cada rato", "RED_WIFI"),
])
def test_heuristica_clasifica_y_nunca_supera_el_tope(texto, esperado):
    d = HeuristicProvider().classify_text(texto, {})
    assert d.tipo_falla_codigo == esperado
    assert d.concluyente
    assert d.confianza <= MAX_CONFIDENCE  # siempre por debajo del umbral 0.75 -> revisión humana


def test_heuristica_sin_palabras_clave_no_es_concluyente():
    d = HeuristicProvider().classify_text("Necesito ayuda con algo raro que pasa a veces", {})
    assert not d.concluyente and d.categoria_codigo is None


def _d(origen, cat, tipo, conf, concl=True):
    return Diagnosis(origen=origen, categoria_codigo=cat, tipo_falla_codigo=tipo, confianza=conf, concluyente=concl)


def test_combinacion_coincidente_refuerza():
    c = combine(_d("TEXTO", "IMPRESION", "IMP_ERROR", 0.7), [_d("IMAGEN", "IMPRESION", "IMP_ATASCO", 0.8)])
    assert c["regla"] == "TEXTO_E_IMAGEN_COINCIDEN"
    assert c["tipo_falla_codigo"] == "IMP_ATASCO"   # el tipo lo aporta la fuente más confiable
    assert c["confianza"] == pytest.approx(0.9)


def test_combinacion_discrepante_penaliza():
    c = combine(_d("TEXTO", "REDES", "RED_WIFI", 0.8), [_d("IMAGEN", "HARDWARE", "HW_PANTALLA", 0.6)])
    assert c["regla"] == "TEXTO_E_IMAGEN_DISCREPAN"
    assert c["categoria_codigo"] == "REDES"
    assert c["confianza"] == pytest.approx(0.56)


def test_combinacion_sin_resultados():
    c = combine(_d("TEXTO", None, None, 0.1, concl=False), [])
    assert c["confianza"] == 0 and c["categoria_codigo"] is None


def test_saneamiento_elimina_exif_y_convierte_a_jpeg():
    img = Image.new("RGB", (3000, 2000), "red")
    exif = Image.Exif()
    exif[0x010F] = "CamaraSecreta"  # Make
    buf = io.BytesIO()
    img.save(buf, format="PNG", exif=exif)
    out = sanitize_image(buf.getvalue())
    with Image.open(io.BytesIO(out)) as res:
        assert res.format == "JPEG"
        assert max(res.size) <= 1600
        assert not res.getexif()


def test_saneamiento_rechaza_no_imagen():
    with pytest.raises(Exception):
        sanitize_image(b"esto no es una imagen")


def _mock_llm(content: str):
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["messages"][0]["role"] == "system"
        assert "<<<DATOS>>>" in str(body["messages"][1]["content"])  # el texto va delimitado como datos
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_proveedor_compatible_valida_codigos_y_limita_confianza():
    p = OpenAICompatibleProvider("openai", "http://llm", "k", "m", "", client=_mock_llm(
        '{"categoria_codigo":"HARDWARE","tipo_falla_codigo":"IMP_ATASCO","confianza":0.99,"concluyente":true,'
        '"descripcion":"Papel atascado","evidencias":["papel"]}'))
    d = p.classify_text("se atasca el papel", {})
    assert d.tipo_falla_codigo == "IMP_ATASCO"
    assert d.categoria_codigo == "IMPRESION"       # se corrige para que sea coherente con el tipo
    assert d.confianza == 0.9                      # tope para solo texto


def test_proveedor_compatible_descarta_codigos_inventados():
    p = OpenAICompatibleProvider("openai", "http://llm", "k", "m", "", client=_mock_llm(
        'Claro: {"tipo_falla_codigo":"INVENTADO","categoria_codigo":"MAGIA","confianza":0.95,"concluyente":true}'))
    d = p.classify_text("algo", {})
    assert d.tipo_falla_codigo is None and d.categoria_codigo is None and not d.concluyente


def test_proveedor_compatible_falla_termina_en_revision():
    def boom(request):
        return httpx.Response(500, json={"error": "x"})
    p = OpenAICompatibleProvider("openai", "http://llm", "k", "m", "", client=httpx.Client(transport=httpx.MockTransport(boom)))
    d = p.classify_text("algo", {})
    assert not d.concluyente and d.confianza == 0


def test_evento_ticket_creado_produce_resultado_con_id_determinista(fake_helpdesk):
    import uuid
    env = {"event_id": "8b0f4f9e-7d1c-4c9e-9a5e-000000000001", "event_type": "TicketCreated",
           "correlation_id": "c1", "payload": {"ticket_id": "t1", "es_demo": False}}
    r1 = process_event(env, fake_helpdesk, HeuristicProvider())
    r2 = process_event(env, fake_helpdesk, HeuristicProvider())
    assert r1["event_type"] == "AiDiagnosisCompleted"
    assert r1["event_id"] == r2["event_id"] == str(uuid.uuid5(NAMESPACE, env["event_id"]))
    assert r1["payload"]["combinado"]["tipo_falla_codigo"] == "IMP_ATASCO"
    assert r1["payload"]["trigger"] == "TICKET_CREATED"


def test_eventos_de_demo_y_adjuntos_de_creacion_se_ignoran(fake_helpdesk):
    demo = {"event_id": "e", "event_type": "TicketCreated", "payload": {"ticket_id": "t", "es_demo": True}}
    adj = {"event_id": "e2", "event_type": "AttachmentUploaded", "payload": {"ticket_id": "t", "durante_creacion": True}}
    assert process_event(demo, fake_helpdesk, HeuristicProvider()) is None
    assert process_event(adj, fake_helpdesk, HeuristicProvider()) is None


def test_voz_no_se_bloquea_mientras_carga_el_modelo(monkeypatch):
    """Mientras Whisper se descarga, un turno de voz responde de inmediato y la síntesis no espera."""
    import dataclasses
    import time
    from app import voice as voice_mod
    from app.voice import ModeloCargando, VoiceEngine
    monkeypatch.setattr(voice_mod, "settings",
                        dataclasses.replace(voice_mod.settings, stt_enabled=True, tts_enabled=True))
    v = VoiceEngine()
    v._stt_lock.acquire()          # simula la descarga en curso
    v._tts_lock.acquire()
    try:
        t0 = time.monotonic()
        try:
            v.transcribe(b"audio")
            raise AssertionError("debió avisar que el modelo se está cargando")
        except ModeloCargando as exc:
            assert "descargando" in str(exc)
        assert v.synthesize_b64("hola") is None
        assert time.monotonic() - t0 < 0.5
    finally:
        v._stt_lock.release()
        v._tts_lock.release()


def test_errores_de_voz_no_tumban_el_turno(monkeypatch):
    """Una falla al sintetizar devuelve None (voz del navegador) y una al transcribir da un error claro."""
    import dataclasses
    from app import voice as voice_mod
    from app.voice import VoiceEngine
    monkeypatch.setattr(voice_mod, "settings",
                        dataclasses.replace(voice_mod.settings, stt_enabled=True, tts_enabled=True))
    v = VoiceEngine()

    class Roto:
        def synthesize_wav(self, *_a):
            raise ValueError("fallo interno de piper")

        def transcribe(self, *_a, **_k):
            raise ValueError("códec no soportado")

    v._tts = Roto()
    v._stt = Roto()
    assert v.synthesize_b64("hola") is None
    try:
        v.transcribe(b"audio")
        raise AssertionError("debió lanzar RuntimeError")
    except RuntimeError as exc:
        assert "No se pudo transcribir" in str(exc)
