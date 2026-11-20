"""Extracción por lotes de datos maestros desde PostgreSQL hacia Landing (fotos completas).

Se usa el rol de solo lectura lake_reader. Se aplica minimización en el origen:
NO se extraen correos, contraseñas ni nombres de usuarios finales.
"""
from __future__ import annotations

from datetime import datetime, timezone

from .config import config
from .io import LandingSource

QUERIES: dict[str, str] = {
    "usuarios": """
        SELECT u.id::text AS entity_id, u.cargo, u.area, s.codigo AS sede_codigo, u.rol, u.activo, u.updated_at
        FROM usuarios u LEFT JOIN sedes s ON s.id = u.sede_id""",
    "tecnicos": """
        SELECT t.usuario_id::text AS entity_id, u.nombre, t.nivel, t.disponible, t.carga_maxima, u.activo,
               COALESCE(string_agg(DISTINCT e.codigo, ',' ORDER BY e.codigo), '') AS especialidades,
               COALESCE(string_agg(DISTINCT c.codigo, ',' ORDER BY c.codigo), '') AS categorias,
               t.updated_at
        FROM tecnicos t JOIN usuarios u ON u.id = t.usuario_id
        LEFT JOIN tecnico_especialidades te ON te.tecnico_id = t.usuario_id
        LEFT JOIN especialidades e ON e.id = te.especialidad_id
        LEFT JOIN categorias c ON c.id = e.categoria_id
        GROUP BY t.usuario_id, u.nombre, t.nivel, t.disponible, t.carga_maxima, u.activo, t.updated_at""",
    "sedes": "SELECT codigo AS entity_id, nombre, ciudad, activo, updated_at FROM sedes",
    "equipos": """
        SELECT e.codigo_inventario AS entity_id, e.tipo, e.marca, e.modelo, s.codigo AS sede_codigo, e.activo, e.updated_at
        FROM equipos e JOIN sedes s ON s.id = e.sede_id""",
    "categorias": "SELECT codigo AS entity_id, nombre, updated_at FROM categorias",
    "tipos_falla": """
        SELECT t.codigo AS entity_id, t.nombre, c.codigo AS categoria_codigo, t.updated_at
        FROM tipos_falla t JOIN categorias c ON c.id = t.categoria_id""",
    "prioridades": "SELECT codigo AS entity_id, nombre, peso FROM prioridades",
    "estados": "SELECT codigo AS entity_id, nombre, abierto FROM estados",
}


def extract(run_id: str, landing: LandingSource | None = None, conn=None) -> dict[str, int]:
    import psycopg
    from psycopg.rows import dict_row
    landing = landing or LandingSource()
    now = datetime.now(timezone.utc)
    dt = now.strftime("%Y-%m-%d")
    counts: dict[str, int] = {}
    own = conn is None
    conn = conn or psycopg.connect(config.helpdesk_dsn, row_factory=dict_row)
    try:
        with conn.cursor() as cur:
            for entity, sql in QUERIES.items():
                cur.execute(sql)
                rows = [{**r, "_snapshot_run_id": run_id, "_snapshot_ts": now.isoformat()} for r in cur.fetchall()]
                landing.write(f"landing/snapshots/{entity}/dt={dt}/{run_id}.jsonl", rows)
                counts[entity] = len(rows)
    finally:
        if own:
            conn.close()
    return counts
