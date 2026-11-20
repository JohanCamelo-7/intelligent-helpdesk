package com.helpdesk.api.analytics;

import com.helpdesk.api.config.AppProperties;
import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.Json;
import com.helpdesk.api.shared.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reportes asíncronos: se encolan, se generan en segundo plano desde la capa Gold y
 * se descargan al terminar (no dependen de una petición larga a través del túnel).
 */
@Service
public class ReportService {

    public static final Set<String> TIPOS = Set.of("RESUMEN", "DETALLE_TICKETS", "TECNICOS", "CATEGORIAS",
            "SEDES", "PRIORIDADES", "REINCIDENCIA", "IA");
    public static final Set<String> FORMATOS = Set.of("CSV", "XLSX", "PDF");
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportJobRepository jobs;
    private final AnalyticsService analytics;
    private final S3Client s3;
    private final AppProperties props;
    private final Json json;

    public ReportService(ReportJobRepository jobs, AnalyticsService analytics, S3Client s3, AppProperties props, Json json) {
        this.jobs = jobs;
        this.analytics = analytics;
        this.s3 = s3;
        this.props = props;
        this.json = json;
    }

    public record Solicitud(String tipo, String formato, LocalDate desde, LocalDate hasta, String sede, String categoria,
                            String tecnico, String prioridad, String estado, boolean incluirDemo,
                            AnalyticsService.Agrupacion agrupacion) {}

    public ReportJob encolar(Solicitud s, UUID usuario) {
        if (!TIPOS.contains(s.tipo())) throw ApiException.badRequest("TIPO_REPORTE", "Tipo de reporte no válido");
        if (!FORMATOS.contains(s.formato())) throw ApiException.badRequest("FORMATO_REPORTE", "Formato no válido");
        ReportJob j = new ReportJob();
        j.setId(UUID.randomUUID());
        j.setSolicitadoPor(usuario);
        j.setTipo(s.tipo());
        j.setFormato(s.formato());
        j.setParametros(json.write(s));
        j.setEstado("EN_COLA");
        j.setCreadoEn(Instant.now());
        return jobs.save(j);
    }

    @Async
    public void generar(UUID jobId, Solicitud s) {
        ReportJob j = jobs.findById(jobId).orElseThrow();
        j.setEstado("PROCESANDO");
        jobs.save(j);
        try {
            ReportWriter.Reporte r = construir(s);
            byte[] bytes = ReportWriter.escribir(r, s.formato());
            String key = "reportes/" + LocalDate.now() + "/" + jobId + "." + s.formato().toLowerCase();
            s3.putObject(PutObjectRequest.builder().bucket(props.s3().reportsBucket()).key(key)
                    .contentType(contentType(s.formato())).build(), RequestBody.fromBytes(bytes));
            j.setObjectKey(key);
            j.setEstado("LISTO");
        } catch (RuntimeException e) {
            log.warn("Falló el reporte {}: {}", jobId, e.getMessage());
            j.setEstado("ERROR");
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            j.setError(msg.length() > 480 ? msg.substring(0, 480) : msg);
        }
        j.setTerminadoEn(Instant.now());
        jobs.save(j);
    }

    ReportWriter.Reporte construir(Solicitud s) {
        AnalyticsService.Filtros f = new AnalyticsService.Filtros(s.desde(), s.hasta(), s.sede(), s.categoria(),
                s.tecnico(), s.prioridad(), s.estado(), s.incluirDemo());
        Map<String, Object> resumen = analytics.resumen(f, s.agrupacion());
        List<String> notas = new ArrayList<>(List.of(
                "Periodo: " + f.desde() + " a " + f.hasta() + " (fecha de creación, zona America/Bogota)",
                "Fuente: capa Gold del Data Lake. Última actualización del lago: " + resumen.get("ultimaActualizacion"),
                "Filtros: " + json.write(Maps.of("sede", s.sede(), "categoria", s.categoria(), "tecnico", s.tecnico(),
                        "prioridad", s.prioridad(), "estado", s.estado(), "incluirDemo", s.incluirDemo())),
                "Reglas: atención = inicio − asignación; resolución = finalización − creación (minutos); cancelados excluidos de promedios.",
                "Reincidente: mismo equipo (o solicitante) y categoría dentro de 30 días tras un cierre.",
                "Generado: " + Instant.now()));
        if (((Number) ((Map<?, ?>) resumen.get("kpis")).get("registros_demo")).longValue() > 0) {
            notas.add("ATENCIÓN: el periodo incluye registros de DEMOSTRACIÓN (es_demo = true).");
        }
        List<ReportWriter.Seccion> secciones = new ArrayList<>();
        switch (s.tipo()) {
            case "RESUMEN" -> {
                secciones.add(kv("Indicadores", (Map<?, ?>) resumen.get("kpis")));
                secciones.add(sec("Evolución (" + s.agrupacion() + ")", resumen.get("serie")));
                secciones.add(sec("Por categoría", resumen.get("porCategoria")));
                secciones.add(sec("Por prioridad", resumen.get("porPrioridad")));
                secciones.add(sec("Por sede", resumen.get("porSede")));
                secciones.add(sec("Fallas más frecuentes", resumen.get("topFallas")));
            }
            case "DETALLE_TICKETS" -> secciones.add(ReportWriter.Seccion.de("Tickets", analytics.detalleTickets(f)));
            case "TECNICOS" -> secciones.add(sec("Rendimiento de técnicos", resumen.get("tecnicos")));
            case "CATEGORIAS" -> {
                secciones.add(sec("Por categoría", resumen.get("porCategoria")));
                secciones.add(sec("Crecimiento frente al periodo anterior", resumen.get("crecimiento")));
                secciones.add(sec("Fallas más frecuentes", resumen.get("topFallas")));
            }
            case "SEDES" -> secciones.add(sec("Por sede", resumen.get("porSede")));
            case "PRIORIDADES" -> secciones.add(sec("Por prioridad", resumen.get("porPrioridad")));
            case "REINCIDENCIA" -> {
                secciones.add(sec("Reincidencia por categoría", resumen.get("reincidencia")));
                secciones.add(sec("Equipos (candidatos a mantenimiento preventivo)", resumen.get("equipos")));
            }
            case "IA" -> secciones.add(sec("Efectividad de la IA", resumen.get("ia")));
            default -> throw new IllegalArgumentException(s.tipo());
        }
        return new ReportWriter.Reporte("Reporte " + s.tipo() + " — Plataforma de Soporte Técnico", notas, secciones);
    }

    @SuppressWarnings("unchecked")
    private static ReportWriter.Seccion sec(String titulo, Object rows) {
        return ReportWriter.Seccion.de(titulo, (List<Map<String, Object>>) rows);
    }

    private static ReportWriter.Seccion kv(String titulo, Map<?, ?> m) {
        List<List<Object>> filas = m.entrySet().stream().map(e -> List.<Object>of(String.valueOf(e.getKey()),
                e.getValue() == null ? "" : e.getValue())).toList();
        return new ReportWriter.Seccion(titulo, List.of("indicador", "valor"), filas);
    }

    public byte[] descargar(ReportJob j) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(props.s3().reportsBucket()).key(j.getObjectKey()).build())
                .asByteArray();
    }

    public static String contentType(String formato) {
        return switch (formato) {
            case "CSV" -> "text/csv; charset=utf-8";
            case "XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/pdf";
        };
    }
}
