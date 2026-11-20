# Inteligencia artificial: diseño explicable

## Capa de proveedores

`ai-service/app/providers/`: interfaz `ModelProvider` con `classify_text` y `analyze_image`.
Implementaciones: `HeuristicProvider` (reglas) y `OpenAICompatibleProvider` (OpenAI, Groq, OpenRouter, vLLM u
Ollama). Se elige con `LLM_PROVIDER`; el resto del sistema no cambia.

Salvaguardas del proveedor de modelos:

- El texto del usuario va delimitado (`<<<DATOS>>> … <<<FIN>>>`) y se indica al modelo que lo trate como datos
  (mitigación de inyección de instrucciones).
- La respuesta debe ser JSON; los códigos se validan contra el catálogo real y un código inventado se descarta.
- La categoría se fuerza a la del tipo de falla, para que siempre sean coherentes.
- La confianza solo con texto se limita a 0,90; si el resultado no es concluyente, se limita a 0,40.
- Cualquier error del modelo termina en un diagnóstico no concluyente, es decir, en revisión humana.

## Combinación texto + imagen (`analysis.combine`)

| Caso | Resultado |
| --- | --- |
| Solo texto o solo imagen concluyente | Esa fuente |
| Coinciden en categoría | Confianza = min(0,98; máx + 0,10). El tipo lo aporta la fuente más confiable. |
| Discrepan | Gana la de mayor confianza, multiplicada por 0,7 |
| Ninguna concluyente | Sin categoría, confianza 0, revisión humana |

## Imágenes

Antes de analizarse, las imágenes se vuelven a codificar (`sanitize_image`): se eliminan EXIF y GPS, se
neutralizan archivos políglotas y el lado mayor se limita a 1600 px. El análisis es asíncrono por eventos.

## Agente conversacional (`ai-service/app/agent.py`)

Etapas: PROBLEMA → EQUIPO → DESDE → IMPACTO → (SEDE) → BORRADOR → CREADO. El agente:

- consulta tickets y crea tickets **con el JWT del propio usuario** (respeta sus permisos);
- solo recomienda comprobaciones de la lista blanca `SAFE_CHECKS`;
- presenta las clasificaciones como preliminares y nunca afirma reparaciones;
- crea el ticket solo con el botón «Confirmar» o con una confirmación explícita («sí, confirmo»);
- al terminar envía solo metadatos (turnos, duración, si usó voz, consentimiento), nunca la conversación.

## Voz

faster-whisper (modelo `small`, CPU, int8) para voz a texto; Piper (`es_MX-ald-medium`) para texto a voz. El
audio se procesa en memoria y se descarta. Grabación máxima de 60 s por turno, por HTTP (sin WebSockets), lo que
funciona igual a través de Cloudflare.

## Asignación de técnicos

Ver `helpdesk-api/.../assignment/AssignmentScorer.java` (fórmula, filtros y desempates documentados en el código
y en la sección 11 del documento de la FASE 1). Los pesos y umbrales se editan en **Configuración**.
