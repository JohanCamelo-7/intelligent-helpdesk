"""Cliente HTTP de helpdesk-api.

Las herramientas del agente usan el JWT DEL PROPIO USUARIO: el agente nunca puede
ver ni hacer más de lo que ese usuario puede hacer. Solo el análisis de eventos usa
la clave de servicio, contra endpoints internos que no exponen datos personales.
"""
from __future__ import annotations

import time

import httpx

from .config import settings


class HelpdeskError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class HelpdeskClient:
    def __init__(self, base_url: str | None = None, client: httpx.Client | None = None) -> None:
        self.base = (base_url or settings.helpdesk_url).rstrip("/")
        self.http = client or httpx.Client(timeout=20)

    def _req(self, method: str, path: str, token: str | None = None, **kw) -> dict | list | None:
        headers = kw.pop("headers", {})
        if token:
            headers["Authorization"] = f"Bearer {token}"
        r = self.http.request(method, f"{self.base}{path}", headers=headers, **kw)
        if r.status_code >= 400:
            try:
                detail = r.json().get("detail") or r.text
            except ValueError:
                detail = r.text
            raise HelpdeskError(r.status_code, str(detail)[:300])
        return r.json() if r.content else None

    # --- Interno (clave de servicio) ---
    def contexto(self, ticket_id: str, retries: int = 3) -> dict:
        last: Exception | None = None
        for i in range(retries):
            try:
                return self._req("GET", f"/internal/tickets/{ticket_id}/contexto",
                                 headers={"X-Service-Key": settings.service_key})  # type: ignore[return-value]
            except (httpx.HTTPError, HelpdeskError) as exc:
                if isinstance(exc, HelpdeskError) and exc.status == 404:
                    raise
                last = exc
                time.sleep(2 * (i + 1))
        raise last  # type: ignore[misc]

    # --- Con el JWT del usuario ---
    def me(self, token: str) -> dict:
        return self._req("GET", "/api/v1/auth/me", token)  # type: ignore[return-value]

    def catalogos(self, token: str) -> dict:
        return self._req("GET", "/api/v1/catalogos", token)  # type: ignore[return-value]

    def ticket_por_numero(self, token: str, numero: str) -> dict:
        return self._req("GET", f"/api/v1/tickets/numero/{numero}", token)  # type: ignore[return-value]

    def mis_tickets(self, token: str, size: int = 5) -> dict:
        return self._req("GET", "/api/v1/tickets", token, params={"size": size})  # type: ignore[return-value]

    def crear_ticket(self, token: str, datos: dict) -> dict:
        return self._req("POST", "/api/v1/tickets", token, params={"canal": "AGENTE"}, json=datos)  # type: ignore[return-value]

    def registrar_sesion(self, token: str, datos: dict) -> None:
        self._req("POST", "/api/v1/agente/sesiones", token, json=datos)
