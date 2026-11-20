package com.helpdesk.api.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_jobs")
@Getter @Setter @NoArgsConstructor
public class ReportJob {
    @Id private UUID id;
    @Column(name = "solicitado_por") private UUID solicitadoPor;
    private String tipo;
    private String formato;
    private String parametros;
    private String estado;
    @Column(name = "object_key") private String objectKey;
    private String error;
    @Column(name = "creado_en") private Instant creadoEn;
    @Column(name = "terminado_en") private Instant terminadoEn;
}
