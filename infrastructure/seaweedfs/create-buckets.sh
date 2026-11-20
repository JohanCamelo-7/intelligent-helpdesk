#!/bin/sh
# Crea los buckets con la identidad de administrador. Es idempotente.
set -u
export AWS_ACCESS_KEY_ID="$S3_ADMIN_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="$S3_ADMIN_SECRET_KEY"
export AWS_DEFAULT_REGION=us-east-1
EP="http://seaweedfs:8333"

for i in $(seq 1 30); do
  if aws --endpoint-url "$EP" s3 ls >/dev/null 2>&1; then break; fi
  echo "Esperando SeaweedFS S3 ($i/30)..."; sleep 2
done

for b in helpdesk-attachments helpdesk-reports lake; do
  if aws --endpoint-url "$EP" s3 ls "s3://$b" >/dev/null 2>&1; then
    echo "Bucket $b ya existe"
  else
    aws --endpoint-url "$EP" s3 mb "s3://$b" && echo "Bucket $b creado"
  fi
done
