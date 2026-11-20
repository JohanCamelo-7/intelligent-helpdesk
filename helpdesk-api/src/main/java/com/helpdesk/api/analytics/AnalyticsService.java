package com.helpdesk.api.analytics;

import com.helpdesk.api.shared.ApiException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Indicadores HISTÓRICOS calculados sobre la capa Gold del Data Lake (vía Trino).
 * Reglas de contabilización (docs/datos/reglas-de-contabilizacion.md):
 *  - Un ticket cuenta en la fecha (America/Bogota) en que se creó.
 *  - Tiempo de atención = inicio de atención − asignación; resolución = finalización − creación (minutos).
 *  - Los cancelados se excluyen de los promedios de resolución.
 *  - Datos marcados es_demo se pueden excluir con incluirDemo=false.
 */
@Service
public class AnalyticsService {

    private final TrinoGateway trino;

    public AnalyticsService(TrinoGateway trino) { this.trino = trino; }

    public enum Agrupacion { DIA("day"), SEMANA("week"), MES("month");
        final String unidad; Agrupacion(String u) { unidad = u; } }

    public record Filtros(LocalDate desde, LocalDate hasta, String sede, String categoria, String tecnico,
                          String prioridad, String estado, boolean incluirDemo) {
        public Filtros {
            if (hasta == null) hasta = LocalDate.now();
            if (desde == null) desde = hasta.minusDays(90);
            if (desde.isAfter(hasta)) throw ApiException.badRequest("RANGO_INVALIDO", "La fecha inicial es posterior a la final");
            if (ChronoUnit.DAYS.between(desde, hasta) > 1100) throw ApiException.badRequest("RANGO_GRANDE", "El rango máximo es de 3 años");
        }
        Filtros periodoAnterior() {
            long dias = ChronoUnit.DAYS.between(desde, hasta) + 1;
            return new Filtros(desde.minusDays(dias), desde.minusDays(1), sede, categoria, tecnico, prioridad, estado, incluirDemo);
        }
    }

    /** WHERE con parámetros enlazados para la tabla de hechos (alias f). */
    private record Where(String sql, List<Object> params) {}

    private Where whereTickets(Filtros f) {
        StringBuilder sb = new StringBuilder(" WHERE f.fecha_creacion BETWEEN CAST(? AS date) AND CAST(? AS date)");
        List<Object> p = new ArrayList<>(List.of(f.desde(), f.hasta()));
        add(sb, p, "f.sede_codigo", f.sede());
        add(sb, p, "f.categoria_codigo", f.categoria());
        add(sb, p, "f.tecnico_id", f.tecnico());
        add(sb, p, "f.prioridad_codigo", f.prioridad());
        add(sb, p, "f.estado_codigo", f.estado());
        if (!f.incluirDemo()) sb.append(" AND NOT f.es_demo");
        return new Where(sb.toString(), p);
    }

    private Where whereSnapshot(Filtros f) {
        StringBuilder sb = new StringBuilder(" WHERE s.fecha BETWEEN CAST(? AS date) AND CAST(? AS date)");
        List<Object> p = new ArrayList<>(List.of(f.desde(), f.hasta()));
        add(sb, p, "s.sede_codigo", f.sede());
        add(sb, p, "s.categoria_codigo", f.categoria());
        add(sb, p, "s.tecnico_id", f.tecnico());
        add(sb, p, "s.prioridad_codigo", f.prioridad());
        if (!f.incluirDemo()) sb.append(" AND NOT s.es_demo");
        return new Where(sb.toString(), p);
    }

    private static void add(StringBuilder sb, List<Object> p, String col, String val) {
        if (val != null && !val.isBlank()) {
            sb.append(" AND ").append(col).append(" = ?");
            p.add(val.trim());
        }
    }

