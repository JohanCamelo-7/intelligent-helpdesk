# FASE 16. Publicación mediante Cloudflare Tunnel y Cloudflare Access

## Qué se construyó

Túnel saliente `cloudflared` → `nginx:80`, sin puertos entrantes. HTTPS lo entrega Cloudflare. Airflow (opcional) se
publica en otro subdominio protegido con Access. Nginx toma la IP real de `CF-Connecting-IP` y los límites de tamaño
y tiempo son compatibles con Cloudflare.

## Rutas

- `infrastructure/cloudflared/config.example.yml`
- `infrastructure/nginx/default.conf`
- `docs/despliegue/cloudflare-tunnel.md`

## Cómo ejecutar

```bash
docker compose --profile edge up -d
```

## Cómo probar

Lista de verificación externa en `docs/despliegue/cloudflare-tunnel.md` §6.
