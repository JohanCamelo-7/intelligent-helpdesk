# FASE 7. Carga y almacenamiento de imágenes

## Qué se construyó

Hasta 5 imágenes de 5 MB por ticket (JPEG, PNG o WebP). El tipo real se valida con Apache Tika (no por la extensión)
y el análisis con ClamAV es opcional (perfil `clamav`). Se guardan en SeaweedFS (bucket `helpdesk-attachments`) con
clave aleatoria y SHA-256. La imagen la entrega el backend tras comprobar permisos; nunca se publica el almacenamiento.

## Rutas

- `helpdesk-api/src/main/java/com/helpdesk/api/ticket/FileValidator.java`
- `helpdesk-api/src/main/java/com/helpdesk/api/ticket/AttachmentService.java`
- `helpdesk-api/src/main/java/com/helpdesk/api/ticket/ClamAvClient.java`
- `infrastructure/seaweedfs/`

## Cómo ejecutar

```bash
docker compose up -d seaweedfs s3-init
docker compose --profile clamav up -d clamav   # opcional; luego CLAMAV_ENABLED=true
```

## Cómo probar

`FileValidatorTest`. Subir un `.exe` renombrado a `.jpg` debe responder 415.