    public Map<String, Object> resumen(Filtros f, Agrupacion agrupacion) {
        Where w = whereTickets(f);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filtros", f);
        out.put("kpis", kpis(w));
        out.put("serie", serie(w, agrupacion == null ? Agrupacion.DIA : agrupacion));
        out.put("porCategoria", porCategoria(w));
        out.put("porPrioridad", trino.query("""
                SELECT f.prioridad_codigo AS codigo, COALESCE(p.nombre, f.prioridad_codigo) AS nombre, count(*) AS total
                FROM gold.fact_tickets f LEFT JOIN gold.dim_prioridad p ON p.prioridad_codigo = f.prioridad_codigo""" + w.sql() + """
                 GROUP BY 1, 2, p.peso ORDER BY p.peso""", w.params()));
        out.put("porSede", trino.query("""
                SELECT f.sede_codigo AS codigo, COALESCE(s.nombre, f.sede_codigo) AS nombre, count(*) AS total,
                       count_if(f.es_reincidente) AS reincidentes
                FROM gold.fact_tickets f LEFT JOIN gold.dim_sede s ON s.sede_codigo = f.sede_codigo""" + w.sql() + """
                 GROUP BY 1, 2 ORDER BY 3 DESC""", w.params()));
        out.put("porEstado", trino.query("""
                SELECT f.estado_codigo AS codigo, COALESCE(e.nombre, f.estado_codigo) AS nombre, count(*) AS total
                FROM gold.fact_tickets f LEFT JOIN gold.dim_estado e ON e.estado_codigo = f.estado_codigo""" + w.sql() + """
                 GROUP BY 1, 2 ORDER BY 3 DESC""", w.params()));
        out.put("topFallas", trino.query("""
                SELECT f.tipo_falla_codigo AS codigo, COALESCE(t.nombre, f.tipo_falla_codigo) AS nombre, count(*) AS total,
                       avg(CASE WHEN f.estado_codigo = 'FINALIZADA' THEN f.min_resolucion END) AS prom_resolucion_min
                FROM gold.fact_tickets f LEFT JOIN gold.dim_tipo_falla t ON t.tipo_falla_codigo = f.tipo_falla_codigo""" + w.sql() + """
                 AND f.tipo_falla_codigo IS NOT NULL GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 10""", w.params()));
        out.put("equipos", equipos(w));
        out.put("porDiaSemana", trino.query("""
                SELECT d.dia_semana AS dia, d.nombre_dia AS nombre, count(*) AS total
                FROM gold.fact_tickets f JOIN gold.dim_fecha d ON d.fecha_key = f.fecha_creacion_key""" + w.sql() + """
                 GROUP BY 1, 2 ORDER BY 1""", w.params()));
        out.put("tecnicos", tecnicos(w));
        Where ws = whereSnapshot(f);
        out.put("pendientes", trino.query("""
                SELECT s.fecha AS fecha, count(*) AS abiertos, count_if(s.antiguedad_horas > 72) AS mas_de_3_dias
                FROM gold.fact_snapshot_diario s""" + ws.sql() + " GROUP BY 1 ORDER BY 1", ws.params()));
        out.put("reincidencia", trino.query("""
                SELECT f.categoria_codigo AS codigo, COALESCE(c.nombre, 'Sin clasificar') AS nombre,
                       count_if(f.es_reincidente) AS reincidentes, count(*) AS total
                FROM gold.fact_tickets f LEFT JOIN gold.dim_categoria c ON c.categoria_codigo = f.categoria_codigo""" + w.sql() + """
                 GROUP BY 1, 2 HAVING count_if(f.es_reincidente) > 0 ORDER BY 3 DESC""", w.params()));
        out.put("crecimiento", crecimiento(f));
        out.put("ia", ia(f));
        out.put("ultimaActualizacion", ultimaActualizacion());
        return out;
    }

