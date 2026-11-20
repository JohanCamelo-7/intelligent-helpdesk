package com.helpdesk.api.analytics;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Business Intelligence: solo ADMIN, solo capa Gold. */
@RestController
@RequestMapping("/api/v1/analitica")
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final AirflowClient airflow;

    public AnalyticsController(AnalyticsService analytics, AirflowClient airflow) {
        this.analytics = analytics;
        this.airflow = airflow;
    }

    @GetMapping("/resumen")
    public Map<String, Object> resumen(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) String sede,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String tecnico,
            @RequestParam(required = false) String prioridad,
            @RequestParam(required = false) String estado,
            @RequestParam(defaultValue = "true") boolean incluirDemo,
            @RequestParam(defaultValue = "DIA") AnalyticsService.Agrupacion agrupacion) {
        return analytics.resumen(new AnalyticsService.Filtros(desde, hasta, sede, categoria, tecnico, prioridad, estado,
                incluirDemo), agrupacion);
    }

    @GetMapping("/pipeline/ejecuciones")
    public Map<String, Object> ejecuciones() {
        return Map.of("lago", analytics.ejecucionesPipeline(), "airflow", airflow.ultimasEjecuciones());
    }

    @GetMapping("/calidad")
    public List<Map<String, Object>> calidad() { return analytics.calidad(); }

    @PostMapping("/pipeline/ejecutar")
    public Map<String, Object> ejecutar() { return airflow.dispararPipeline(); }
}
