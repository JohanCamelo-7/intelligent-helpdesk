"""Taxonomía de fallas: se carga del catálogo de helpdesk-api (/internal/catalogos).

Incluye las palabras clave del clasificador por reglas y la lista blanca de
comprobaciones SEGURAS que el agente puede recomendar.
"""
from __future__ import annotations

import logging
import unicodedata
from dataclasses import dataclass

import httpx

from .config import settings

log = logging.getLogger(__name__)


def normalize(text: str) -> str:
    """Minúsculas y sin tildes, para comparar palabras clave."""
    t = unicodedata.normalize("NFD", text.lower())
    return "".join(c for c in t if unicodedata.category(c) != "Mn")


@dataclass(frozen=True)
class FaultType:
    codigo: str
    nombre: str
    categoria: str


# Catálogo por defecto (igual a V2__datos_referencia.sql). Se reemplaza por el real al iniciar.
DEFAULT_TYPES: list[FaultType] = [
    FaultType("HW_NO_ENCIENDE", "El equipo no enciende", "HARDWARE"),
    FaultType("HW_PANTALLA", "Falla de pantalla o monitor", "HARDWARE"),
    FaultType("HW_DANO_FISICO", "Daño físico del equipo o conector", "HARDWARE"),
    FaultType("HW_RENDIMIENTO", "Lentitud o sobrecalentamiento", "HARDWARE"),
    FaultType("SW_ERROR_SISTEMA", "Error del sistema operativo", "SOFTWARE"),
    FaultType("SW_APLICACION", "Falla de una aplicación", "SOFTWARE"),
    FaultType("SW_INSTALACION", "Instalación o actualización de software", "SOFTWARE"),
    FaultType("RED_SIN_CONEXION", "Sin conexión a la red o internet", "REDES"),
    FaultType("RED_WIFI", "Problemas de wifi", "REDES"),
    FaultType("RED_CABLEADO", "Cable o punto de red dañado", "REDES"),
    FaultType("IMP_ATASCO", "Atasco de papel", "IMPRESION"),
    FaultType("IMP_ERROR", "Error o mensaje en la impresora", "IMPRESION"),
    FaultType("IMP_CONEXION", "La impresora no se conecta", "IMPRESION"),
    FaultType("PER_TECLADO_MOUSE", "Teclado o mouse", "PERIFERICOS"),
    FaultType("PER_AUDIO_VIDEO", "Audio, cámara o proyector", "PERIFERICOS"),
    FaultType("ACC_CONTRASENA", "Contraseña o bloqueo de cuenta", "ACCESOS"),
    FaultType("ACC_PERMISOS", "Permisos de acceso", "ACCESOS"),
    FaultType("ACC_CORREO", "Correo electrónico", "ACCESOS"),
]

# Palabras clave del modo por reglas (sin tildes).
KEYWORDS: dict[str, list[str]] = {
    "HW_NO_ENCIENDE": ["no enciende", "no prende", "no arranca", "no da imagen ni luz", "apagado y no", "no vuelve a encender"],
    "HW_PANTALLA": ["pantalla", "monitor", "parpadea", "lineas", "pixeles", "pantalla negra", "se ve oscura"],
    "HW_DANO_FISICO": ["roto", "rota", "quebr", "golpe", "se cayo", "bisagra", "doblado", "conector", "puerto usb", "derram"],
    "HW_RENDIMIENTO": ["lento", "lenta", "se calienta", "ventilador", "sobrecalent", "se congela", "tarda mucho"],
    "SW_ERROR_SISTEMA": ["pantalla azul", "windows", "error del sistema", "bsod", "no carga el escritorio", "reinicia solo"],
    "SW_APLICACION": ["programa", "aplicacion", "se cierra", "excel", "word", "software", "sistema de", "no abre"],
    "SW_INSTALACION": ["instalar", "instalacion", "actualizacion", "actualizar", "licencia"],
    "RED_SIN_CONEXION": ["sin internet", "no tengo internet", "sin conexion", "no hay red", "no conecta a la red", "caida de red"],
    "RED_WIFI": ["wifi", "wi-fi", "inalambric", "red inalambrica", "se desconecta"],
    "RED_CABLEADO": ["cable de red", "punto de red", "cable", "ethernet", "patch"],
    "IMP_ATASCO": ["atasc", "papel", "arruga", "atora", "papel atascado", "hojas"],
    # "impresora" sola no indica el tipo: es común a los tres tipos de impresión.
    "IMP_ERROR": ["toner", "tinta", "luz naranja", "error en la impresora", "mensaje de error en la impresora", "codigo de error"],
    "IMP_CONEXION": ["impresora desconectada", "no encuentro la impresora", "impresora no aparece", "cola de impresion"],
    "PER_TECLADO_MOUSE": ["teclado", "mouse", "raton", "teclas"],
    "PER_AUDIO_VIDEO": ["proyector", "camara", "microfono", "parlante", "audio", "sonido", "videollamada", "hdmi"],
    "ACC_CONTRASENA": ["contrasena", "clave", "bloquead", "olvide", "password", "iniciar sesion"],
    "ACC_PERMISOS": ["permiso", "acceso a", "carpeta compartida", "no tengo acceso", "autoriz"],
    "ACC_CORREO": ["correo", "outlook", "email", "e-mail", "buzon", "no me llegan"],
}

