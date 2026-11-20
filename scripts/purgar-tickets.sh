#!/usr/bin/env bash
# Borra los tickets con número >= DESDE (por ejemplo, una carga de demostración duplicada) de PostgreSQL y del
# Data Lake (Bronze y Landing), y reconstruye Silver y Gold.
#
# Uso:  ./scripts/purgar-tickets.sh SOP-030001
set -euo pipefail
export MSYS_NO_PATHCONV=1          # Git Bash: no convertir rutas de los comandos que corren en los contenedores
cd "$(dirname "$0")/.."

DESDE="${1:-}"
if [[ ! "$DESDE" =~ ^SOP-[0-9]{6}$ ]]; then
  echo "Uso: ./scripts/purgar-tickets.sh SOP-030001   (número de ticket inicial, formato SOP-NNNNNN)"
  exit 1
fi

psql() { docker compose exec -T postgres psql -U postgres -d helpdesk -v ON_ERROR_STOP=1 -X -q "$@"; }

echo "Tickets que se borrarán (número >= $DESDE):"
psql -c "SELECT count(*) AS tickets, min(numero) AS desde, max(numero) AS hasta,
                min(fecha_creacion)::date AS creados_desde, max(fecha_creacion)::date AS creados_hasta,
                count(*) FILTER (WHERE NOT es_demo) AS NO_demo
         FROM tickets WHERE numero >= '$DESDE';"
echo "Quedarán: $(psql -t -A -c "SELECT count(*) FROM tickets WHERE numero < '$DESDE';") tickets."
echo
read -r -p "¿Borrarlos de la base de datos y del Data Lake? Escribe SI para confirmar: " resp
case "$(echo "$resp" | tr '[:lower:]' '[:upper:]')" in SI|SÍ|S) ;; *) echo "Cancelado. No se borró nada."; exit 1 ;; esac

echo
echo "1/4 Pausando el pipeline del Data Lake (para que no escriba mientras se purga)..."
docker compose exec -T airflow airflow dags pause lakehouse_pipeline >/dev/null

echo "2/4 Borrando en PostgreSQL (una sola transacción)..."
psql <<SQL
BEGIN;
CREATE TEMP TABLE purga AS SELECT id FROM tickets WHERE numero >= '$DESDE';
UPDATE agente_sesiones SET ticket_id = NULL WHERE ticket_id IN (SELECT id FROM purga);
DELETE FROM outbox    WHERE aggregate_id IN (SELECT id::text FROM purga);
DELETE FROM audit_log WHERE recurso_id   IN (SELECT id::text FROM purga);
-- historial, adjuntos, diagnósticos, asignaciones, actividades y encuestas se borran en cascada
DELETE FROM tickets   WHERE id IN (SELECT id FROM purga);
COMMIT;
SQL
echo "   Tickets en la base: $(psql -t -A -c "SELECT count(*) FROM tickets;")"

echo "3/4 Purgando Bronze y Landing en el Data Lake (unos minutos)..."
docker compose exec -T airflow python -m lakehouse.cli purge --desde-numero "$DESDE" 2>&1 \
  | grep -E "INFO|ERROR|Error|Traceback" | grep -v "py4j\|SparkContext\|log4j" || true

echo "4/4 Reactivando el pipeline y lanzándolo para reconstruir Silver y Gold..."
docker compose exec -T airflow airflow dags unpause lakehouse_pipeline >/dev/null
docker compose exec -T airflow airflow dags trigger lakehouse_pipeline >/dev/null
echo
echo "Listo. En 2-5 minutos el pipeline termina; luego revisa Analítica (Desde 01/01/2024, con datos de demostración)."
