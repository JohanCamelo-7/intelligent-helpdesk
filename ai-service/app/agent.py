"""Agente conversacional de soporte.

Diseño explicable: el FLUJO y las HERRAMIENTAS los controla este código; el modelo de
lenguaje solo interpreta la descripción (clasificación). Así el agente:
  - no puede inventar acciones ni afirmar reparaciones,
  - solo sugiere comprobaciones de una lista blanca segura,
  - crea un ticket únicamente tras una confirmación explícita del usuario,
  - usa el JWT del usuario en cada herramienta (respeta sus permisos).
"""
from __future__ import annotations

import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from .config import settings
from .helpdesk_client import HelpdeskClient, HelpdeskError
from .providers import get_provider
from .security import User
from .taxonomy import SAFE_CHECKS, normalize, taxonomy

TICKET_RE = re.compile(r"\bSOP[-\s]?(\d{1,6})\b", re.IGNORECASE)
EQUIPO_RE = re.compile(r"\b([A-Z]{2,4}-[A-Z]{3}-\d{2,3})\b", re.IGNORECASE)
MIS_TICKETS = ("mis tickets", "mis solicitudes", "estado de mis", "mis casos", "mis reportes")
REINICIAR = ("empezar de nuevo", "reiniciar", "cancelar reporte", "olvidalo", "nuevo reporte")
SIN_EQUIPO = ("ninguno", "no aplica", "no se", "ninguna", "no tengo", "no es un equipo", "cuenta", "correo")
AFIRMA = ("si", "confirmo", "crear", "crealo", "dale", "de acuerdo", "correcto", "listo")
NIEGA = ("no", "editar", "cambiar", "corregir")
ESTADOS = {"PENDIENTE": "pendiente", "EN_CURSO": "en curso", "FINALIZADA": "finalizado", "CANCELADA": "cancelado"}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def estimar_prioridad(impacto: str, bloquea: bool, categoria: str | None) -> str:
    """Réplica de PriorityRules (helpdesk-api) solo para mostrar la estimación en el borrador.
    La prioridad definitiva la calcula el backend."""
    puntaje = {"INDIVIDUAL": 1, "AREA": 2, "SEDE": 3}[impacto] + (1 if bloquea else 0)
    if categoria in ("REDES", "ACCESOS") and impacto != "INDIVIDUAL":
        puntaje += 1
    return ["BAJA", "MEDIA", "ALTA", "CRITICA"][max(1, min(4, puntaje)) - 1]


def contiene(texto: str, palabras: tuple[str, ...]) -> bool:
    t = " " + re.sub(r"[^a-z0-9 ]", " ", normalize(texto)) + " "
    t = re.sub(r"\s+", " ", t)
    return any(f" {p} " in t or (" " in p and p in t) for p in palabras)


@dataclass
class Session:
    id: str
    user_id: str
    consentimiento: bool
    inicio: str = field(default_factory=now_iso)
    last: float = field(default_factory=time.time)
    turnos: int = 0
    intencion: str | None = None
    uso_voz: bool = False
    etapa: str = "PROBLEMA"
    slots: dict = field(default_factory=dict)
    clasificacion: dict | None = None
    borrador: dict | None = None
    ticket_id: str | None = None
    ticket_numero: str | None = None
    catalogo: dict | None = None
    perfil: dict | None = None
    intentos_equipo: int = 0


@dataclass
class Reply:
    texto: str
    etapa: str
    borrador: dict | None = None
    ticket: dict | None = None
    sugerencias: list[str] = field(default_factory=list)

    def to_dict(self, sesion: Session) -> dict:
        return {"sesion_id": sesion.id, "respuesta": self.texto, "etapa": self.etapa, "borrador": self.borrador,
                "ticket": self.ticket, "sugerencias": self.sugerencias}


