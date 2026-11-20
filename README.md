# Plataforma Inteligente de Soporte Técnico

Sistema web para reportar fallas por **texto, imagen o voz**. La IA clasifica la solicitud, propone la falla y
asigna al técnico adecuado. Cada operación genera un evento que llega a un **Data Lake** (Bronze → Silver →
Gold) del que salen los tableros y reportes. La aplicación se publica en Internet con **Cloudflare Tunnel**.

> **Proyecto final · Desafío nacional UNIMINUTO.** El sistema modela el soporte de TI de tres sedes reales de
> UNIMINUTO: **Bogotá** (Sede Principal Minuto de Dios), **Bello** (Rectoría Antioquia - Chocó) y **Girardot**
> (Centro Regional). Informe técnico, hitos y pruebas: [docs/proyecto-final/](docs/proyecto-final/README.md).

| Componente | Tecnología | Carpeta |
| --- | --- | --- |
| Frontend | Angular 22 + Angular Material (servido por Nginx) | `frontend/` |
| Negocio (identidad, tickets, técnicos, asignación, notificaciones, analítica, reportes) | Java 21 + Spring Boot 4.1 | `helpdesk-api/` |
| IA: clasificación, visión, agente y voz | Python 3.12 + FastAPI, faster-whisper, Piper | `ai-service/` |
| Ingesta de eventos a Landing | Python + RabbitMQ | `data-ingestion/` |
| Data Lake: ETL, calidad, orquestación | Apache Spark 4.1 + Apache Iceberg 1.11 + Airflow 3.3 | `data-lake/` |
| Consultas SQL sobre Gold | Trino | `infrastructure/trino/` |
| Transaccional / mensajería / objetos | PostgreSQL 17 / RabbitMQ 4.4 / SeaweedFS (S3) | `infrastructure/` |
| Publicación | Nginx + Cloudflare Tunnel | `infrastructure/nginx`, `infrastructure/cloudflared` |

## Requisitos

- Docker Engine con Docker Compose v2 (en Windows: Docker Desktop con WSL2).
- 16 GB de RAM como mínimo (32 GB recomendado) y 60 GB de disco libres.
- Internet durante la primera construcción (descarga imágenes, dependencias de Maven/npm/pip, librerías de
  Iceberg y, la primera vez que se usa la voz, los modelos de Whisper y Piper).

## Puesta en marcha (local)

Con Docker Desktop abierto, en Git Bash (Windows), WSL, Linux o macOS:

```bash
cd intelligent-helpdesk
./scripts/iniciar.sh
```

El script hace todo: crea `.env` con contraseñas aleatorias (si no existe), construye las imágenes, inicia los
servicios, espera a que respondan, ejecuta `scripts/verificar.sh` y muestra el usuario y la contraseña del
administrador. La primera vez tarda entre 10 y 20 minutos.

> **El archivo `.env` guarda tus contraseñas.** El zip del proyecto no lo incluye. Al actualizar el proyecto,
> conserva tu `.env` en la carpeta. Si lo pierdes, `iniciar.sh` crea uno nuevo y te pregunta si borra la base de
> datos anterior (que quedó con las contraseñas viejas).

Comandos manuales equivalentes: `./scripts/preparar-env.sh` (o `scripts\preparar-env.ps1` en PowerShell),
`docker compose up -d --build` y `./scripts/verificar.sh`.

Abre **http://localhost:8080** e ingresa con `ADMIN_EMAIL` / `ADMIN_PASSWORD` del `.env`.

### Primer recorrido sugerido

1. **Configuración → Escenario de demostración → Generar escenario** (60 días, 150 tickets). Crea técnicos,
   usuarios y tickets históricos marcados como *demostración*.
2. **Configuración → Pipeline del Data Lake → Ejecutar ahora** (o espera la ejecución horaria). En 2 a 5 minutos
   se llenan Bronze, Silver y Gold.
3. **Analítica**: tableros con datos reales del lago (filtros por fecha, sede, categoría, técnico, prioridad y estado).
4. **Asistente de IA**: reporta una falla por voz o texto; el ticket se crea solo cuando confirmas.
5. Entra como técnico (`tecnico1@demo.helpdesk.local` / `Demo1234!`) para atender tickets asignados.

### Consolas internas (solo en el equipo local)

