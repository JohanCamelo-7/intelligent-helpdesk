#!/usr/bin/env bash
# =====================================================================
# HITO 3 · Tarea de punta a punta sobre la solución integrada
#
# Historia de usuario: una docente de UNIMINUTO Girardot reporta que la impresora de la sala de
# profesores no imprime. El sistema clasifica el caso, lo asigna a un técnico, el técnico lo atiende
# y lo cierra, la docente califica la atención y la coordinación nacional ve el caso en la analítica.
#
# Todo pasa por la API real (la misma que usa la aplicación web), y se mide contra las metas
# definidas antes de la prueba en docs/proyecto-final/01-problema-y-criterios.md.
#
# Requisito: haber generado el escenario de demostración (usa una cuenta usuarioN@demo.helpdesk.local
# de Girardot y la cuenta del técnico asignado, con la contraseña de demostración).
#
# Uso (Git Bash, desde la carpeta del proyecto):   ./pruebas/hito3-flujo-integrado.sh
# =====================================================================
. "$(dirname "$0")/lib.sh"
SALIDA="$RESULTADOS/hito3-$(date +%Y%m%d-%H%M).md"
verificar_servicios
declare -A T   # marcas de tiempo (ms)

paso "1. La docente de Girardot inicia sesión"
USUARIO=$(sql "SELECT u.email FROM usuarios u JOIN sedes s ON s.id = u.sede_id
               WHERE s.codigo = 'SEDE_GIRARDOT' AND u.rol = 'USER' AND u.activo AND u.email LIKE '%@demo.helpdesk.local'
               ORDER BY u.email LIMIT 1" | tr -d '\r')
[ -n "$USUARIO" ] || { echo "No hay usuarios de demostración en Girardot. Genera primero el escenario (Configuración → Escenario de demostración)."; exit 1; }
TOKEN_USR=$(login "$USUARIO" "$DEMO_PASSWORD")
[ -n "$TOKEN_USR" ] || { echo "No se pudo iniciar sesión con $USUARIO (contraseña de demostración: DEMO_PASSWORD)"; exit 1; }
TOKEN_ADMIN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD")
[ -n "$TOKEN_ADMIN" ] || { echo "No se pudo iniciar sesión como administrador ($ADMIN_EMAIL)"; exit 1; }
ok "Sesión iniciada: $USUARIO"

paso "2. Reporta la falla"
SEDE=$(sql "SELECT id FROM sedes WHERE codigo = 'SEDE_GIRARDOT'" | tr -d '\r')
EQUIPO=$(sql "SELECT id FROM equipos e WHERE e.sede_id = $SEDE AND e.tipo = 'IMPRESORA' ORDER BY id LIMIT 1" | tr -d '\r')
DESC="[PRUEBA HITO 3] La impresora de la sala de profesores no imprime: aparece atasco de papel aunque la bandeja está vacía y bloquea la entrega de notas"
T[inicio]=$(ahora_ms)
llamar POST /tickets "$TOKEN_USR" "{\"descripcion\":\"$DESC\",\"sedeId\":$SEDE,\"equipoId\":${EQUIPO:-null},\"impacto\":\"AREA\",\"bloqueaTrabajo\":true}"
[ "$CODIGO" = "201" ] || { echo "La API respondió $CODIGO: $CUERPO"; exit 1; }
T[creado]=$(ahora_ms); LAT_CREAR=$TIEMPO
ID=$(echo "$CUERPO" | json_str id); NUM=$(echo "$CUERPO" | json_str numero)
ok "Ticket $NUM creado en $LAT_CREAR s"

paso "3. El sistema lo clasifica y lo asigna"
clasificado() { [ -n "$(sql "SELECT 1 FROM tickets WHERE id = '$ID' AND categoria_id IS NOT NULL" | tr -d '\r')" ]; }
esperar 180 "la clasificación por IA" clasificado && T[clasificado]=$(ahora_ms)
asignado() { [ -n "$(sql "SELECT 1 FROM tickets WHERE id = '$ID' AND tecnico_id IS NOT NULL" | tr -d '\r')" ]; }
if esperar 60 "la asignación automática" asignado; then MODO="automática"
else
  MODO="manual (por el administrador)"
  REC=$(sql "SELECT coalesce(tecnico_recomendado_id::text, '') FROM tickets WHERE id = '$ID'" | tr -d '\r')
  [ -n "$REC" ] || REC=$(sql "SELECT usuario_id FROM tecnicos ORDER BY usuario_id LIMIT 1" | tr -d '\r')
  llamar POST "/tickets/$ID/asignacion" "$TOKEN_ADMIN" "{\"tecnicoId\":\"$REC\",\"motivo\":\"Asignación de la prueba del hito 3\"}"
fi
T[asignado]=$(ahora_ms)
IFS='|' read -r CAT TIPO PRIO TEC_EMAIL TEC_ID <<<"$(sql "SELECT c.codigo, coalesce(f.codigo, ''), t.prioridad_codigo, u.email, u.id
  FROM tickets t JOIN categorias c ON c.id = t.categoria_id LEFT JOIN tipos_falla f ON f.id = t.tipo_falla_id
  JOIN usuarios u ON u.id = t.tecnico_id WHERE t.id = '$ID'" | tr -d '\r')"
ok "Categoría $CAT · tipo ${TIPO:-sin definir} · prioridad $PRIO · asignación $MODO a $TEC_EMAIL"

paso "4. El técnico lo atiende y lo cierra"
TOKEN_TEC=$(login "$TEC_EMAIL" "$DEMO_PASSWORD")
if [ -z "$TOKEN_TEC" ]; then info "El técnico no es una cuenta de demostración; actúa el administrador"; TOKEN_TEC=$TOKEN_ADMIN; fi
llamar POST "/tickets/$ID/estado" "$TOKEN_TEC" '{"estado":"EN_CURSO","comentario":"Reviso la impresora en sitio"}'
[ "$CODIGO" = "200" ] || { echo "No se pudo iniciar la atención ($CODIGO): $CUERPO"; exit 1; }
T[en_curso]=$(ahora_ms)
llamar POST "/tickets/$ID/actividades" "$TOKEN_TEC" '{"tipo":"REPARACION","descripcion":"Se retiró un trozo de papel del rodillo de alimentación y se limpió el sensor","minutos":25}'
ACT=$CODIGO
[ -n "$TIPO" ] || TIPO=$(sql "SELECT f.codigo FROM tipos_falla f JOIN categorias c ON c.id = f.categoria_id WHERE c.codigo = '$CAT' ORDER BY f.id LIMIT 1" | tr -d '\r')
llamar POST "/tickets/$ID/estado" "$TOKEN_TEC" "{\"estado\":\"FINALIZADA\",\"solucion\":\"Se retiró papel atascado en el rodillo y se limpió el sensor de bandeja; impresión de prueba correcta\",\"tipoFallaFinalCodigo\":\"$TIPO\"}"
[ "$CODIGO" = "200" ] || { echo "No se pudo cerrar el ticket ($CODIGO): $CUERPO"; exit 1; }
T[cerrado]=$(ahora_ms); ok "Actividad registrada (HTTP $ACT) y ticket finalizado"

paso "5. La docente califica la atención"
llamar POST "/tickets/$ID/encuesta" "$TOKEN_USR" '{"calificacion":5,"comentario":"Resuelto el mismo día"}'
ENC=$CODIGO; ok "Encuesta enviada (HTTP $ENC)"

paso "6. Trazabilidad en PostgreSQL"
HIST=$(sql "SELECT count(*) FROM ticket_historial WHERE ticket_id = '$ID'" | tr -d '\r')
EVENTOS=$(sql "SELECT string_agg(event_type, ', ' ORDER BY occurred_at) FROM outbox WHERE aggregate_id = '$ID'" | tr -d '\r')
NEV=$(sql "SELECT count(*) FROM outbox WHERE aggregate_id = '$ID'" | tr -d '\r')
publicados() { [ "$(sql "SELECT count(*) FROM outbox WHERE aggregate_id = '$ID' AND published_at IS NULL" | tr -d '\r')" = "0" ]; }
esperar 120 "la publicación de los eventos" publicados
info "$HIST cambios en el historial · $NEV eventos: $EVENTOS"

paso "7. La coordinación nacional lo ve en el Data Lake"
sleep 15
disparar_pipeline "$TOKEN_ADMIN"; T[pipeline]=$(ahora_ms)
en_gold() { [ "$(trino "SELECT estado_codigo FROM gold.fact_tickets WHERE numero = '$NUM'")" = "FINALIZADA" ]; }
esperar 1200 "que Gold muestre el ticket finalizado" en_gold
T[gold]=$(ahora_ms)
FILA=$(trino "SELECT sede_codigo, categoria_codigo, estado_codigo, coalesce(cast(min_resolucion AS varchar), '') FROM gold.fact_tickets WHERE numero = '$NUM'" | tr '\t' '/')
NBRONZE=$(trino "SELECT count(DISTINCT event_id) FROM bronze.domain_events WHERE aggregate_id = '$ID'")
HOY=$(date +%Y-%m-%d)
llamar GET "/analitica/resumen?desde=$HOY&hasta=$HOY&sede=SEDE_GIRARDOT" "$TOKEN_ADMIN"
ANALITICA=$CODIGO; LAT_ANALITICA=$TIEMPO
ok "Gold: $FILA · eventos en Bronze: $NBRONZE de $NEV · analítica respondió en $LAT_ANALITICA s"

cumple() { awk -v v="$1" -v m="$2" 'BEGIN{print (v<m)?"✅ cumple":"❌ no cumple"}'; }
{
  echo "# Resultado · Hito 3 · Tarea de punta a punta"
  echo
  echo "Ejecutado: $(date '+%Y-%m-%d %H:%M') · ticket $NUM · usuaria $USUARIO · técnico $TEC_EMAIL · asignación $MODO"
  echo
  echo "| Paso / medición | Meta (predicción) | Observado | ¿Cumple? |"
  echo "| --- | --- | --- | --- |"
  echo "| Registrar el ticket (respuesta de la API) | < 2 s | $LAT_CREAR s | $(cumple "$LAT_CREAR" 2) |"
  echo "| Clasificación por IA | < 60 s | ${T[clasificado]:+$(seg "${T[creado]}" "${T[clasificado]}") s} | $( [ -n "${T[clasificado]:-}" ] && cumple "$(seg "${T[creado]}" "${T[clasificado]}")" 60 || echo '❌ no cumple') |"
  echo "| Categoría asignada | IMPRESION | $CAT | $( [ "$CAT" = IMPRESION ] && echo '✅ cumple' || echo '⚠️ distinta') |"
  echo "| Técnico asignado | < 2 min desde el registro | $(seg "${T[creado]}" "${T[asignado]}") s ($MODO) | $(cumple "$(seg "${T[creado]}" "${T[asignado]}")" 120) |"
  echo "| Historial y eventos completos | ≥ 4 cambios y ≥ 5 eventos | $HIST cambios · $NEV eventos | $( [ "$HIST" -ge 4 ] && [ "$NEV" -ge 5 ] && echo '✅ cumple' || echo '❌ no cumple') |"
  echo "| Eventos en Bronze = eventos del outbox | iguales, sin duplicados | $NBRONZE / $NEV | $( [ "$NBRONZE" = "$NEV" ] && echo '✅ cumple' || echo '❌ no cumple') |"
  echo "| Ticket finalizado visible en Gold | < 10 min desde que se lanza el pipeline | $(seg "${T[pipeline]}" "${T[gold]}") s | $(cumple "$(seg "${T[pipeline]}" "${T[gold]}")" 600) |"
  echo "| Consulta de analítica por sede (Trino) | < 3 s | $LAT_ANALITICA s (HTTP $ANALITICA) | $(cumple "$LAT_ANALITICA" 3) |"
  echo "| Tarea completa, del reporte a la analítica | < 15 min | $(seg "${T[inicio]}" "${T[gold]}") s | $(cumple "$(seg "${T[inicio]}" "${T[gold]}")" 900) |"
  echo
  echo "Eventos del ticket, en orden: $EVENTOS"
  echo
  echo "Fila en gold.fact_tickets (sede, categoría, estado, minutos de resolución): $FILA"
} > "$SALIDA"
echo; cat "$SALIDA"; echo; ok "Resultado guardado en ${SALIDA#$RAIZ/}"
