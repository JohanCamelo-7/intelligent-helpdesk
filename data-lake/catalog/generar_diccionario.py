"""Genera docs/datos/diccionario-de-datos.md a partir de catalog/tablas.yaml y quality/rules.yaml.

Uso: python data-lake/catalog/generar_diccionario.py
"""
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
catalogo = yaml.safe_load((ROOT / "data-lake/catalog/tablas.yaml").read_text(encoding="utf-8"))
reglas = yaml.safe_load((ROOT / "data-lake/quality/rules.yaml").read_text(encoding="utf-8"))["tables"]

lines = ["# Diccionario de datos del Data Lake", "",
         "Generado automáticamente desde `data-lake/catalog/tablas.yaml` y `data-lake/quality/rules.yaml`. No editar a mano.", ""]
for capa in ["Bronze", "Silver", "Gold", "Ops"]:
    lines += [f"## Capa {capa}", ""]
    for nombre, t in catalogo["tablas"].items():
        if t.get("capa") != capa:
            continue
        lines += [f"### `{nombre}`", "", t["descripcion"], ""]
        meta = [("Fuente", t.get("fuente")), ("Partición", t.get("particion")), ("Retención", t.get("retencion")),
                ("Datos personales", t.get("pii")), ("Consumidores", t.get("consumidores")),
                ("Responsable", t.get("responsable", catalogo["responsable_por_defecto"]))]
        lines += [f"- **{k}:** {v}" for k, v in meta if v] + [""]
        if t.get("columnas"):
            lines += ["| Columna | Descripción |", "| --- | --- |"]
            lines += [f"| `{c}` | {d} |" for c, d in t["columnas"].items()] + [""]
        if nombre in reglas:
            r = reglas[nombre]
            lines += ["Reglas de calidad:", ""]
            lines += [f"- `{x['name']}` ({x['severity']}): `{x['expr']}`" for x in r.get("row_rules", [])]
            lines += [f"- `{x['name']}` ({x['severity']}): {x['type']}" for x in r.get("table_rules", [])] + [""]

out = ROOT / "docs/datos/diccionario-de-datos.md"
out.write_text("\n".join(lines), encoding="utf-8")
print(f"Escrito {out}")
