package com.helpdesk.api.shared;

import com.helpdesk.api.analytics.ReportWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxAndReportTest {

    @Test
    void routingKeyDesdeTipoDeEvento() {
        assertThat(OutboxService.routingKey("TicketCreated")).isEqualTo("ticket.created");
        assertThat(OutboxService.routingKey("AiDiagnosisCompleted")).isEqualTo("ai.diagnosis.completed");
        assertThat(OutboxService.routingKey("TechnicianAssigned")).isEqualTo("technician.assigned");
    }

    @Test
    void csvEscapaFormulasYSeparadores() {
        var seccion = ReportWriter.Seccion.de("Prueba", List.of(Map.of("texto", "=CMD()"), Map.of("texto", "a;b")));
        var reporte = new ReportWriter.Reporte("Título", List.of("nota"), List.of(seccion));
        String csv = new String(ReportWriter.escribir(reporte, "CSV"), StandardCharsets.UTF_8);
        assertThat(csv).contains("'=CMD()").contains("\"a;b\"");
    }

    @Test
    void generaXlsxYPdf() {
        var seccion = ReportWriter.Seccion.de("Datos", List.of(Map.of("n", 1L, "x", 2.5)));
        var reporte = new ReportWriter.Reporte("T", List.of(), List.of(seccion));
        assertThat(ReportWriter.escribir(reporte, "XLSX")).startsWith((byte) 'P', (byte) 'K');
        assertThat(new String(ReportWriter.escribir(reporte, "PDF"), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void mapsAdmiteNulos() {
        Map<String, Object> m = Maps.of("a", null, "b", 2);
        assertThat(m).containsEntry("a", null).containsEntry("b", 2);
    }
}
