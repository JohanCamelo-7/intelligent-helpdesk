#!/usr/bin/env bash
# Exporta todas las cuentas (administradores, técnicos y usuarios) con su contraseña de acceso a
# usuarios-y-contrasenas.csv (UTF-8, separado por ";", se abre directo en Excel).
#
# Las contraseñas se guardan cifradas (BCrypt) y no se pueden leer de la base: el archivo usa
#   - Demo1234!            para las cuentas de demostración (@demo.helpdesk.local)
#   - ADMIN_PASSWORD (.env) para el administrador principal (ADMIN_EMAIL)
#   - "(definida al crearla)" para las cuentas creadas a mano en la aplicación
#
# Uso: ./scripts/exportar-usuarios.sh [archivo.csv]
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "No existe .env en esta carpeta."; exit 1; }

SALIDA="${1:-usuarios-y-contrasenas.csv}"
ADMIN_EMAIL=$(grep -E '^ADMIN_EMAIL=' .env | cut -d= -f2- | tr -d '\r')
ADMIN_PASSWORD=$(grep -E '^ADMIN_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
AIRFLOW_PASSWORD=$(grep -E '^AIRFLOW_ADMIN_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')

# psql recibe los valores como variables (:'admin_email'), sin armar SQL concatenando texto.
{
  printf '\xEF\xBB\xBF'   # BOM: Excel reconoce las tildes
  docker compose exec -T postgres psql -U postgres -d helpdesk -X -q -v ON_ERROR_STOP=1 \
    -v admin_email="$ADMIN_EMAIL" -v admin_pass="$ADMIN_PASSWORD" <<'SQL'
\pset footer off
COPY (
  SELECT CASE u.rol WHEN 'ADMIN' THEN 'Administrador' WHEN 'TECNICO' THEN 'Técnico' ELSE 'Usuario' END AS "Rol",
         u.nombre AS "Nombre",
         u.email AS "Correo (usuario)",
         CASE WHEN lower(u.email) = lower(:'admin_email') THEN :'admin_pass'
              WHEN u.email ILIKE '%@demo.helpdesk.local' THEN 'Demo1234!'
              ELSE '(definida al crearla)' END AS "Contraseña",
         u.cargo AS "Cargo",
         COALESCE(u.area, '') AS "Área",
         COALESCE(s.nombre, '') AS "Sede",
         COALESCE(t.nivel, '') AS "Nivel técnico",
         COALESCE((SELECT string_agg(e.nombre, ', ' ORDER BY e.nombre)
                   FROM tecnico_especialidades te JOIN especialidades e ON e.id = te.especialidad_id
                   WHERE te.tecnico_id = u.id), '') AS "Especialidades",
         CASE WHEN u.activo THEN 'Sí' ELSE 'No' END AS "Activo"
  FROM usuarios u
  LEFT JOIN sedes s ON s.id = u.sede_id
  LEFT JOIN tecnicos t ON t.usuario_id = u.id
  ORDER BY CASE u.rol WHEN 'ADMIN' THEN 1 WHEN 'TECNICO' THEN 2 ELSE 3 END,
           NULLIF(regexp_replace(split_part(u.email, '@', 1), '\D', '', 'g'), '')::int NULLS FIRST,
           u.email
) TO STDOUT WITH (FORMAT csv, HEADER true, DELIMITER ';');
SQL
} > "$SALIDA"

echo "Archivo creado: $SALIDA ($(( $(wc -l < "$SALIDA") - 1 )) cuentas)"
echo "Airflow (http://localhost:8081): usuario admin / ${AIRFLOW_PASSWORD}"
echo "Contiene contraseñas: no lo compartas ni lo subas a un repositorio."
