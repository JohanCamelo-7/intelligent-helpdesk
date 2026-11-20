package com.helpdesk.api.analytics;

import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reportes")
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final ReportService service;
    private final ReportJobRepository jobs;

    public ReportController(ReportService service, ReportJobRepository jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    public record ReporteRequest(@NotBlank String tipo, @NotBlank String formato, LocalDate desde, LocalDate hasta,
                                 String sede, String categoria, String tecnico, String prioridad, String estado,
                                 Boolean incluirDemo, AnalyticsService.Agrupacion agrupacion) {}

    public record JobDto(UUID id, String tipo, String formato, String estado, String error, Instant creadoEn, Instant terminadoEn) {
        static JobDto of(ReportJob j) {
            return new JobDto(j.getId(), j.getTipo(), j.getFormato(), j.getEstado(), j.getError(), j.getCreadoEn(), j.getTerminadoEn());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto solicitar(@Valid @RequestBody ReporteRequest r) {
        ReportService.Solicitud s = new ReportService.Solicitud(r.tipo().toUpperCase(), r.formato().toUpperCase(),
                r.desde(), r.hasta(), r.sede(), r.categoria(), r.tecnico(), r.prioridad(), r.estado(),
                r.incluirDemo() == null || r.incluirDemo(),
                r.agrupacion() == null ? AnalyticsService.Agrupacion.DIA : r.agrupacion());
        ReportJob j = service.encolar(s, CurrentUser.get().id());
        service.generar(j.getId(), s);
        return JobDto.of(j);
    }

    @GetMapping
    public List<JobDto> mios() {
        return jobs.findTop20BySolicitadoPorOrderByCreadoEnDesc(CurrentUser.get().id()).stream().map(JobDto::of).toList();
    }

    @GetMapping("/{id}")
    public JobDto estado(@PathVariable UUID id) {
        return JobDto.of(propio(id));
    }

    @GetMapping("/{id}/descarga")
    public ResponseEntity<byte[]> descargar(@PathVariable UUID id) {
        ReportJob j = propio(id);
        if (!"LISTO".equals(j.getEstado())) throw ApiException.conflict("REPORTE_NO_LISTO", "El reporte aún no está listo");
        String nombre = "reporte-" + j.getTipo().toLowerCase() + "-" + j.getCreadoEn().toString().substring(0, 10)
                + "." + j.getFormato().toLowerCase();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReportService.contentType(j.getFormato())))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
                .body(service.descargar(j));
    }

    private ReportJob propio(UUID id) {
        ReportJob j = jobs.findById(id).orElseThrow(() -> ApiException.notFound("Reporte"));
        if (!j.getSolicitadoPor().equals(CurrentUser.get().id())) throw ApiException.notFound("Reporte");
        return j;
    }
}
