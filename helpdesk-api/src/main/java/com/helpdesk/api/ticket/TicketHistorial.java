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
@Table(name = "ticket_historial")
@Getter @Setter @NoArgsConstructor
public class TicketHistorial {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "ticket_id") private UUID ticketId;
    private String tipo;
    @Column(name = "valor_anterior") private String valorAnterior;
    @Column(name = "valor_nuevo") private String valorNuevo;
    private String detalle;
    @Column(name = "usuario_id") private UUID usuarioId;
    private Instant fecha;
}
