#!/usr/bin/env bash
# =====================================================================
# HITO 2 · Prueba del componente distribuido: caída del broker de mensajes
#
# Pregunta: ¿qué le pasa al sistema nacional si el broker (RabbitMQ) que une
# la operación de las sedes con el Data Lake y la IA se cae unos minutos?
#
# Predicción (escrita ANTES de correr la prueba, ver docs/proyecto-final/03-hito2-distribuido.md):
#   - Las sedes siguen registrando tickets (PostgreSQL no depende del broker).
#   - Los eventos se acumulan en la tabla outbox; no se pierde ninguno.
#   - La clasificación por IA y el Data Lake se ATRASAN (consistencia eventual).
#   - Al volver el broker, el outbox se vacía y Gold vuelve a coincidir con PostgreSQL, sin duplicados.
#
# Uso (Git Bash, desde la carpeta del proyecto):   ./pruebas/hito2-prueba-distribuida.sh [TICKETS] [SEGUNDOS_CAIDA]
#   Por defecto 12 tickets (4 por sede) y 60 s de caída. Crea tickets reales marcados «[PRUEBA HITO 2]».
# =====================================================================
. "$(dirname "$0")/lib.sh"
N=${1:-12}; CAIDA=${2:-60}
SALIDA="$RESULTADOS/hito2-$(date +%Y%m%d-%H%M).md"
verificar_servicios

paso "Inicio de sesión"
TOKEN_ADMIN=$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD")
[ -n "$TOKEN_ADMIN" ] || { echo "No se pudo iniciar sesión como administrador ($ADMIN_EMAIL). Revisa ADMIN_PASSWORD en .env"; exit 1; }
ok "Administrador: $ADMIN_EMAIL"
mapfile -t SEDES < <(sql "SELECT id || '|' || codigo || '|' || ciudad FROM sedes WHERE activo ORDER BY id" | tr -d '\r')
info "Sedes: $(printf '%s ' "${SEDES[@]}" | sed 's/[0-9]*|SEDE_[A-Z]*|//g')"

paso "1. Estado inicial (antes de la falla)"
PG0=$(sql "SELECT count(*) FROM tickets" | tr -d '\r')
GOLD0=$(trino "SELECT count(*) FROM gold.fact_tickets" || echo "?")
PEND0=$(sql "SELECT count(*) FROM outbox WHERE published_at IS NULL" | tr -d '\r')
info "PostgreSQL: $PG0 tickets · Gold: $GOLD0 tickets · outbox pendiente: $PEND0"

paso "2. Se detiene RabbitMQ (simula la caída del broker)"
docker compose stop rabbitmq >/dev/null 2>&1
T_CAIDA=$(ahora_ms); ok "RabbitMQ detenido"

paso "3. Las sedes siguen reportando: se crean $N tickets por la API durante la caída"
IDS=(); NUMS=(); LAT=(); FALLOS=0
DESCS=("El computador de la sala de sistemas no enciende después del corte de energía"
       "La impresora de coordinación académica muestra atasco de papel y no imprime"
       "No hay conexión a internet en el bloque administrativo desde esta mañana"
       "El videobeam del aula no detecta la señal HDMI del portátil del docente")
for i in $(seq 1 "$N"); do
  IFS='|' read -r SID SCOD SCIU <<<"${SEDES[$(( (i - 1) % ${#SEDES[@]} ))]}"
  D="[PRUEBA HITO 2] ${DESCS[$(( (i - 1) % 4 ))]} (sede $SCIU, caso $i)"
  llamar POST /tickets "$TOKEN_ADMIN" "{\"descripcion\":\"$D\",\"sedeId\":$SID,\"impacto\":\"AREA\",\"bloqueaTrabajo\":true}"
  if [ "$CODIGO" = "201" ]; then
    IDS+=("$(echo "$CUERPO" | json_str id)"); NUMS+=("$(echo "$CUERPO" | json_str numero)"); LAT+=("$TIEMPO")
  else FALLOS=$((FALLOS + 1)); info "Respuesta $CODIGO en el caso $i"; fi
