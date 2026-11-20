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
@Table(name = "encuestas")
@Getter @Setter @NoArgsConstructor
public class Encuesta {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "ticket_id") private UUID ticketId;
    @Column(name = "usuario_id") private UUID usuarioId;
    private short calificacion;
    private String comentario;
    private Instant fecha;
}