# Lista blanca de comprobaciones seguras por categoría (el agente nunca sugiere otras).
SAFE_CHECKS: dict[str, list[str]] = {
    "HARDWARE": ["Verifica que el cable de energía esté bien conectado al equipo y al tomacorriente.",
                 "Si es un portátil, conecta el cargador y espera unos minutos antes de encenderlo.",
                 "No abras el equipo ni intentes repararlo internamente."],
    "SOFTWARE": ["Guarda tu trabajo y reinicia el equipo.",
                 "Anota el mensaje de error exacto o toma una foto de la pantalla."],
    "REDES": ["Revisa si otros compañeros tienen el mismo problema.",
              "Verifica que el cable de red esté conectado o que el wifi esté activado.",
              "Reinicia el equipo para renovar la conexión."],
    "IMPRESION": ["Verifica que la impresora esté encendida y con papel.",
                  "Si hay papel atascado, apágala antes de retirarlo con cuidado y sin forzar."],
    "PERIFERICOS": ["Desconecta y vuelve a conectar el dispositivo en otro puerto.",
                    "Si es inalámbrico, revisa las pilas o la carga."],
    "ACCESOS": ["Verifica que el teclado no tenga activadas las mayúsculas.",
                "No compartas tu contraseña con nadie, ni siquiera con soporte."],
}

ESPECIALIDAD_POR_CATEGORIA = {
    "HARDWARE": "Soporte de hardware", "SOFTWARE": "Soporte de software", "REDES": "Redes",
    "IMPRESION": "Impresoras", "PERIFERICOS": "Periféricos", "ACCESOS": "Cuentas y accesos",
}


class Taxonomy:
    def __init__(self) -> None:
        self.types: list[FaultType] = list(DEFAULT_TYPES)
        self.equipos: list[dict] = []
        self.sedes: list[dict] = []

    @property
    def by_code(self) -> dict[str, FaultType]:
        return {t.codigo: t for t in self.types}

    @property
    def categories(self) -> list[str]:
        return sorted({t.categoria for t in self.types})

    def load(self) -> None:
        try:
            resp = httpx.get(f"{settings.helpdesk_url}/internal/catalogos",
                             headers={"X-Service-Key": settings.service_key}, timeout=10)
            resp.raise_for_status()
            data = resp.json()
            self.types = [FaultType(t["codigo"], t["nombre"], t["categoriaCodigo"]) for t in data.get("tiposFalla", [])] or self.types
            self.equipos = data.get("equipos", [])
            self.sedes = data.get("sedes", [])
            log.info("Catálogo cargado: %d tipos de falla, %d equipos", len(self.types), len(self.equipos))
        except Exception as exc:  # el servicio sigue con el catálogo por defecto
            log.warning("No se pudo cargar el catálogo de helpdesk-api (%s); se usa el catálogo por defecto", exc)

    def prompt_list(self) -> str:
        return "\n".join(f"- {t.codigo} ({t.categoria}): {t.nombre}" for t in self.types)


taxonomy = Taxonomy()
