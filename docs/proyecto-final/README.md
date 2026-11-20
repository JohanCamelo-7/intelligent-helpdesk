# Proyecto final · Solución de base de datos para el desafío nacional UNIMINUTO

Mesa de ayuda de TI para tres sedes reales de UNIMINUTO (**Bogotá**, **Bello** y **Girardot**), con una base
relacional para la operación, un componente distribuido por eventos y un Data Lake para la analítica nacional.

| Documento | Contenido |
| --- | --- |
| [Informe técnico](informe-tecnico.md) | Resumen, arquitectura, decisiones, **predicción contra observación**, limitaciones y defensa |
| [1. Problema y criterios](01-problema-y-criterios.md) | Problema, afectados, hechos y supuestos, alcance nacional, por qué bases masivas, criterios de éxito |
| [2. Hito 1 · Modelo relacional](02-hito1-modelo-relacional.md) | Diagrama entidad-relación, decisiones del modelo, operación de integridad |
| [3. Hito 2 · Componente distribuido](03-hito2-distribuido.md) | Flujo por eventos, modelo estrella, prueba de caída del broker y lo que revela |
| [4. Hito 3 · Solución integrada](04-hito3-integrado.md) | API, tarea de punta a punta y prueba contra la meta |
| [img/](img/) | Diagramas en PNG para el informe impreso o las diapositivas |

## Dónde está cada requisito

| Requisito del proyecto | Evidencia |
| --- | --- |
| Problema real, afectados, hechos frente a supuestos | [01](01-problema-y-criterios.md) §1.1 |
| Alcance nacional y por qué técnicas de bases masivas | [01](01-problema-y-criterios.md) §1.2 – 1.3 |
| Criterios de éxito medibles definidos antes de probar | [01](01-problema-y-criterios.md) §1.4 |
| **Hito 1:** modelo relacional + diagrama ER + operación de integridad demostrada | [02](02-hito1-modelo-relacional.md), `pruebas/hito1-integridad.sql`, `helpdesk-api/src/main/resources/db/migration/` |
| **Hito 2:** componente distribuido + prueba que revela una consecuencia | [03](03-hito2-distribuido.md), `pruebas/hito2-prueba-distribuida.sh`, `data-lake/`, `data-ingestion/` |
| **Hito 3:** solución integrada (API / flujo) + tarea de punta a punta + prueba contra la meta | [04](04-hito3-integrado.md), `pruebas/hito3-flujo-integrado.sh`, `helpdesk-api/`, `frontend/` |
| Diagrama de arquitectura | [Informe](informe-tecnico.md) §3, [img/arquitectura.png](img/arquitectura.png) |
| Tabla predicho contra observado | [Informe](informe-tecnico.md) §5 |
| Repositorio en GitHub | Este repositorio |

## Orden para preparar la entrega

1. Reiniciar y regenerar los datos (una sola vez): ver «Cómo reproducir» en el [informe](informe-tecnico.md) §8.
2. Correr las tres pruebas y copiar los valores de `pruebas/resultados/` en la tabla del informe (§5).
3. Revisar que los ✅ y ❌ del informe coincidan con lo observado. Si algo no cumple, se deja así y se explica la
   causa: también es un resultado.
