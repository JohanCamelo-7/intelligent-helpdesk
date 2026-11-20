# FASE 4. Backend, autenticación y usuarios

## Qué se construyó

Servicio Spring Boot 4.1 (Java 21). Login con JWT de acceso (15 min, HS256) y token de refresco rotativo en una cookie
HttpOnly `hd_refresh`. Roles ADMIN, TECNICO y USUARIO. Bloqueo tras intentos fallidos. El administrador inicial se crea
desde `.env`. El módulo de usuarios ofrece CRUD para el ADMIN, y toda acción sensible queda en `audit_log`.

## Rutas

- `helpdesk-api/src/main/java/com/helpdesk/api/config/SecurityConfig.java`
- `helpdesk-api/src/main/java/com/helpdesk/api/config/JwtConfig.java`
- `helpdesk-api/src/main/java/com/helpdesk/api/identity/`
- `helpdesk-api/src/main/java/com/helpdesk/api/shared/AuditService.java`

## Cómo ejecutar

```bash
docker compose up -d --build helpdesk-api
```

## Cómo probar

Swagger en http://localhost:8080/api/v1/swagger → `POST /api/v1/auth/login` con ADMIN_EMAIL/ADMIN_PASSWORD; `GET /api/v1/tickets` sin token debe responder 401.
