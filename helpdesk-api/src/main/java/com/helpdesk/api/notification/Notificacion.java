package com.helpdesk.api.notification;

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
@Table(name = "notificaciones")
@Getter @Setter @NoArgsConstructor
public class Notificacion {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "usuario_id") private UUID usuarioId;
    private String titulo;
    private String mensaje;
    @Column(name = "ticket_id") private UUID ticketId;
    private boolean leida;
    private Instant fecha;
}