class Agent:
    def __init__(self, helpdesk: HelpdeskClient | None = None, provider=None) -> None:
        self.helpdesk = helpdesk or HelpdeskClient()
        self._provider = provider
        self.sessions: dict[str, Session] = {}
        self.lock = threading.Lock()

    @property
    def provider(self):
        return self._provider or get_provider()

    # ------------------------------------------------------------------ sesiones
    def nueva_sesion(self, user: User, consentimiento: bool) -> tuple[Session, Reply]:
        self._limpiar()
        s = Session(id=str(uuid.uuid4()), user_id=user.id, consentimiento=consentimiento)
        with self.lock:
            self.sessions[s.id] = s
        nombre = user.nombre.split(" ")[0] if user.nombre else ""
        return s, Reply(f"Hola {nombre}. Soy el asistente de soporte técnico. Cuéntame qué problema tienes, "
                        "o pregúntame por el estado de tus tickets (por ejemplo: «estado de SOP-000123»).",
                        s.etapa, sugerencias=["Mis tickets", "Mi impresora no imprime", "No tengo internet"])

    def obtener(self, sesion_id: str, user: User) -> Session:
        self._limpiar()
        s = self.sessions.get(sesion_id)
        if s is None or s.user_id != user.id:
            raise KeyError("Sesión no encontrada o expirada")
        s.last = time.time()
        return s

    def terminar(self, sesion_id: str, user: User) -> dict:
        s = self.obtener(sesion_id, user)
        datos = {"sesionId": s.id, "inicio": s.inicio, "fin": now_iso(), "turnos": s.turnos,
                 "intencion": s.intencion, "ticketId": s.ticket_id, "usoVoz": s.uso_voz,
                 "consentimiento": s.consentimiento, "proveedor": self.provider.name}
        try:
            self.helpdesk.registrar_sesion(user.token, datos)
        finally:
            with self.lock:
                self.sessions.pop(s.id, None)
        return datos

    def _limpiar(self) -> None:
        limite = time.time() - settings.session_ttl_seconds
        with self.lock:
            for k in [k for k, v in self.sessions.items() if v.last < limite]:
                self.sessions.pop(k, None)

    # ------------------------------------------------------------------ turno
    def turno(self, s: Session, texto: str, user: User, por_voz: bool = False) -> Reply:
        s.turnos += 1
        s.uso_voz = s.uso_voz or por_voz
        texto = (texto or "").strip()[:2000]
        if not texto:
            return Reply("No te escuché bien. ¿Puedes repetirlo o escribirlo?", s.etapa)

        if contiene(texto, REINICIAR):
            s.etapa, s.slots, s.clasificacion, s.borrador, s.intentos_equipo = "PROBLEMA", {}, None, None, 0
            return Reply("Listo, empecemos de nuevo. ¿Qué problema tienes?", s.etapa)

        m = TICKET_RE.search(texto)
        if m and s.etapa in ("PROBLEMA", "CREADO"):
            return self._consultar_ticket(s, user, f"SOP-{int(m.group(1)):06d}")
        if contiene(texto, MIS_TICKETS) and s.etapa in ("PROBLEMA", "CREADO"):
            return self._mis_tickets(s, user)

        handler = {
            "PROBLEMA": self._problema, "CREADO": self._problema, "EQUIPO": self._equipo, "DESDE": self._desde,
            "IMPACTO": self._impacto, "SEDE": self._sede, "BORRADOR": self._borrador_respuesta,
        }[s.etapa]
        return handler(s, texto, user)

    # ------------------------------------------------------------------ herramientas de consulta
    def _consultar_ticket(self, s: Session, user: User, numero: str) -> Reply:
        s.intencion = s.intencion or "CONSULTAR_ESTADO"
        try:
            d = self.helpdesk.ticket_por_numero(user.token, numero)
        except HelpdeskError as exc:
            if exc.status in (403, 404):
                return Reply(f"No encontré el ticket {numero} entre tus solicitudes.", s.etapa)
            return Reply("No pude consultar el sistema de tickets en este momento. Intenta más tarde.", s.etapa)
        r = d["resumen"]
        estado = ESTADOS.get(r["estado"], r["estado"])
        partes = [f"El ticket {numero} está {estado}, con prioridad {r['prioridad'].lower()}."]
        if r.get("tecnicoNombre"):
            partes.append(f"Lo atiende {r['tecnicoNombre']}.")
        elif r["estado"] == "PENDIENTE":
            partes.append("Todavía no tiene técnico asignado; está en revisión." if r.get("requiereRevision")
                          else "Pronto se le asignará un técnico.")
        if r["estado"] == "FINALIZADA" and d.get("solucion"):
            partes.append(f"Solución registrada: {d['solucion'][:200]}")
        return Reply(" ".join(partes), s.etapa, ticket={"id": r["id"], "numero": numero, "estado": r["estado"]})

    def _mis_tickets(self, s: Session, user: User) -> Reply:
        s.intencion = s.intencion or "CONSULTAR_ESTADO"
        try:
            page = self.helpdesk.mis_tickets(user.token, size=5)
        except HelpdeskError:
            return Reply("No pude consultar tus tickets en este momento.", s.etapa)
        items = page.get("content", [])
        if not items:
            return Reply("No tienes tickets registrados. ¿Quieres reportar un problema?", s.etapa)
        lineas = [f"{t['numero']}: {ESTADOS.get(t['estado'], t['estado'])}" + (f", con {t['tecnicoNombre']}" if t.get("tecnicoNombre") else "")
                  for t in items]
        return Reply("Tus tickets más recientes son: " + "; ".join(lineas) + ".", s.etapa)

    # ------------------------------------------------------------------ recolección del problema
    def _problema(self, s: Session, texto: str, user: User) -> Reply:
        if len(texto) < 12:
            return Reply("Cuéntame con un poco más de detalle qué está pasando: qué intentabas hacer y qué ocurre.", s.etapa)
        s.intencion = "REPORTAR_FALLA"
        s.slots = {"problema": texto}
        s.ticket_id = None
        d = self.provider.classify_text(texto, {})
        s.clasificacion = d.to_dict()
        self._extraer_extras(s, texto, user)

        partes = []
        if d.concluyente and d.tipo_falla_codigo:
            ft = taxonomy.by_code[d.tipo_falla_codigo]
            partes.append(f"Entiendo. Parece un caso de «{ft.nombre.lower()}». Es una clasificación preliminar; un técnico la confirmará.")
            check = SAFE_CHECKS.get(ft.categoria, [])
            if check:
                partes.append(f"Mientras tanto, puedes probar esto: {check[0]}")
        else:
            partes.append("Entiendo. Voy a registrar tu caso para que un técnico lo revise.")
        siguiente = self._siguiente_pregunta(s, user)
        partes.append(siguiente.texto)
        siguiente.texto = " ".join(partes)
        return siguiente

    def _extraer_extras(self, s: Session, texto: str, user: User) -> None:
        m = EQUIPO_RE.search(texto)
        if m:
            eq = self._buscar_equipo(s, user, m.group(1))
            if eq:
                s.slots["equipo"] = eq
        imp = self._parse_impacto(texto)
        if imp:
            s.slots["impacto"] = imp
        if contiene(texto, ("no puedo trabajar", "urgente", "detenido", "parado", "bloquea", "no podemos trabajar")):
            s.slots["bloquea"] = True

    def _siguiente_pregunta(self, s: Session, user: User) -> Reply:
        if "equipo" not in s.slots:
            s.etapa = "EQUIPO"
            return Reply("¿Qué equipo presenta la falla? Dime su código de inventario (por ejemplo PC-BOG-001) "
                         "o responde «ninguno» si no aplica.", s.etapa, sugerencias=["Ninguno"])
        if "desde" not in s.slots:
            s.etapa = "DESDE"
            return Reply("¿Desde cuándo ocurre?", s.etapa, sugerencias=["Desde hoy", "Desde ayer", "Hace varios días"])
        if "impacto" not in s.slots or "bloquea" not in s.slots:
            s.etapa = "IMPACTO"
            return Reply("¿A quién afecta y te impide trabajar? Por ejemplo: «solo a mí y no puedo trabajar».", s.etapa,
                         sugerencias=["Solo a mí, puedo seguir trabajando", "A mi área, no podemos trabajar", "A toda la sede"])
        if not self._sede_id(s, user):
            s.etapa = "SEDE"
            sedes = [x["nombre"] for x in self._catalogo(s, user).get("sedes", [])]
            return Reply("¿En qué sede estás?", s.etapa, sugerencias=sedes[:6])
        return self._crear_borrador(s, user)

    def _equipo(self, s: Session, texto: str, user: User) -> Reply:
        m = EQUIPO_RE.search(texto)
        if contiene(texto, SIN_EQUIPO):
            s.slots["equipo"] = None
        elif m and (eq := self._buscar_equipo(s, user, m.group(1))):
            s.slots["equipo"] = eq
        else:
            s.intentos_equipo += 1
            if s.intentos_equipo < 2:
                return Reply("No encontré ese código de equipo. Revisa la etiqueta de inventario o responde «ninguno».", s.etapa,
                             sugerencias=["Ninguno"])
            s.slots["equipo"] = None
            s.slots["equipo_texto"] = texto[:120]
        return self._siguiente_pregunta(s, user)

    def _desde(self, s: Session, texto: str, user: User) -> Reply:
        s.slots["desde"] = texto[:200]
        imp = self._parse_impacto(texto)
        if imp:
            s.slots["impacto"] = imp
        return self._siguiente_pregunta(s, user)

    def _impacto(self, s: Session, texto: str, user: User) -> Reply:
        imp = self._parse_impacto(texto)
        if imp:
            s.slots["impacto"] = imp
        t = normalize(texto)
        if any(p in t for p in ("no puedo", "no podemos", "bloquea", "detenid", "parad", "urgente")):
            s.slots["bloquea"] = True
        elif "puedo seguir" in t or "podemos seguir" in t or "no me impide" in t or re.search(r"\bno\b", t):
            s.slots.setdefault("bloquea", False)
        elif re.search(r"\bsi\b", t):
            s.slots["bloquea"] = True
        if "impacto" not in s.slots:
            return Reply("¿Afecta solo a ti, a tu área o a toda la sede?", s.etapa,
                         sugerencias=["Solo a mí", "A mi área", "A toda la sede"])
        s.slots.setdefault("bloquea", False)
        return self._siguiente_pregunta(s, user)

    def _sede(self, s: Session, texto: str, user: User) -> Reply:
        t = normalize(texto)
        for sede in self._catalogo(s, user).get("sedes", []):
            nombre = normalize(sede["nombre"])
            if nombre.split(" (")[0] in t or normalize(sede["codigo"]) in t or t in nombre:
                s.slots["sede_id"] = sede["id"]
                return self._siguiente_pregunta(s, user)
        return Reply("No reconocí la sede. Elige una de la lista.", s.etapa,
                     sugerencias=[x["nombre"] for x in self._catalogo(s, user).get("sedes", [])][:6])

    @staticmethod
    def _parse_impacto(texto: str) -> str | None:
        t = normalize(texto)
        if any(p in t for p in ("toda la sede", "todo el edificio", "toda la empresa", "todos en la sede", "toda la oficina")):
            return "SEDE"
        if any(p in t for p in ("mi area", "el area", "varios", "companeros", "todo el equipo", "toda el area", "mi equipo de trabajo")):
            return "AREA"
        if any(p in t for p in ("solo a mi", "solo yo", "solamente a mi", "unicamente a mi", "a mi solo", "solo mi")):
            return "INDIVIDUAL"
        return None

    # ------------------------------------------------------------------ borrador y confirmación
    def _crear_borrador(self, s: Session, user: User) -> Reply:
        cls = s.clasificacion or {}
        categoria = cls.get("categoria_codigo")
        eq = s.slots.get("equipo")
        partes = [s.slots["problema"]]
        if s.slots.get("desde"):
            partes.append(f"Desde: {s.slots['desde']}.")
        if s.slots.get("equipo_texto"):
            partes.append(f"Equipo indicado por el usuario: {s.slots['equipo_texto']}.")
        descripcion = " ".join(partes)[:4000]
        if len(descripcion) < 10:
            descripcion = (descripcion + " (reportado mediante el agente)")[:4000]
        prioridad = estimar_prioridad(s.slots["impacto"], bool(s.slots.get("bloquea")), categoria)
        tipo = taxonomy.by_code.get(cls.get("tipo_falla_codigo") or "")
        s.borrador = {
            "id": str(uuid.uuid4()),
            "descripcion": descripcion,
            "sedeId": self._sede_id(s, user),
            "equipoId": eq["id"] if eq else None,
            "equipo": f"{eq['codigo']} · {eq['tipo']}" if eq else None,
            "impacto": s.slots["impacto"],
            "bloqueaTrabajo": bool(s.slots.get("bloquea")),
            "categoriaSugerida": tipo.categoria if tipo else None,
            "tipoFallaSugerido": tipo.nombre if tipo else None,
            "prioridadEstimada": prioridad,
        }
        s.etapa = "BORRADOR"
        resumen = (f"Este es el resumen: {descripcion} Equipo: {s.borrador['equipo'] or 'no aplica'}. "
                   f"Prioridad estimada: {prioridad.lower()}"
                   + (f"; categoría sugerida: {tipo.categoria.lower()}." if tipo else ".")
                   + " ¿Confirmas que cree el ticket? Puedes pulsar «Confirmar» o decir «sí, confirmo».")
        return Reply(resumen, s.etapa, borrador=s.borrador, sugerencias=["Sí, confirmo", "Editar"])

    def _borrador_respuesta(self, s: Session, texto: str, user: User) -> Reply:
        if contiene(texto, AFIRMA) and not contiene(texto, ("no",)):
            return self.confirmar(s, s.borrador["id"], user, {})
        if contiene(texto, NIEGA):
            s.etapa = "PROBLEMA"
            s.borrador = None
            return Reply("De acuerdo, no lo creé. Puedes editar el borrador en pantalla o describirme de nuevo el problema.", s.etapa)
        return Reply("¿Confirmas que cree el ticket? Responde «sí, confirmo» o «editar».", s.etapa, borrador=s.borrador,
                     sugerencias=["Sí, confirmo", "Editar"])

    def confirmar(self, s: Session, borrador_id: str, user: User, cambios: dict) -> Reply:
        """Única vía para crear un ticket: requiere el borrador vigente y una acción explícita del usuario."""
        if not s.borrador or s.borrador["id"] != borrador_id:
            return Reply("Ese borrador ya no está vigente. Describe el problema de nuevo.", s.etapa)
        datos = {
            "descripcion": (cambios.get("descripcion") or s.borrador["descripcion"]).strip()[:4000],
            "sedeId": cambios.get("sedeId") or s.borrador["sedeId"],
            "equipoId": cambios.get("equipoId", s.borrador["equipoId"]),
            "impacto": cambios.get("impacto") or s.borrador["impacto"],
            "bloqueaTrabajo": bool(cambios.get("bloqueaTrabajo", s.borrador["bloqueaTrabajo"])),
        }
        try:
            creado = self.helpdesk.crear_ticket(user.token, datos)
        except HelpdeskError as exc:
            return Reply(f"No pude crear el ticket: {exc}. Revisa los datos e intenta de nuevo.", s.etapa, borrador=s.borrador)
        r = creado["resumen"]
        s.ticket_id, s.ticket_numero = r["id"], r["numero"]
        s.borrador = None
        s.etapa = "CREADO"
        s.slots = {}
        return Reply(f"Listo. Creé el ticket {r['numero']}. El sistema lo clasificará y asignará al técnico adecuado; "
                     "recibirás una notificación cuando tenga técnico. Puedes preguntarme por su estado cuando quieras.",
                     s.etapa, ticket={"id": r["id"], "numero": r["numero"], "estado": r["estado"]})

    # ------------------------------------------------------------------ utilidades
    def _catalogo(self, s: Session, user: User) -> dict:
        if s.catalogo is None:
            try:
                s.catalogo = self.helpdesk.catalogos(user.token)
            except HelpdeskError:
                s.catalogo = {"sedes": [], "equipos": []}
        return s.catalogo

    def _sede_id(self, s: Session, user: User):
        if s.slots.get("sede_id"):
            return s.slots["sede_id"]
        eq = s.slots.get("equipo")
        if eq:
            return eq["sedeId"]
        if s.perfil is None:
            try:
                s.perfil = self.helpdesk.me(user.token)
            except HelpdeskError:
                s.perfil = {}
        return s.perfil.get("sedeId")

    def _buscar_equipo(self, s: Session, user: User, codigo: str) -> dict | None:
        codigo = codigo.upper()
        for e in self._catalogo(s, user).get("equipos", []):
            if e["codigo"].upper() == codigo:
                return e
        return None


agent = Agent()
