"""Guardia SQL: solo deja pasar UNA consulta de lectura sobre las tablas conocidas de gold y ops.

Es la primera barrera; la segunda es el usuario `analytics` de Trino (solo SELECT en gold y ops).
Los mensajes de error son claros a propósito: el agente se los devuelve al modelo para que corrija la consulta.
"""
from __future__ import annotations

import sqlglot
from sqlglot import exp
from sqlglot.errors import ParseError

ESQUEMAS_PERMITIDOS = {"gold", "ops"}

_PROHIBIDOS = (exp.Insert, exp.Update, exp.Delete, exp.Merge, exp.Create, exp.Drop, exp.Alter, exp.TruncateTable,
               exp.Command, exp.Grant, exp.Set, exp.Use, exp.Transaction, exp.Commit, exp.Rollback)


class SQLRechazado(Exception):
    pass


def validar(sql: str, tablas_conocidas: set[str], limite: int) -> str:
    """Devuelve la consulta normalizada (con LIMIT) o lanza SQLRechazado con el motivo."""
    sql = (sql or "").strip().rstrip(";").strip()
    if not sql:
        raise SQLRechazado("La consulta está vacía.")
    try:
        sentencias = [s for s in sqlglot.parse(sql, read="trino") if s is not None]
    except ParseError as exc:
        raise SQLRechazado(f"La consulta no es SQL válido para Trino: {str(exc).splitlines()[0]}") from exc
    if len(sentencias) != 1:
        raise SQLRechazado("Solo se permite una consulta por pregunta.")
    arbol = sentencias[0]
    if not isinstance(arbol, exp.Query):
        raise SQLRechazado("Solo se permiten consultas de lectura (SELECT).")
    for nodo in arbol.walk():
        if isinstance(nodo, _PROHIBIDOS):
            raise SQLRechazado(f"Operación no permitida: {nodo.key.upper()}. Solo se permite leer.")

    ctes = {c.alias_or_name.lower() for c in arbol.find_all(exp.CTE)}
    conocidas_cortas = {t.split(".", 1)[1]: t for t in tablas_conocidas}
    usadas = set()
    for tabla in arbol.find_all(exp.Table):
        nombre = tabla.name.lower()
        if not nombre or (not tabla.db and nombre in ctes):
            continue
        catalogo = tabla.catalog.lower()
        esquema = tabla.db.lower() or "gold"
        if catalogo and catalogo != "lake":
            raise SQLRechazado(f"Catálogo no permitido: {catalogo}. Usa solo las tablas del lago (gold.*, ops.*).")
        if esquema not in ESQUEMAS_PERMITIDOS:
            raise SQLRechazado(f"Esquema no permitido: {esquema}. Solo se puede consultar gold y ops.")
        completo = f"{esquema}.{nombre}"
        if completo not in tablas_conocidas:
            sugerencia = conocidas_cortas.get(nombre)
            pista = f" ¿Quisiste decir {sugerencia}?" if sugerencia else ""
            raise SQLRechazado(f"La tabla {completo} no existe.{pista} Tablas disponibles: {', '.join(sorted(tablas_conocidas))}")
        usadas.add(completo)
    if not usadas:
        raise SQLRechazado("La consulta debe leer al menos una tabla del lago.")

    # Límite de filas: se respeta uno menor; si no hay o es mayor, se aplica el máximo.
    if isinstance(arbol, exp.Select):
        actual = arbol.args.get("limit")
        valor = None
        if actual is not None:
            try:
                valor = int(actual.expression.name) if actual.expression is not None else None
            except (TypeError, ValueError):
                valor = None
        if valor is None or valor > limite:
            arbol = arbol.limit(limite)
    else:  # UNION / INTERSECT / EXCEPT
        arbol = exp.select("*").from_(arbol.subquery("q")).limit(limite)
    return arbol.sql(dialect="trino", pretty=True)


def tablas_de(sql: str) -> list[str]:
    try:
        arbol = sqlglot.parse_one(sql, read="trino")
    except ParseError:
        return []
    return sorted({f"{(t.db or 'gold').lower()}.{t.name.lower()}" for t in arbol.find_all(exp.Table) if t.name})
