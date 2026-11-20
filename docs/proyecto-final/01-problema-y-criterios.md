# 1. Problema, alcance nacional y criterios de éxito

## 1.1 El problema

UNIMINUTO opera como un sistema universitario con sedes en muchas regiones de Colombia. En este proyecto el soporte
técnico de TI se modela en **tres sedes reales**:

| Código | Sede | Ciudad | Rectoría / regional |
| --- | --- | --- | --- |
| `SEDE_BOGOTA` | UNIMINUTO Sede Principal Bogotá - Minuto de Dios (Engativá) | Bogotá | Sede principal |
| `SEDE_BELLO` | UNIMINUTO Sede Bello | Bello (área metropolitana de Medellín) | Antioquia - Chocó |
| `SEDE_GIRARDOT` | UNIMINUTO Centro Regional Girardot | Girardot | Cundinamarca - Boyacá |

Cuando un docente, un administrativo o un estudiante monitor tiene una falla (un computador que no enciende, una
impresora atascada, sin internet en un bloque, una cuenta bloqueada), el reporte suele llegar por llamada, correo o
mensaje. Eso produce tres problemas que este proyecto ataca:

1. **Información incompleta y sin estructura.** Falta la sede, el equipo, el impacto; no se puede priorizar.
2. **Asignación manual y desigual.** Se le asigna al técnico que contesta, no al que tiene la especialidad y la carga
   adecuadas; las sedes pequeñas dependen de pocas personas.
3. **Sin visión nacional.** Cada sede conoce sus casos, pero nadie puede responder con datos preguntas como *¿qué
   sede tarda más en resolver?*, *¿qué fallas se repiten?* o *¿cuánta carga tiene cada técnico?*.

**Afectados:** usuarios que reportan (pierden horas de clase o trabajo), técnicos (carga mal repartida),
coordinadores de sede y la coordinación nacional de TI (deciden sin datos).

### Hechos documentados y supuestos

| Tipo | Afirmación | Fuente |
| --- | --- | --- |
| Hecho | UNIMINUTO tiene sede en Bogotá (Minuto de Dios, Engativá), en Bello (Rectoría Antioquia - Chocó) y un centro regional en Girardot | uniminuto.edu, «Encuentra tu sede» |
| Hecho | UNIMINUTO no tiene una sede dentro del municipio de Medellín; Bello es la sede del área metropolitana | uniminuto.edu |
| Supuesto | Reparto de la demanda: 55 % Bogotá, 30 % Bello, 15 % Girardot | Proporción elegida por tamaño relativo; **no** son cifras oficiales |
| Supuesto | 100 usuarios, 25 técnicos y 2 administradores; 30.000 tickets entre ene-2024 y nov-2026 | Escenario de prueba de carga, no datos de la institución |
| Supuesto | Más tickets los lunes, picos de 8–12 y 14–17, menos en diciembre y festivos | Patrón típico de una mesa de ayuda; se puede ajustar en `DemoService` |
| Supuesto | Clasificación por IA con 72–90 % de acierto | **Simulado** en los datos de demostración |

Todos los datos generados quedan marcados `es_demo = true` y canal `DEMO`. No se presentan como datos reales de
UNIMINUTO.

## 1.2 Por qué es un problema de alcance nacional

- La solución debe servir igual a una sede grande (Bogotá) y a una pequeña (Girardot), y a sedes que se agreguen
  después. Por eso **la sede es un dato, no código**: agregar una sede nueva es una fila en `sedes`.
- La coordinación nacional necesita **comparar sedes con las mismas definiciones** (qué es «resuelto», cómo se mide
  el tiempo). Eso exige un modelo de datos común y reglas de cálculo únicas
  (`docs/datos/reglas-de-contabilizacion.md`).
- Las sedes operan al mismo tiempo; una falla en el componente que alimenta la analítica nacional **no puede detener
  la atención en ninguna sede**.

## 1.3 Por qué se necesitan técnicas de bases de datos masivas

Con solo tres sedes, el volumen ya supera lo que conviene resolver con una única base transaccional:

| Dato | Volumen en la prueba |
| --- | --- |
| Tickets (2024 – 2026) | 30.000 |
| Eventos de dominio (cada cambio de un ticket) | ≈ 300.000 – 400.000 |
| Filas escritas en la capa Gold por ejecución | ≈ 290.000 |

- **Separar operación y análisis.** Las consultas analíticas recorren años de historia; correrlas sobre PostgreSQL
  competiría con el registro de tickets. Se separa en una base transaccional (OLTP) y un lago de datos (OLAP).
- **Almacenamiento columnar y particionado.** Iceberg sobre Parquet, particionado por fecha, permite leer solo las
  columnas y los días necesarios.
- **Procesamiento distribuido.** Spark transforma cientos de miles de eventos en minutos; Trino consulta en paralelo.
- **Integración por eventos.** El patrón outbox y RabbitMQ desacoplan las sedes del lago, con entrega «al menos una
  vez» e idempotencia por `event_id`.

Con 30 o 50 sedes el volumen crece de forma lineal; la arquitectura ya está preparada para eso (ver la sección de
defensa del informe técnico).

## 1.4 Criterios de éxito (definidos antes de las pruebas)

Estas metas se fijaron **antes** de ejecutar las pruebas. Los scripts de `pruebas/` miden cada una y escriben el
resultado en `pruebas/resultados/`.

| # | Criterio | Meta | Cómo se mide | Hito |
| --- | --- | --- | --- | --- |
| C1 | Ningún dato inválido entra a la base | 7 de 7 intentos inválidos rechazados (pruebas 2 a 8) | `pruebas/hito1-integridad.sql` | 1 |
| C2 | Registro de un ticket atómico | Si falla el evento, 0 tickets guardados | `pruebas/hito1-integridad.sql` | 1 |
| C3 | Integridad referencial en los datos cargados | 0 huérfanos | `pruebas/hito1-integridad.sql` | 1 |
| C4 | Las sedes siguen operando si cae el broker | 100 % de tickets registrados, < 2 s por registro | `pruebas/hito2-prueba-distribuida.sh` | 2 |
| C5 | Ningún evento se pierde ni se duplica | Bronze = outbox; 0 `event_id` repetidos | `pruebas/hito2-prueba-distribuida.sh` | 2 |
| C6 | Recuperación tras la caída | Outbox vacío < 60 s después de volver el broker | `pruebas/hito2-prueba-distribuida.sh` | 2 |
| C7 | Consistencia final entre operación y lago | Tickets en PostgreSQL = tickets en Gold, por sede | `pruebas/hito2-prueba-distribuida.sh` | 2 |
| C8 | Calidad de datos en el pipeline | Rechazos < 5 % por tabla | `ops.pipeline_runs`, `ops.dq_results` | 2 |
| C9 | Tarea de punta a punta | Del reporte a verse en la analítica nacional en < 15 min | `pruebas/hito3-flujo-integrado.sh` | 3 |
| C10 | Respuesta de la API | Registro < 2 s; consulta analítica por sede < 3 s | `pruebas/hito3-flujo-integrado.sh` | 3 |
| C11 | Pipeline con 30.000 tickets | Completo en < 10 min | Airflow / `ops.pipeline_runs` | 2 |
