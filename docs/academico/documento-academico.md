# Plataforma inteligente de soporte técnico con agente de IA por voz, visión artificial y arquitectura Data Lake

*Borrador del documento académico. Las secciones marcadas **[Completar]** requieren datos del equipo (institución,
autores, mediciones propias). No se incluyen resultados experimentales que no se hayan medido.*

## Resumen

Se presenta el diseño y la implementación de una plataforma web de mesa de ayuda en la que los usuarios reportan
fallas por texto, imágenes o voz. Un servicio de inteligencia artificial clasifica la solicitud, propone el tipo de
falla y recomienda o asigna un técnico según su especialidad, experiencia, carga y desempeño. Cada operación genera
un evento de dominio que alimenta un Data Lake con arquitectura Medallion (Bronze, Silver, Gold) sobre Apache
Iceberg, del cual se derivan los indicadores y reportes. La plataforma se ejecuta con Docker Compose y se publica en
Internet por HTTPS mediante Cloudflare Tunnel, sin abrir puertos entrantes. **[Completar: resultados de la
evaluación cuando se midan.]**

**Palabras clave:** mesa de ayuda, clasificación de incidentes, asignación de técnicos, lakehouse, Apache Iceberg,
arquitectura orientada a eventos, agentes conversacionales.

## 1. Introducción

### 1.1 Problema
En muchas organizaciones, el reporte de fallas técnicas depende de llamadas o mensajes informales. La información
llega incompleta, la clasificación y la asignación son manuales y no quedan datos confiables para medir tiempos,
cargas o fallas recurrentes. **[Completar: caso concreto de la organización estudiada.]**

### 1.2 Objetivo general
Construir una plataforma que reciba, clasifique y asigne solicitudes de soporte con apoyo de IA, y que conserve la
trazabilidad completa de cada solicitud en un Data Lake para su análisis.

### 1.3 Objetivos específicos
1. Registrar solicitudes por formulario, imágenes y conversación por voz.
2. Clasificar automáticamente la categoría y el tipo de falla, con un nivel de confianza explícito.
3. Recomendar o asignar técnicos con un modelo de puntaje explicable.
4. Implementar ingesta, calidad y transformación de datos en capas Bronze, Silver y Gold.
5. Ofrecer tableros e informes basados en la capa Gold.
6. Desplegar la solución de forma reproducible y publicarla de forma segura.

### 1.4 Alcance
| Estado | Elementos |
| --- | --- |
| **Implementado** | Autenticación y roles; tickets con máquina de estados; carga y validación de imágenes; clasificación (heurística o por modelo de lenguaje compatible con OpenAI u Ollama); agente conversacional con voz; asignación por puntaje; outbox y RabbitMQ; ingesta a Landing; Bronze, Silver y Gold con reglas de calidad y cuarentena; Trino; tableros y reportes Excel/PDF; generador de datos de demostración; Docker Compose; Cloudflare Tunnel |
| **Prototipo** | Análisis de imágenes (depende de un modelo de visión externo o local y no se entrenó un modelo propio); voz (modelos genéricos sin ajuste al dominio) |
| **Propuesto (trabajo futuro)** | Modelo de clasificación entrenado con los tickets históricos; separación en microservicios independientes; firma RS256 con varios emisores; alertas proactivas por fallas recurrentes |

## 2. Marco teórico

- **Gestión de servicios de TI.** El proceso de gestión de incidentes busca restablecer el servicio lo antes posible.
  La plataforma implementa su registro, categorización, priorización, asignación y cierre (AXELOS, 2019).
- **Arquitectura lakehouse y Medallion.** Combina el almacenamiento económico de un data lake con transacciones y
  esquemas propios de un data warehouse (Armbrust et al., 2021). Las capas Bronze, Silver y Gold organizan los datos
  según su grado de refinamiento.
- **Formatos de tabla abiertos.** Apache Iceberg aporta transacciones ACID, evolución del esquema y viajes en el
  tiempo sobre archivos Parquet (Apache Software Foundation, s. f.-a).
- **Arquitectura orientada a eventos y patrón outbox.** El patrón outbox transaccional garantiza que el cambio de
  estado y el evento se registren juntos, y los consumidores idempotentes toleran la entrega "al menos una vez"
  (Richardson, 2018).
- **Modelos de lenguaje como clasificadores.** Los modelos de lenguaje permiten clasificar texto sin entrenamiento
  específico, con riesgos conocidos (respuestas inventadas e inyección de instrucciones) que el diseño mitiga con
  validación contra un catálogo cerrado y umbrales de confianza.

## 3. Metodología

Desarrollo incremental en 18 fases (`docs/fases/`), cada una con entregables, comandos de ejecución y pruebas.
Las decisiones de diseño y sus justificaciones están en `docs/arquitectura/decisiones.md`.

## 4. Diseño de la solución

