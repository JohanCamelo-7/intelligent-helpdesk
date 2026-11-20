# FASE 10. Asignación automática de técnicos

## Qué se construyó

Puntaje S = 0,40·E + 0,20·X + 0,25·(1 − C/Cmax) + 0,15·D (especialidad, experiencia, carga y desempeño histórico
desde Gold; en ALTA o CRÍTICA se intercambian los pesos de X y C). Filtros: técnico activo, disponible, que atienda la
categoría y no esté en su carga máxima; un JUNIOR no recibe tickets CRÍTICOS. La asignación es automática solo con
confianza de IA ≥ 0,75 y puntaje ≥ 0,60; si no, el administrador ve las recomendaciones y asigna o reasigna con motivo.

## Rutas

- `helpdesk-api/src/main/java/com/helpdesk/api/assignment/AssignmentScorer.java`
- `helpdesk-api/src/main/java/com/helpdesk/api/assignment/AssignmentService.java`
- `helpdesk-api/src/main/java/com/helpdesk/api/assignment/PerformanceProvider.java`

## Cómo ejecutar

```bash
cd helpdesk-api && mvn test -Dtest=AssignmentScorerTest
```

## Cómo probar

`AssignmentScorerTest` (7 casos). En la interfaz: detalle del ticket → Asignar → ver ranking y motivo.
