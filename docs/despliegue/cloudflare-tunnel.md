# Publicación con Cloudflare Tunnel

El servidor abre una conexión **saliente** cifrada hacia Cloudflare. No se abren puertos en el router, no se necesita
IP pública fija y Cloudflare entrega el certificado HTTPS. Esto también habilita el micrófono del asistente, porque
los navegadores solo lo permiten en contextos seguros.

```
Navegador ─HTTPS─> Cloudflare (DNS, WAF, Access) ─túnel─> cloudflared ─> nginx:80 ─> helpdesk-api / ai-service
```

## Requisitos

- Cuenta gratuita de Cloudflare y un dominio con sus servidores DNS en Cloudflare.
- Sin dominio: usa un *Quick Tunnel* solo para una demostración (ver al final).

## 1. Crear el túnel (administrado desde el panel, recomendado)

1. Cloudflare Zero Trust → **Networks → Tunnels → Create a tunnel** → tipo *Cloudflared* → nombre `helpdesk`.
2. Copia el **token** que aparece en el comando de instalación y pégalo en `.env` como `TUNNEL_TOKEN`.
3. En **Public Hostnames** agrega:

| Subdominio | Servicio |
| --- | --- |
| `soporte.midominio.com` | `HTTP` → `nginx:80` |
| `datos.midominio.com` (opcional) | `HTTP` → `airflow:8080` |

La regla final por defecto (cualquier otro nombre) responde 404. Para versionar las reglas como código, usa
`infrastructure/cloudflared/config.example.yml`.

## 2. Ajustar la aplicación

En `.env`:

```
PUBLIC_DOMAIN=soporte.midominio.com
CORS_ORIGINS=https://soporte.midominio.com
COOKIE_SECURE=true
```

La API se sirve en el mismo dominio (`/api`), así que no hay CORS entre dominios y la cookie de refresco puede ser
`SameSite=Strict`. Spring usa `server.forward-headers-strategy=framework` y Nginx reenvía `X-Forwarded-Proto` y
`CF-Connecting-IP` (IP real para auditoría y limitación de peticiones).

## 3. Iniciar

```bash
docker compose --profile edge up -d
docker compose logs -f cloudflared        # debe mostrar "Registered tunnel connection"
docker compose ps cloudflared             # estado del health check
```

## 4. Proteger las herramientas administrativas con Cloudflare Access

Zero Trust → **Access → Applications → Add → Self-hosted**: dominio `datos.midominio.com`, política *Allow* para la
lista de correos autorizados, método *One-time PIN*. Airflow pide además su propio usuario y contraseña (defensa en
profundidad). Swagger, Trino, RabbitMQ, SeaweedFS y PostgreSQL **no** se publican.

## 5. Reglas recomendadas en Cloudflare

- WAF → Rate limiting: `/api/v1/auth/login` (10/min por IP), `/api/v1/tickets` POST (30/min), `/api/v1/agent/*` (60/min).
- SSL/TLS: modo *Full*; *Always Use HTTPS* activado.

## 6. Verificación desde una red externa (por ejemplo, datos móviles)

- `https://soporte.midominio.com` abre el login con candado válido.
- El asistente pide permiso de micrófono y transcribe un turno de voz.
- Crear un ticket con 2 imágenes de unos 3 MB funciona.
- `https://datos.midominio.com` muestra la pantalla de Cloudflare Access antes de Airflow.
- Un escaneo de puertos a la IP pública del servidor no muestra 5432, 5672, 8333, 8080, 8081 ni 8082.

## Rotación del token y respuesta a una filtración

1. Zero Trust → Tunnels → `helpdesk` → **Refresh token** (invalida el anterior).
2. Actualiza `TUNNEL_TOKEN` en `.env` y ejecuta `docker compose --profile edge up -d cloudflared`.
3. Revisa los registros de Access y de auditoría (`audit_log`) del periodo afectado.

## Contingencia

Si Cloudflare o el túnel fallan, la aplicación sigue disponible en la red local: `http://<ip-del-servidor>:8080`
(sin micrófono remoto, porque no es HTTPS). Para reactivar el túnel: `docker compose restart cloudflared`.

## Plan B sin dominio: Quick Tunnel

```bash
docker run --rm --network helpdesk_edge cloudflare/cloudflared:2026.6.0 tunnel --no-autoupdate --url http://nginx:80
```

Muestra una URL `https://<aleatorio>.trycloudflare.com`. Cambia en cada arranque y no tiene garantía de
disponibilidad: úsala solo para una demostración puntual.

## Diferencias entre local y publicado

| | Local | Publicado |
| --- | --- | --- |
| URL | http://localhost:8080 | https://soporte.midominio.com |
| Cookie `Secure` | `COOKIE_SECURE=false` | `true` |
| Micrófono | Funciona en `localhost` | Funciona (HTTPS) |
| Consolas internas | 127.0.0.1:8081/8082/15672 | No publicadas (Airflow opcional con Access) |

El tráfico de la aplicación transita por la red de Cloudflare como proveedor tercero; esto se declara en la política
de tratamiento de datos del proyecto.
