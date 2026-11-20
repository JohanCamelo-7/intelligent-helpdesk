# FASE 6. Frontend Angular

## Qué se construyó

Angular 22 + Angular Material, con componentes standalone y signals. Pantallas: login, mis tickets, nuevo ticket
(con imágenes), detalle, asistente de IA, panel del técnico, panel operativo del administrador, personas (usuarios
y técnicos), configuración, analítica y reportes. Las rutas se protegen por rol y el interceptor renueva el token
automáticamente. Las fuentes son locales (sin CDN), por la política CSP.

## Rutas

- `frontend/src/app/pages/`
- `frontend/src/app/core/`
- `frontend/src/app/shared/chart.ts`
- `frontend/Dockerfile`

## Cómo ejecutar

```bash
cd frontend && npm ci && npx ng serve    # desarrollo, con proxy a localhost:8080
# o en producción: docker compose up -d --build nginx
```

## Cómo probar

`npx ng build` sin errores; `npx ng test --watch=false`; recorrido por cada rol.
