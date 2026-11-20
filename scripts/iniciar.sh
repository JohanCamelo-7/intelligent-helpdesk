#!/usr/bin/env bash
# Arranque completo en un solo paso: crea .env si falta, construye, inicia y verifica.
# Uso (Git Bash, WSL, Linux o macOS), desde cualquier carpeta:  ./scripts/iniciar.sh
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null 2>&1 || { echo "No se encontró Docker. Instala Docker Desktop."; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker no está en ejecución. Abre Docker Desktop, espera a que inicie y vuelve a intentar."; exit 1; }

env_nuevo=false
if [ ! -f .env ]; then
  ./scripts/preparar-env.sh
  env_nuevo=true
fi

# Un .env nuevo no sirve con una base de datos creada con otras contraseñas.
if $env_nuevo && docker volume inspect helpdesk_pgdata >/dev/null 2>&1; then
  echo
  echo "ATENCIÓN: existe una base de datos de una instalación anterior, creada con OTRAS contraseñas."
  echo "Con el .env nuevo los servicios no podrán conectarse a ella."
  echo "Opciones: recuperar el .env anterior (y cancelar aquí), o borrar los datos anteriores."
  read -r -p "¿Borrar los datos anteriores y empezar de cero? Escribe SI para confirmar: " resp
  case "$(echo "$resp" | tr '[:lower:]' '[:upper:]')" in SI|SÍ|S|YES|Y) resp=SI ;; esac
  if [ "$resp" = "SI" ]; then
    docker compose down -v --remove-orphans
  else
    rm -f .env
    echo "Cancelado. No se borró nada. Copia tu .env anterior en esta carpeta y vuelve a ejecutar el script."
    exit 1
  fi
fi

echo
echo "Construyendo e iniciando los servicios (la primera vez tarda 10-20 minutos)..."
docker compose up -d --build

PORT=$(grep -E '^HTTP_PORT=' .env | cut -d= -f2); PORT=${PORT:-8080}
echo
echo "Esperando a que la aplicación responda (máximo 5 minutos)..."
for _ in $(seq 1 60); do
  if curl -fs "http://localhost:$PORT/" >/dev/null 2>&1 \
     && docker compose exec -T helpdesk-api curl -fs http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    break
  fi
  sleep 5
done
sleep 20   # margen para Airflow y Trino

echo
./scripts/verificar.sh || echo "Algún servicio aún no responde; espera un minuto y ejecuta ./scripts/verificar.sh"

echo
echo "Listo. Abre http://localhost:$PORT"
echo "  Usuario:    $(grep '^ADMIN_EMAIL=' .env | cut -d= -f2)"
echo "  Contraseña: $(grep '^ADMIN_PASSWORD=' .env | cut -d= -f2)"
echo "El archivo .env tiene tus contraseñas: no lo borres ni lo reemplaces al actualizar el proyecto."
