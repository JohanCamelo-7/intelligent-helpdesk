#!/usr/bin/env bash
# Crea el archivo .env a partir de .env.example con contraseñas y secretos aleatorios.
# Uso: ./scripts/preparar-env.sh            (no toca un .env existente)
#      ./scripts/preparar-env.sh --forzar   (lo reemplaza; guarda una copia .env.bak)
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ] && [ "${1:-}" != "--forzar" ]; then
  echo ".env ya existe; no se modificó. Usa --forzar para regenerarlo."
  exit 0
fi
[ -f .env ] && cp .env .env.bak

# Lee primero un bloque fijo de bytes (evita el SIGPIPE de "tr | head" con pipefail)
rand() { head -c 4000 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }

ADMIN_PASS="Admin$(rand 10)7"
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    JWT_SECRET=*)      echo "JWT_SECRET=$(rand 64)" ;;
    ADMIN_PASSWORD=*)  echo "ADMIN_PASSWORD=$ADMIN_PASS" ;;
    *=cambia-*)        echo "${line%%=*}=$(rand 24)" ;;
    *)                 echo "$line" ;;
  esac
done < .env.example > .env

echo "Archivo .env creado con secretos aleatorios."
echo "  Usuario administrador:   $(grep '^ADMIN_EMAIL=' .env | cut -d= -f2)"
echo "  Contraseña:              $ADMIN_PASS"
echo "  Airflow (usuario admin): $(grep '^AIRFLOW_ADMIN_PASSWORD=' .env | cut -d= -f2)"
echo "Guárdalas: también quedan en el archivo .env."