done
CREADOS=${#IDS[@]}
LAT_PROM=$(printf '%s\n' "${LAT[@]:-0}" | awk '{s+=$1; if ($1>m) m=$1} END {printf "%.3f|%.3f", s/NR, m}')
ok "Creados $CREADOS de $N (fallidos: $FALLOS) · latencia promedio ${LAT_PROM%|*} s, máxima ${LAT_PROM#*|} s"
LISTA_IDS=$(printf "'%s'," "${IDS[@]}"); LISTA_IDS=${LISTA_IDS%,}
LISTA_NUMS=$(printf "'%s'," "${NUMS[@]}"); LISTA_NUMS=${LISTA_NUMS%,}

paso "4. Efecto de la caída"
sleep 5
PEND_CAIDA=$(sql "SELECT count(*) FROM outbox WHERE published_at IS NULL" | tr -d '\r')
EV_PRUEBA=$(sql "SELECT count(*) FROM outbox WHERE published_at IS NULL AND aggregate_id IN ($LISTA_IDS)" | tr -d '\r')
CLASIF_CAIDA=$(sql "SELECT count(*) FROM tickets WHERE id::text IN ($LISTA_IDS) AND categoria_id IS NOT NULL" | tr -d '\r')
PG1=$(sql "SELECT count(*) FROM tickets" | tr -d '\r')
info "PostgreSQL ya tiene $PG1 tickets (+$((PG1 - PG0)))"
info "Eventos sin publicar en el outbox: $PEND_CAIDA (de ellos, $EV_PRUEBA de los tickets de prueba)"
info "Tickets de prueba ya clasificados por la IA: $CLASIF_CAIDA de $CREADOS"
info "Se mantiene la caída hasta completar $CAIDA s…"
RESTA=$(( CAIDA - ( $(ahora_ms) - T_CAIDA ) / 1000 )); [ "$RESTA" -gt 0 ] && sleep "$RESTA"

paso "5. Se restablece RabbitMQ"
docker compose start rabbitmq >/dev/null 2>&1
T_VUELTA=$(ahora_ms); ok "RabbitMQ iniciado"
outbox_vacio() { [ "$(sql "SELECT count(*) FROM outbox WHERE published_at IS NULL AND aggregate_id IN ($LISTA_IDS)" | tr -d '\r')" = "0" ]; }
esperar 300 "que el outbox publique los eventos retenidos" outbox_vacio
T_DRENADO=$(ahora_ms); ok "Outbox vacío $(seg "$T_VUELTA" "$T_DRENADO") s después de volver el broker"
clasificados() { [ "$(sql "SELECT count(*) FROM tickets WHERE id::text IN ($LISTA_IDS) AND categoria_id IS NOT NULL" | tr -d '\r')" = "$CREADOS" ]; }
if esperar 300 "la clasificación por IA de los tickets de prueba" clasificados; then
  T_CLASIF=$(ahora_ms); S_CLASIF=$(seg "$T_VUELTA" "$T_CLASIF")
  ok "Los $CREADOS tickets quedaron clasificados $S_CLASIF s después de volver el broker"
  C_CLASIF=$(awk -v s="$S_CLASIF" 'BEGIN{print (s<120)?"✅ cumple":"❌ no cumple"}'); S_CLASIF="$S_CLASIF s"
else S_CLASIF="no terminó en 5 min"; C_CLASIF="❌ no cumple"; fi

paso "6. Se ejecuta el pipeline del Data Lake y se espera a que Gold tenga los tickets"
sleep 15   # data-ingestion escribe en Landing cada 10 s
disparar_pipeline "$TOKEN_ADMIN"; T_PIPE=$(ahora_ms)
en_gold() { [ "$(trino "SELECT count(*) FROM gold.fact_tickets WHERE numero IN ($LISTA_NUMS)")" = "$CREADOS" ]; }
esperar 1200 "que Gold contenga los tickets de prueba" en_gold
T_GOLD=$(ahora_ms); ok "Tickets de prueba en Gold $(seg "$T_PIPE" "$T_GOLD") s después de lanzar el pipeline"

paso "7. Verificación final"
PG2=$(sql "SELECT count(*) FROM tickets" | tr -d '\r')
GOLD2=$(trino "SELECT count(*) FROM gold.fact_tickets")
DUP=$(trino "SELECT count(*) - count(DISTINCT event_id) FROM bronze.domain_events")
EV_OUT=$(sql "SELECT count(*) FROM outbox WHERE aggregate_id IN ($LISTA_IDS)" | tr -d '\r')
EV_BRONZE=$(trino "SELECT count(DISTINCT event_id) FROM bronze.domain_events WHERE aggregate_id IN ($LISTA_IDS)")
info "PostgreSQL $PG2 · Gold $GOLD2 · eventos de prueba: outbox $EV_OUT / Bronze $EV_BRONZE · event_id duplicados en Bronze: $DUP"
POR_SEDE_PG=$(sql "SELECT s.codigo || '|' || count(t.id) FROM sedes s LEFT JOIN tickets t ON t.sede_id = s.id GROUP BY s.codigo ORDER BY 1" | tr -d '\r')
POR_SEDE_GOLD=$(trino "SELECT sede_codigo, count(*) FROM gold.fact_tickets GROUP BY 1 ORDER BY 1")

marca() { [ "$1" = "$2" ] && echo "✅ cumple" || echo "❌ no cumple"; }
{
  echo "# Resultado · Hito 2 · Caída del broker de mensajes"
  echo
  echo "Ejecutado: $(date '+%Y-%m-%d %H:%M') · tickets de prueba: $N · caída de RabbitMQ: $CAIDA s"
  echo
  echo "| Medición | Predicción | Observado | ¿Cumple? |"
  echo "| --- | --- | --- | --- |"
  echo "| Tickets registrados durante la caída | $N de $N | $CREADOS de $N | $(marca "$CREADOS" "$N") |"
  echo "| Latencia de registro con el broker caído | < 2 s | prom. ${LAT_PROM%|*} s, máx. ${LAT_PROM#*|} s | $(awk -v m="${LAT_PROM#*|}" 'BEGIN{print (m<2)?"✅ cumple":"❌ no cumple"}') |"
  echo "| Eventos de la prueba retenidos en el outbox (no perdidos) | ≥ 1 por ticket | $EV_PRUEBA sin publicar | $( [ "$EV_PRUEBA" -ge "$CREADOS" ] && echo '✅ cumple' || echo '❌ no cumple') |"
  echo "| Clasificación por IA durante la caída | se atrasa (0 clasificados) | $CLASIF_CAIDA de $CREADOS | $(marca "$CLASIF_CAIDA" 0) |"
  echo "| Vaciado del outbox al volver el broker | < 60 s | $(seg "$T_VUELTA" "$T_DRENADO") s | $(awk -v s="$(seg "$T_VUELTA" "$T_DRENADO")" 'BEGIN{print (s<60)?"✅ cumple":"❌ no cumple"}') |"
  echo "| Clasificación al volver el broker | < 120 s | $S_CLASIF | $C_CLASIF |"
  echo "| Tiempo hasta verse en Gold (desde que se lanza el pipeline) | < 10 min | $(seg "$T_PIPE" "$T_GOLD") s | $(awk -v s="$(seg "$T_PIPE" "$T_GOLD")" 'BEGIN{print (s<600)?"✅ cumple":"❌ no cumple"}') |"
  echo "| Eventos de prueba en Bronze = en el outbox | iguales | $EV_BRONZE / $EV_OUT | $(marca "$EV_BRONZE" "$EV_OUT") |"
  echo "| event_id duplicados en Bronze | 0 | $DUP | $(marca "$DUP" 0) |"
  echo "| Tickets PostgreSQL = Gold al final | iguales | $PG2 / $GOLD2 | $(marca "$PG2" "$GOLD2") |"
  echo
  echo "Desfase observado antes de la prueba: PostgreSQL $PG0 vs Gold $GOLD0 tickets."
  echo
  echo "**Tickets por sede**"
  echo
  echo "| Sede | PostgreSQL | Gold |"
  echo "| --- | --- | --- |"
  while IFS='|' read -r c n; do
    g=$(echo "$POR_SEDE_GOLD" | awk -v c="$c" -F'\t' '$1==c {print $2}')
    echo "| $c | $n | ${g:-0} |"
  done <<<"$POR_SEDE_PG"
  echo
  echo "Tickets de prueba: ${NUMS[*]}"
} > "$SALIDA"
echo; cat "$SALIDA"; echo; ok "Resultado guardado en ${SALIDA#$RAIZ/}"
