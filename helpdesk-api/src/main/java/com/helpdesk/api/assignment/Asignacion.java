package com.helpdesk.api.assignment;

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
@Table(name = "asignaciones")
@Getter @Setter @NoArgsConstructor
public class Asignacion {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "ticket_id") private UUID ticketId;
    @Column(name = "tecnico_recomendado_id") private UUID tecnicoRecomendadoId;
    @Column(name = "tecnico_asignado_id") private UUID tecnicoAsignadoId;
    private String modo;
    @Column(name = "puntaje_recomendado") private BigDecimal puntajeRecomendado;
    @Column(name = "puntaje_asignado") private BigDecimal puntajeAsignado;
    private String motivo;
    private String explicacion;
    @Column(name = "pesos_version") private String pesosVersion;
    @Column(name = "asignado_por") private UUID asignadoPor;
    private boolean activa = true;
    private Instant fecha;
    @Column(name = "updated_at") private Instant updatedAt;
}
