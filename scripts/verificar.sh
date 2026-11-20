#!/usr/bin/env bash
# Comprueba que los servicios responden después de "docker compose up".
set -u
cd "$(dirname "$0")/.."
if [ ! -f .env ]; then
  echo "No existe el archivo .env. Créalo con: ./scripts/preparar-env.sh   (Windows: scripts\\preparar-env.ps1)"
  exit 1
fi
PORT=$(grep -E '^HTTP_PORT=' .env 2>/dev/null | cut -d= -f2); PORT=${PORT:-8080}
ok=0; fail=0
check() {
  if eval "$2" >/dev/null 2>&1; then echo "  [OK]    $1"; ok=$((ok+1)); else echo "  [FALLA] $1"; fail=$((fail+1)); fi
}
echo "Estado de los contenedores:"
docker compose ps --format "table {{.Service}}\t{{.State}}\t{{.Health}}"
echo; echo "Comprobaciones:"
check "Frontend (Nginx) en http://localhost:$PORT"      "curl -fs http://localhost:$PORT/"
check "helpdesk-api /actuator/health"                  "docker compose exec -T helpdesk-api curl -fs http://127.0.0.1:8080/actuator/health"
check "ai-service /health"                             "docker compose exec -T ai-service python -c \"import urllib.request;urllib.request.urlopen('http://127.0.0.1:8000/health')\""
check "API pública responde (401 esperado sin login)"  "[ \$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$PORT/api/v1/tickets) = 401 ]"
check "Endpoints internos NO publicados (404)"         "[ \$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$PORT/internal/catalogos) = 404 ]"
check "Airflow en http://localhost:8081"               "curl -fs http://localhost:8081/api/v2/monitor/health"
check "Trino en http://localhost:8082"                 "curl -fs http://localhost:8082/v1/info"
check "PostgreSQL"                                     "docker compose exec -T postgres pg_isready -U postgres"
echo; echo "Resultado: $ok correctas, $fail con falla."
[ "$fail" -eq 0 ]
