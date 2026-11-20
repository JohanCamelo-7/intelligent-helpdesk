# FASE 8. Servicio de visión artificial y clasificación de fallas

## Qué se construyó

Servicio FastAPI que consume `ticket.created` y `attachment.uploaded`, obtiene el contexto por un endpoint interno,
limpia las imágenes (elimina EXIF), clasifica el texto y analiza las imágenes con el proveedor elegido (heurístico,
OpenAI compatible u Ollama), combina los resultados y publica `AiDiagnosisCompleted`. El backend aplica la
clasificación, ajusta la prioridad y decide si la asignación es automática o pasa a revisión humana.

## Rutas

- `ai-service/app/analysis.py`
- `ai-service/app/providers/`
- `ai-service/app/taxonomy.py`
- `helpdesk-api/src/main/java/com/helpdesk/api/ticket/AiResultListener.java`
- `docs/arquitectura/ia.md`

## Cómo ejecutar

```bash
docker compose up -d --build ai-service
```

## Cómo probar

`cd ai-service && pytest` (clasificación, combinación, saneamiento de imágenes, idempotencia).
