#!/usr/bin/env bash
# Respaldo diario: bases PostgreSQL + volumen de SeaweedFS.
# Uso: ./infrastructure/backup/backup.sh [carpeta_destino]
set -euo pipefail
cd "$(dirname "$0")/../.."
DEST="${1:-./backups}/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"

for db in helpdesk airflow lake_catalog; do
  echo "Respaldando base $db..."
  docker compose exec -T postgres pg_dump -U postgres -Fc "$db" > "$DEST/$db.dump"
done

echo "Respaldando almacenamiento de objetos (SeaweedFS)..."
docker run --rm -v helpdesk_s3data:/data:ro -v "$(realpath "$DEST")":/backup alpine:3.20 \
  tar czf /backup/s3data.tar.gz -C /data .

cp .env "$DEST/env.backup" 2>/dev/null || true
echo "Respaldo listo en $DEST"
