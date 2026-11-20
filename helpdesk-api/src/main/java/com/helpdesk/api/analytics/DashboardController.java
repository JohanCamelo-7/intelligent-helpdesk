package com.helpdesk.api.analytics;

import com.helpdesk.api.assignment.AssignmentConfig;
import com.helpdesk.api.shared.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Indicadores OPERATIVOS en tiempo real (PostgreSQL). Se distinguen de los
 * indicadores históricos del Data Lake, que salen de /api/v1/analitica.
 */
@RestController
public class DashboardController {

    private final JdbcTemplate jdbc;
    private final AssignmentConfig config;

    public DashboardController(JdbcTemplate jdbc, AssignmentConfig config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @GetMapping("/api/v1/dashboard/operativo")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> operativo() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tipo", "OPERATIVO_TIEMPO_REAL");
        out.put("contadores", jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE estado_codigo = 'PENDIENTE') AS pendientes,
                       count(*) FILTER (WHERE estado_codigo = 'EN_CURSO') AS en_curso,
                       count(*) FILTER (WHERE estado_codigo = 'FINALIZADA') AS finalizados,
                       count(*) FILTER (WHERE estado_codigo IN ('PENDIENTE','EN_CURSO') AND prioridad_codigo IN ('ALTA','CRITICA')) AS prioritarios,
                       count(*) FILTER (WHERE estado_codigo IN ('PENDIENTE','EN_CURSO') AND tecnico_id IS NULL) AS sin_tecnico,
                       count(*) FILTER (WHERE ia_categoria_codigo IS NOT NULL) AS clasificados_ia,
                       count(*) FILTER (WHERE requiere_revision AND estado_codigo IN ('PENDIENTE','EN_CURSO')) AS pendientes_validacion
                FROM tickets"""));
        out.put("tiempos30Dias", jdbc.queryForMap("""
                SELECT round(avg(EXTRACT(EPOCH FROM (fecha_inicio_atencion - fecha_asignacion)) / 60)::numeric, 1) AS prom_atencion_min,
                       round(avg(EXTRACT(EPOCH FROM (fecha_finalizacion - fecha_creacion)) / 60)::numeric, 1) AS prom_resolucion_min
                FROM tickets WHERE fecha_creacion >= now() - interval '30 days' AND estado_codigo = 'FINALIZADA'"""));
        out.put("tecnicos", jdbc.queryForList("""
                SELECT u.id, u.nombre, t.nivel, t.disponible, t.carga_maxima,
                       COALESCE(SUM(p.peso) FILTER (WHERE k.estado_codigo IN ('PENDIENTE','EN_CURSO')), 0) AS carga,
                       COUNT(k.id) FILTER (WHERE k.estado_codigo IN ('PENDIENTE','EN_CURSO')) AS abiertos
                FROM tecnicos t JOIN usuarios u ON u.id = t.usuario_id
                LEFT JOIN tickets k ON k.tecnico_id = t.usuario_id
                LEFT JOIN prioridades p ON p.codigo = k.prioridad_codigo
                WHERE u.activo
                GROUP BY u.id, u.nombre, t.nivel, t.disponible, t.carga_maxima
                ORDER BY carga DESC, u.nombre"""));
        out.put("tecnicosDisponibles", jdbc.queryForObject(
                "SELECT count(*) FROM tecnicos t JOIN usuarios u ON u.id = t.usuario_id WHERE t.disponible AND u.activo", Long.class));
        out.put("revision", jdbc.queryForList("""
                SELECT id, numero, prioridad_codigo AS prioridad, ai_confianza, fecha_creacion
                FROM tickets WHERE requiere_revision AND estado_codigo IN ('PENDIENTE','EN_CURSO')
                ORDER BY fecha_creacion LIMIT 20"""));
        return out;
    }

    @GetMapping("/api/v1/dashboard/tecnico")
    @PreAuthorize("hasRole('TECNICO')")
    public Map<String, Object> tecnico() {
        UUID me = CurrentUser.get().id();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contadores", jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE estado_codigo = 'PENDIENTE') AS pendientes,
                       count(*) FILTER (WHERE estado_codigo = 'EN_CURSO') AS en_curso,
                       count(*) FILTER (WHERE estado_codigo = 'FINALIZADA') AS finalizados,
                       count(*) FILTER (WHERE estado_codigo = 'FINALIZADA' AND fecha_finalizacion >= now() - interval '30 days') AS finalizados_30d,
                       round(avg(EXTRACT(EPOCH FROM (fecha_finalizacion - fecha_creacion)) / 60)
                             FILTER (WHERE estado_codigo = 'FINALIZADA')::numeric, 1) AS prom_resolucion_min
                FROM tickets WHERE tecnico_id = ?""", me));
        out.put("carga", jdbc.queryForMap("""
                SELECT t.carga_maxima, t.disponible, COALESCE((SELECT SUM(p.peso) FROM tickets k JOIN prioridades p ON p.codigo = k.prioridad_codigo
                        WHERE k.tecnico_id = t.usuario_id AND k.estado_codigo IN ('PENDIENTE','EN_CURSO')), 0) AS carga
                FROM tecnicos t WHERE t.usuario_id = ?""", me));
        out.put("satisfaccion", jdbc.queryForObject("""
                SELECT round(avg(e.calificacion)::numeric, 2) FROM encuestas e JOIN tickets k ON k.id = e.ticket_id WHERE k.tecnico_id = ?""",
                Double.class, me));
        return out;
    }

    public record PesosRequest(@DecimalMin("0") @DecimalMax("1") double especialidad,
                               @DecimalMin("0") @DecimalMax("1") double experiencia,
                               @DecimalMin("0") @DecimalMax("1") double carga,
                               @DecimalMin("0") @DecimalMax("1") double desempeno,
                               @DecimalMin("0") @DecimalMax("1") double umbralConfianza,
                               @DecimalMin("0") @DecimalMax("1") double umbralPuntaje) {}

    @GetMapping("/api/v1/config/asignacion")
    @PreAuthorize("hasRole('ADMIN')")
    public AssignmentConfig.Valores leerConfig() { return config.leer(); }

    @PutMapping("/api/v1/config/asignacion")
    @PreAuthorize("hasRole('ADMIN')")
    public AssignmentConfig.Valores guardarConfig(@Valid @RequestBody PesosRequest r) {
        return config.guardar(r.especialidad(), r.experiencia(), r.carga(), r.desempeno(), r.umbralConfianza(), r.umbralPuntaje());
    }
}