    Map<String, Object> kpis(Where w) {
        List<Map<String, Object>> rows = trino.query("""
                SELECT count(*) AS total,
                       count_if(f.estado_codigo = 'FINALIZADA') AS finalizados,
                       count_if(f.estado_codigo IN ('PENDIENTE','EN_CURSO')) AS abiertos,
                       count_if(f.estado_codigo = 'CANCELADA') AS cancelados,
                       avg(f.min_hasta_atencion) AS prom_atencion_min,
                       avg(CASE WHEN f.estado_codigo = 'FINALIZADA' THEN f.min_resolucion END) AS prom_resolucion_min,
                       count_if(f.es_reincidente) AS reincidentes,
                       avg(CASE WHEN f.ia_acerto THEN 1.0 WHEN NOT f.ia_acerto THEN 0.0 END) AS pct_ia_acierto,
                       avg(CASE WHEN f.asignacion_efectiva THEN 1.0 WHEN NOT f.asignacion_efectiva THEN 0.0 END) AS pct_asignacion_efectiva,
                       count_if(f.asignado_por_ia) AS asignados_por_ia,
                       count_if(f.requirio_revision) AS requirieron_revision,
                       avg(CAST(f.calificacion AS double)) AS satisfaccion,
                       count_if(f.es_demo) AS registros_demo
                FROM gold.fact_tickets f""" + w.sql(), w.params());
        Map<String, Object> k = rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.get(0));
        long total = ((Number) k.getOrDefault("total", 0L)).longValue();
        long fin = ((Number) k.getOrDefault("finalizados", 0L)).longValue();
        k.put("pct_finalizados", total == 0 ? null : (double) fin / total);
        return k;
    }

    List<Map<String, Object>> serie(Where w, Agrupacion a) {
        // La unidad viene de un enum (no del usuario): se inserta como literal seguro.
        String unidad = "'" + a.unidad + "'";
        return trino.query("SELECT date_trunc(" + unidad + ", f.fecha_creacion) AS periodo, count(*) AS creados, " +
                "count_if(f.estado_codigo = 'FINALIZADA') AS finalizados, " +
                "avg(CASE WHEN f.estado_codigo = 'FINALIZADA' THEN f.min_resolucion END) AS prom_resolucion_min " +
                "FROM gold.fact_tickets f" + w.sql() + " GROUP BY 1 ORDER BY 1", w.params());
    }

    List<Map<String, Object>> porCategoria(Where w) {
        return trino.query("""
                SELECT COALESCE(f.categoria_codigo, 'SIN_CLASIFICAR') AS codigo, COALESCE(c.nombre, 'Sin clasificar') AS nombre,
                       count(*) AS total, avg(CASE WHEN f.estado_codigo = 'FINALIZADA' THEN f.min_resolucion END) AS prom_resolucion_min
                FROM gold.fact_tickets f LEFT JOIN gold.dim_categoria c ON c.categoria_codigo = f.categoria_codigo""" + w.sql() + """
                 GROUP BY 1, 2 ORDER BY 3 DESC""", w.params());
    }

    List<Map<String, Object>> equipos(Where w) {
        return trino.query("""
                SELECT f.equipo_codigo AS codigo, max(e.tipo) AS tipo, max(e.marca) AS marca, max(e.sede_codigo) AS sede,
                       count(*) AS total, count_if(f.es_reincidente) AS reincidentes,
                       count(*) >= 3 OR count_if(f.es_reincidente) >= 1 AS candidato_mantenimiento
                FROM gold.fact_tickets f LEFT JOIN gold.dim_equipo e ON e.equipo_codigo = f.equipo_codigo""" + w.sql() + """
                 AND f.equipo_codigo IS NOT NULL GROUP BY 1 ORDER BY 5 DESC, 6 DESC LIMIT 15""", w.params());
    }

    List<Map<String, Object>> tecnicos(Where w) {
        return trino.query("""
                SELECT f.tecnico_id AS tecnico_id, max(t.nombre) AS nombre,
                       count_if(f.estado_codigo = 'FINALIZADA') AS resueltos,
                       count_if(f.estado_codigo IN ('PENDIENTE','EN_CURSO')) AS abiertos,
                       avg(CASE WHEN f.estado_codigo = 'FINALIZADA' THEN f.min_resolucion END) AS prom_resolucion_min,
                       avg(f.min_hasta_atencion) AS prom_atencion_min,
                       count_if(f.reaperturas > 0) AS con_reapertura,
                       avg(CAST(f.calificacion AS double)) AS satisfaccion
                FROM gold.fact_tickets f
                LEFT JOIN gold.dim_tecnico t ON t.tecnico_id = f.tecnico_id AND t.es_actual""" + w.sql() + """
                 AND f.tecnico_id IS NOT NULL GROUP BY 1 ORDER BY 3 DESC""", w.params());
    }

    List<Map<String, Object>> crecimiento(Filtros f) {
        List<Map<String, Object>> actual = porCategoria(whereTickets(f));
        Map<String, Long> previo = new HashMap<>();
        for (Map<String, Object> r : porCategoria(whereTickets(f.periodoAnterior()))) {
            previo.put(String.valueOf(r.get("codigo")), ((Number) r.get("total")).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : actual) {
            long now = ((Number) r.get("total")).longValue();
            long prev = previo.getOrDefault(String.valueOf(r.get("codigo")), 0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codigo", r.get("codigo"));
            m.put("nombre", r.get("nombre"));
            m.put("actual", now);
            m.put("anterior", prev);
            m.put("variacion", prev == 0 ? null : (double) (now - prev) / prev);
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(((Number) b.get("actual")).longValue() - ((Number) b.get("anterior")).longValue(),
                ((Number) a.get("actual")).longValue() - ((Number) a.get("anterior")).longValue()));
        return out;
    }

    List<Map<String, Object>> ia(Filtros f) {
        String demo = f.incluirDemo() ? "" : " AND NOT d.es_demo";
        return trino.query("""
                SELECT d.origen AS origen, count(*) AS total,
                       count_if(d.resultado = 'ACERTADO') AS acertados,
                       count_if(d.resultado = 'CORREGIDO') AS corregidos,
                       count_if(d.resultado = 'PENDIENTE') AS pendientes,
                       count_if(NOT d.concluyente) AS no_concluyentes,
                       avg(d.confianza) AS confianza_prom,
                       avg(d.latencia_ms) AS latencia_prom_ms
                FROM gold.fact_diagnosticos_ia d WHERE d.fecha BETWEEN CAST(? AS date) AND CAST(? AS date)""" + demo + " GROUP BY 1 ORDER BY 1",
                List.of(f.desde(), f.hasta()));
    }

    public Object ultimaActualizacion() {
        try {
            List<Map<String, Object>> r = trino.query(
                    "SELECT max(finished_at) AS ts FROM ops.pipeline_runs WHERE step = 'gold' AND status = 'OK'", List.of());
            return r.isEmpty() ? null : r.get(0).get("ts");
        } catch (ApiException e) {
            return null;
        }
    }

    public List<Map<String, Object>> detalleTickets(Filtros f) {
        Where w = whereTickets(f);
        return trino.query("""
                SELECT f.numero, f.fecha_creacion, f.estado_codigo AS estado, f.prioridad_codigo AS prioridad,
                       COALESCE(c.nombre, 'Sin clasificar') AS categoria, t.nombre AS tipo_falla, s.nombre AS sede,
                       f.equipo_codigo AS equipo, tec.nombre AS tecnico, round(f.min_hasta_atencion, 1) AS min_atencion,
                       round(f.min_resolucion, 1) AS min_resolucion, f.reaperturas, f.es_reincidente, f.asignado_por_ia,
                       f.ia_acerto, f.calificacion, f.es_demo
                FROM gold.fact_tickets f
                LEFT JOIN gold.dim_categoria c ON c.categoria_codigo = f.categoria_codigo
                LEFT JOIN gold.dim_tipo_falla t ON t.tipo_falla_codigo = f.tipo_falla_codigo
                LEFT JOIN gold.dim_sede s ON s.sede_codigo = f.sede_codigo
                LEFT JOIN gold.dim_tecnico tec ON tec.tecnico_id = f.tecnico_id AND tec.es_actual""" + w.sql() + """
                 ORDER BY f.creado_en LIMIT 20000""", w.params());
    }

    public List<Map<String, Object>> ejecucionesPipeline() {
        return trino.query("""
                SELECT run_id, step, status, started_at, finished_at, rows_in, rows_out, rows_rejected, message
                FROM ops.pipeline_runs ORDER BY started_at DESC LIMIT 30""", List.of());
    }

    public List<Map<String, Object>> calidad() {
        return trino.query("""
                SELECT run_id, table_name, rule_name, severity, passed, failed_rows, checked_at
                FROM ops.dq_results ORDER BY checked_at DESC LIMIT 60""", List.of());
    }

    // Accesores para el generador de reportes
    public Map<String, Object> kpis(Filtros f) { return kpis(whereTickets(f)); }
    public List<Map<String, Object>> serie(Filtros f, Agrupacion a) { return serie(whereTickets(f), a); }
    public List<Map<String, Object>> categorias(Filtros f) { return porCategoria(whereTickets(f)); }
    public List<Map<String, Object>> tecnicos(Filtros f) { return tecnicos(whereTickets(f)); }
    public List<Map<String, Object>> equipos(Filtros f) { return equipos(whereTickets(f)); }
}
