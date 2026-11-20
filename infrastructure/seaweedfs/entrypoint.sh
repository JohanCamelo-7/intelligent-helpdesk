#!/bin/sh
# Genera la configuración de identidades S3 a partir de variables de entorno
# y arranca SeaweedFS en modo servidor único (master + volume + filer + S3).
set -eu

mkdir -p /etc/seaweedfs /data
cat > /etc/seaweedfs/s3.json <<EOF
{
  "identities": [
    {
      "name": "admin",
      "credentials": [{"accessKey": "${S3_ADMIN_ACCESS_KEY}", "secretKey": "${S3_ADMIN_SECRET_KEY}"}],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    },
    {
      "name": "helpdesk",
      "credentials": [{"accessKey": "${S3_HELPDESK_ACCESS_KEY}", "secretKey": "${S3_HELPDESK_SECRET_KEY}"}],
      "actions": [
        "Read:helpdesk-attachments", "Write:helpdesk-attachments", "List:helpdesk-attachments",
        "Read:helpdesk-reports", "Write:helpdesk-reports", "List:helpdesk-reports"
      ]
    },
    {
      "name": "ai",
      "credentials": [{"accessKey": "${S3_AI_ACCESS_KEY}", "secretKey": "${S3_AI_SECRET_KEY}"}],
      "actions": ["Read:helpdesk-attachments"]
    },
    {
      "name": "lake",
      "credentials": [{"accessKey": "${S3_LAKE_ACCESS_KEY}", "secretKey": "${S3_LAKE_SECRET_KEY}"}],
      "actions": ["Read:lake", "Write:lake", "List:lake", "Tagging:lake"]
    }
  ]
}
EOF

exec weed server -dir=/data -ip.bind=0.0.0.0 \
  -master.volumeSizeLimitMB=1024 -volume.max=0 \
  -s3 -s3.port=8333 -s3.config=/etc/seaweedfs/s3.json
