package com.helpdesk.api.ticket;

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
@Table(name = "actividades")
@Getter @Setter @NoArgsConstructor
public class Actividad {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "ticket_id") private UUID ticketId;
    @Column(name = "tecnico_id") private UUID tecnicoId;
    private String tipo;
    private String descripcion;
    private int minutos;
    private Instant fecha;
    @Column(name = "updated_at") private Instant updatedAt;
}
