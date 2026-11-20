"""Cliente mínimo del protocolo REST de Trino (sin dependencias extra).

Se conecta con el usuario `analytics`, que en infrastructure/trino/rules.json solo tiene SELECT sobre los esquemas
gold y ops del catálogo lake. Aunque la guardia SQL fallara, Trino rechazaría cualquier escritura.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field

import httpx

from ..config import settings


class TrinoError(Exception):
    def __init__(self, mensaje: str, nombre: str = "") -> None:
        super().__init__(mensaje)
        self.nombre = nombre


@dataclass
class Resultado:
    columnas: list[dict] = field(default_factory=list)   # [{"nombre": .., "tipo": ..}]
    filas: list[list] = field(default_factory=list)
    truncado: bool = False
    duracion_ms: int = 0


class TrinoClient:
    def __init__(self, url: str | None = None, user: str | None = None, client: httpx.Client | None = None) -> None:
        self.url = (url or settings.trino_url).rstrip("/")
        self.user = user or settings.trino_user
        self.http = client or httpx.Client(timeout=settings.lake_timeout + 10)

    def consultar(self, sql: str, max_filas: int | None = None, schema: str = "gold") -> Resultado:
        max_filas = max_filas or settings.lake_max_filas
        headers = {
            "X-Trino-User": self.user,
            "X-Trino-Catalog": "lake",
            "X-Trino-Schema": schema,
            "X-Trino-Source": "ai-service-agente-data-lake",
            "X-Trino-Time-Zone": "America/Bogota",
            "X-Trino-Session": f"query_max_execution_time={int(settings.lake_timeout)}s",
        }
        inicio = time.perf_counter()
        try:
            for _ in range(3):                    # 503 = Trino ocupado o iniciando: reintentar
                resp = self.http.post(f"{self.url}/v1/statement", content=sql.encode("utf-8"), headers=headers)
                if resp.status_code != 503:
                    break
                time.sleep(1)
        except httpx.HTTPError as exc:
            raise TrinoError(f"No se pudo conectar con Trino: {exc}", "CONEXION") from exc
        res = Resultado()
        while True:
            if resp.status_code != 200:
                raise TrinoError(f"Trino respondió HTTP {resp.status_code}: {resp.text[:300]}", "HTTP")
            body = resp.json()
            if body.get("error"):
                err = body["error"]
                raise TrinoError(err.get("message", "Error de Trino"), err.get("errorName", ""))
            if body.get("columns") and not res.columnas:
                res.columnas = [{"nombre": c["name"], "tipo": c["type"]} for c in body["columns"]]
            for fila in body.get("data") or []:
                if len(res.filas) >= max_filas:
                    res.truncado = True
                    break
                res.filas.append(fila)
            siguiente = body.get("nextUri")
            if not siguiente:
                break
            if res.truncado:                      # ya hay suficientes filas: cancelar la consulta
                try:
                    self.http.delete(siguiente, headers={"X-Trino-User": self.user})
                except httpx.HTTPError:
                    pass
                break
            if time.perf_counter() - inicio > settings.lake_timeout + 5:
                self.http.delete(siguiente, headers={"X-Trino-User": self.user})
                raise TrinoError("La consulta superó el tiempo máximo", "EXCEEDED_TIME_LIMIT")
            resp = self.http.get(siguiente, headers={"X-Trino-User": self.user})
        res.duracion_ms = int((time.perf_counter() - inicio) * 1000)
        return res
