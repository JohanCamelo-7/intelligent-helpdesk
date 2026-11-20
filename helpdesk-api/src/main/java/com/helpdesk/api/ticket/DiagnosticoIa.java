package com.helpdesk.api.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "diagnosticos_ia")
@Getter @Setter @NoArgsConstructor
public class DiagnosticoIa {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "ticket_id") private UUID ticketId;
    private String origen;
    @Column(name = "adjunto_id") private UUID adjuntoId;
    private String proveedor;
    private String modelo;
    @Column(name = "tipo_equipo") private String tipoEquipo;
    @Column(name = "categoria_codigo") private String categoriaCodigo;
    @Column(name = "tipo_falla_codigo") private String tipoFallaCodigo;
    private String descripcion;
    @Column(name = "causa_probable") private String causaProbable;
    private BigDecimal confianza;
    private boolean concluyente;
    private String evidencias;
    private String recomendacion;
    @Column(name = "especialidad_sugerida") private String especialidadSugerida;
    private String estado;
    @Column(name = "latencia_ms") private Integer latenciaMs;
    private Instant fecha;
    @Column(name = "updated_at") private Instant updatedAt;
}