| Herramienta | URL | Usuario |
| --- | --- | --- |
| Swagger (API de negocio) | http://localhost:8080/api/v1/swagger | JWT del login |
| API del agente | http://localhost:8080/api/v1/agent/docs | JWT del login |
| Airflow | http://localhost:8081 | `admin` / `AIRFLOW_ADMIN_PASSWORD` |
| Trino | http://localhost:8082 | `admin` |
| RabbitMQ | http://localhost:15672 | `RABBITMQ_USER` |

## Inteligencia artificial

**Preguntar al Data Lake** (menú del administrador): preguntas en español sobre la capa Gold, con respuesta, tabla,
gráfica y el SQL usado. Con `LLM_PROVIDER=heuristic` funciona por plantillas; con `openai` u `ollama` el modelo genera
y corrige el SQL. Detalle y seguridad: [docs/arquitectura/agente-data-lake.md](docs/arquitectura/agente-data-lake.md).

`LLM_PROVIDER` en `.env`:

- `heuristic` (por defecto): reglas por palabras clave. Funciona sin GPU ni internet. Nunca supera 0,60 de
  confianza, así que todos los tickets pasan por revisión del administrador. No analiza imágenes.
- `openai`: cualquier API compatible con OpenAI (`LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `VISION_MODEL`).
  Se envía solo la descripción y la imagen sin metadatos; nunca nombres ni correos.
- `ollama`: modelo local. `docker compose --profile ollama up -d`, luego
  `docker compose exec ollama ollama pull gemma3:4b` y define `LLM_MODEL=gemma3:4b`.

La voz (faster-whisper y Piper) corre en el servidor. Si los modelos no están disponibles, el navegador usa su
propia síntesis de voz y el usuario puede escribir.

## Publicación en Internet (Cloudflare Tunnel)

Guía completa: [docs/despliegue/cloudflare-tunnel.md](docs/despliegue/cloudflare-tunnel.md). En resumen:

```bash
# 1. Crea el túnel en Cloudflare Zero Trust y copia el token en TUNNEL_TOKEN (.env)
# 2. Pon COOKIE_SECURE=true y tu dominio en CORS_ORIGINS
docker compose --profile edge up -d
```

## Pruebas

```bash
# Backend Java (unitarias)                 # Integración con PostgreSQL real (requiere Docker)
cd helpdesk-api && mvn test                 mvn verify -Pintegration
# Servicio de IA y de ingesta
cd ai-service && pip install -r requirements-dev.txt && pytest
cd data-ingestion && pytest
# Data Lake (Spark local sobre Parquet: Landing -> Bronze -> Silver -> Gold)
cd data-lake && pip install -r requirements-dev.txt && pytest
# Frontend
cd frontend && npm ci && npx ng test --watch=false
```

Pruebas de los hitos del proyecto final (con todo el sistema corriendo):

```bash
docker compose exec -T postgres psql -U postgres -d helpdesk -v ON_ERROR_STOP=1 < pruebas/hito1-integridad.sql
./pruebas/hito2-prueba-distribuida.sh      # caída del broker y consistencia eventual
./pruebas/hito3-flujo-integrado.sh         # tarea de punta a punta por la API
```

Qué se verificó realmente al construir este entregable está en
[docs/fases/FASE-17-pruebas.md](docs/fases/FASE-17-pruebas.md).

## Documentación

- **Proyecto final UNIMINUTO (informe técnico e hitos): [docs/proyecto-final/](docs/proyecto-final/README.md)**
- **Agente de IA del Data Lake (preguntas en español sobre Gold): [docs/arquitectura/agente-data-lake.md](docs/arquitectura/agente-data-lake.md)**
- Fases del proyecto: [docs/fases/](docs/fases/)
- Documento académico: [docs/academico/documento-academico.md](docs/academico/documento-academico.md)
- Arquitectura, reglas y contratos: [docs/arquitectura/](docs/arquitectura/), [contracts/events/eventos.md](contracts/events/eventos.md)
- Diccionario de datos (generado): [docs/datos/diccionario-de-datos.md](docs/datos/diccionario-de-datos.md)
- Prueba de carga del Data Lake (30.000 tickets, 100 usuarios, 25 técnicos): [docs/datos/carga-masiva.md](docs/datos/carga-masiva.md)

## Respaldo y recuperación

```bash
./infrastructure/backup/backup.sh            # PostgreSQL (3 bases) + objetos de SeaweedFS
./infrastructure/backup/restore.sh backups/AAAAMMDD-HHMMSS
```