### 4.1 Arquitectura
Frontend Angular servido por Nginx; servicio de negocio Spring Boot (módulos de identidad, tickets, técnicos,
asignación, notificaciones y analítica); servicio de IA en FastAPI; RabbitMQ; PostgreSQL; SeaweedFS (compatible con
S3); Spark e Iceberg orquestados por Airflow; Trino para las consultas; Cloudflare Tunnel para la publicación.

### 4.2 Clasificación y prioridad
La IA propone la categoría y el tipo de falla con una confianza. La combinación de texto e imagen sigue reglas
explícitas (`docs/arquitectura/ia.md`). La prioridad **no** la decide la IA: se calcula por reglas de impacto y
bloqueo (`docs/arquitectura/reglas-de-estado.md`).

### 4.3 Modelo de asignación
S = 0,40·E + 0,20·X + 0,25·(1 − C/Cmax) + 0,15·D, donde E es la afinidad de especialidad, X la experiencia, C la carga
actual y D el desempeño histórico obtenido de Gold. En prioridad ALTA o CRÍTICA se intercambian los pesos de X y C.
La asignación es automática solo si la confianza de la IA es ≥ 0,75 y S ≥ 0,60; en otro caso decide una persona.
Cada asignación guarda el puntaje, el motivo y la versión de los pesos, lo que permite auditarla.

### 4.4 Data Lake
Landing (JSONL) → Bronze (eventos inmutables, deduplicados) → Silver (entidades limpias, con validación de calidad
y cuarentena) → Gold (esquema estrella). El gobierno de datos incluye un catálogo con dueño y grano por tabla,
el linaje por ejecución, los resultados de calidad y las reglas de contabilización (`docs/datos/`).

### 4.5 Seguridad y privacidad
Control de acceso por rol y por propiedad del ticket; los eventos no llevan datos personales; las imágenes se
re-codifican sin metadatos; el audio no se almacena; el agente solo crea tickets con confirmación explícita; no hay
puertos entrantes y Cloudflare Access protege las consolas (`docs/fases/FASE-17-pruebas.md`).

## 5. Resultados

### 5.1 Verificación funcional realizada
Las pruebas automatizadas y lo que se verificó en el entorno de construcción se detallan en
`docs/fases/FASE-17-pruebas.md`. En resumen: las pruebas del servicio de IA, de la ingesta y del pipeline Spark
(Landing a Gold) se ejecutaron con éxito, y la compilación de producción del frontend no presentó errores.

### 5.2 Evaluación pendiente **[Completar con mediciones propias]**
Medidas propuestas, sin valores todavía:

| Indicador | Cómo medirlo |
| --- | --- |
| Exactitud de la clasificación | Tipo propuesto por la IA vs. tipo confirmado por el técnico (`gold` → acierto de IA), por proveedor |
| Tasa de asignación automática efectiva | Definición en `docs/datos/reglas-de-contabilizacion.md` |
| Tiempo hasta la asignación | Antes y después de implantar la plataforma |
| Usabilidad del agente por voz | Cuestionario SUS a usuarios piloto |
| Latencia | Tiempo desde la creación del ticket hasta el diagnóstico |

Los datos del generador de demostración son sintéticos y **no** deben presentarse como resultados.

## 6. Discusión y limitaciones

- El proveedor heurístico limita su confianza a 0,60, así que todos sus tickets pasan por revisión humana. La calidad
  real depende del modelo de lenguaje que se configure.
- El análisis de imágenes depende de un modelo externo o local; no se entrenó ni se evaluó un modelo propio.
- Los modelos de voz son genéricos; el ruido y los acentos pueden afectar la transcripción.
- Se eligió un único servicio Java modular en lugar de varios microservicios. Esto reduce la complejidad operativa
  a cambio de escalar los módulos en conjunto.
- El tráfico pasa por un proveedor tercero (Cloudflare), lo que debe declararse en la política de datos.

## 7. Conclusiones **[Completar tras la evaluación]**

## Referencias

- Apache Software Foundation. (s. f.-a). *Apache Iceberg documentation*. https://iceberg.apache.org/docs/latest/
- Apache Software Foundation. (s. f.-b). *Apache Airflow documentation*. https://airflow.apache.org/docs/
- Armbrust, M., Ghodsi, A., Xin, R., & Zaharia, M. (2021). Lakehouse: A new generation of open platforms that unify
  data warehousing and advanced analytics. En *Proceedings of the Conference on Innovative Data Systems Research (CIDR)*.
- AXELOS. (2019). *ITIL Foundation: ITIL 4 edition*. TSO.
- Cloudflare. (s. f.). *Cloudflare Tunnel*. https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/
- Richardson, C. (2018). *Microservices patterns*. Manning.
- Trino Software Foundation. (s. f.). *Trino documentation*. https://trino.io/docs/current/

*Comprueba cada referencia en la fuente original antes de entregar el documento y agrega las que use tu institución.*
