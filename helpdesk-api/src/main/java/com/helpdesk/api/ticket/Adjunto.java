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
@Table(name = "adjuntos")
@Getter @Setter @NoArgsConstructor
public class Adjunto {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "ticket_id") private UUID ticketId;
    @Column(name = "object_key") private String objectKey;
    @Column(name = "nombre_original") private String nombreOriginal;
    private String mime;
    private long tamano;
    private String sha256;
    private Integer ancho;
    private Integer alto;
    @Column(name = "subido_por") private UUID subidoPor;
    private Instant fecha;
    @Column(name = "updated_at") private Instant updatedAt;
}
