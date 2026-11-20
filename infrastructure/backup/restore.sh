#!/usr/bin/env bash
# Restauración desde una carpeta creada por backup.sh
# Uso: ./infrastructure/backup/restore.sh backups/20261112-010000
set -euo pipefail
cd "$(dirname "$0")/../.."
SRC="${1:?Indica la carpeta del respaldo}"

echo "Deteniendo servicios de aplicación..."
docker compose stop helpdesk-api ai-service data-ingestion airflow trino nginx

for db in helpdesk airflow lake_catalog; do
  echo "Restaurando base $db..."
  docker compose exec -T postgres pg_restore -U postgres --clean --if-exists -d "$db" < "$SRC/$db.dump"
done

echo "Restaurando almacenamiento de objetos..."
docker compose stop seaweedfs
docker run --rm -v helpdesk_s3data:/data -v "$(realpath "$SRC")":/backup alpine:3.20 \
  sh -c "rm -rf /data/* && tar xzf /backup/s3data.tar.gz -C /data"

docker compose up -d
echo "Restauración completada."
