# FASE 9. Agente de IA con voz

## Qué se construyó

Asistente conversacional que guía el reporte (problema, equipo, desde cuándo, impacto, sede), sugiere solo
comprobaciones seguras, consulta el estado de los tickets y crea el ticket únicamente tras confirmación explícita,
con el JWT del propio usuario. La voz usa faster-whisper (voz a texto) y Piper (texto a voz); si no están
disponibles, usa la síntesis del navegador. El audio no se almacena.

## Rutas

- `ai-service/app/agent.py`
- `ai-service/app/voice.py`
- `frontend/src/app/pages/agent.ts`
- `helpdesk-api/src/main/java/com/helpdesk/api/agent/AgenteController.java`

## Cómo ejecutar

```bash
docker compose up -d ai-service   # los modelos de voz se descargan la primera vez que se usan
```

## Cómo probar

`pytest tests/test_agente.py`. En la interfaz: Asistente de IA → mantener pulsado el micrófono → describir la falla → Confirmar.

## Notas

El micrófono solo funciona en `localhost` o por HTTPS (túnel).
