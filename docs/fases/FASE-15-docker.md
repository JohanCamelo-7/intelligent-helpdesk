# FASE 15. Docker y despliegue local

## Qué se construyó

Un `docker-compose.yml` con health checks, dependencias ordenadas, volúmenes persistentes y dos redes (`app` y
`edge`). Solo Nginx publica un puerto (8080). Las consolas internas escuchan en 127.0.0.1. Los perfiles opcionales son
`edge` (túnel), `clamav` y `ollama`. Incluye scripts de verificación, respaldo y restauración.

## Rutas

- `docker-compose.yml`
- `.env.example`
- `scripts/verificar.sh`
- `infrastructure/backup/`

## Cómo ejecutar

```bash
cp .env.example .env && docker compose up -d --build && ./scripts/verificar.sh
```

## Cómo probar

`verificar.sh` debe terminar con 0 fallas.
