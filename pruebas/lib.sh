# Funciones comunes de las pruebas de los hitos. Se carga con:  . "$(dirname "$0")/lib.sh"
# Funciona en Git Bash (Windows), Linux y macOS. Solo necesita docker, curl y las herramientas básicas de bash.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"
[ -f .env ] || { echo "Falta el archivo .env en $RAIZ. Ejecuta primero ./scripts/iniciar.sh"; exit 1; }

env_val() { grep -E "^$1=" .env | head -1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^"//' -e 's/"$//'; }
API="http://localhost:$(env_val HTTP_PORT || true)"
[ "$API" = "http://localhost:" ] && API="http://localhost:8080"
API="$API/api/v1"
ADMIN_EMAIL="${ADMIN_EMAIL:-$(env_val ADMIN_EMAIL)}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-$(env_val ADMIN_PASSWORD)}"
DEMO_PASSWORD="${DEMO_PASSWORD:-Demo1234!}"
RESULTADOS="$RAIZ/pruebas/resultados"
mkdir -p "$RESULTADOS"

ahora_ms() { date +%s%3N; }
seg() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", (b - a) / 1000 }'; }   # segundos entre dos marcas en ms

sql() {   # sql "SELECT ..." -> salida sin encabezados, columnas separadas por |
  docker compose exec -T postgres psql -U postgres -d helpdesk -X -q -tA -v ON_ERROR_STOP=1 -c "$1"
}
trino() { # trino "SELECT ..." -> salida TSV sin encabezados
  docker compose exec -T trino trino --user admin --catalog lake --output-format TSV --execute "$1" 2>/dev/null | tr -d '\r'
}

json_str() { # json_str CLAVE  (lee JSON por stdin; devuelve el primer valor de texto de esa clave)
  grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

login() {    # login EMAIL CLAVE -> imprime el token
  local r
  r=$(curl -s -X POST "$API/auth/login" -H 'Content-Type: application/json' \
      -d "{\"email\":\"$1\",\"password\":\"$2\"}")
  echo "$r" | json_str accessToken
}

# llamar METODO RUTA TOKEN [JSON] -> deja el cuerpo en $CUERPO, el código en $CODIGO y los segundos en $TIEMPO
llamar() {
  local salida
  if [ -n "${4:-}" ]; then
    salida=$(curl -s -X "$1" "$API$2" -H "Authorization: Bearer $3" -H 'Content-Type: application/json' \
             -d "$4" -w $'\n%{http_code} %{time_total}')
  else
    salida=$(curl -s -X "$1" "$API$2" -H "Authorization: Bearer $3" -w $'\n%{http_code} %{time_total}')
  fi
  CUERPO=$(echo "$salida" | sed '$d')
  read -r CODIGO TIEMPO <<<"$(echo "$salida" | tail -1)"
}

esperar() {  # esperar SEGUNDOS_MAX "descripción" comando...  -> repite el comando cada 5 s hasta que tenga éxito
  local max=$1 desc=$2; shift 2
  local ini; ini=$(date +%s)
  until "$@"; do
    if [ $(( $(date +%s) - ini )) -ge "$max" ]; then echo "  ✗ Tiempo agotado esperando: $desc"; return 1; fi
    sleep 5
  done
}

paso() { echo; echo "▶ $*"; }
ok()   { echo "  ✓ $*"; }
info() { echo "  · $*"; }

verificar_servicios() {
  local faltan
  faltan=$(docker compose ps --status running --services 2>/dev/null | tr -d '\r' | sort | \
           comm -13 - <(printf '%s\n' postgres rabbitmq helpdesk-api data-ingestion trino airflow nginx | sort) || true)
  if [ -n "$faltan" ]; then echo "Estos servicios no están corriendo: $faltan"; echo "Ejecuta ./scripts/iniciar.sh"; exit 1; fi
}

disparar_pipeline() { # disparar_pipeline TOKEN_ADMIN
  llamar POST /analitica/pipeline/ejecutar "$1"
  if [ "$CODIGO" = "200" ]; then info "Pipeline lanzado en Airflow ($(echo "$CUERPO" | json_str dag_run_id))"
  else info "Airflow respondió $CODIGO (puede haber una ejecución en curso); se espera a que termine"; fi
}
