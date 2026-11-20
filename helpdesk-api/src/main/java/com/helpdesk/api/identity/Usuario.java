package com.helpdesk.api.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usuarios")
@Getter @Setter @NoArgsConstructor
public class Usuario {
    @Id
    private UUID id = UUID.randomUUID();
    private String email;
    @Column(name = "password_hash") private String passwordHash;
    private String nombre;
    private String cargo;
    private String area;
    @Column(name = "sede_id") private Long sedeId;
    @Enumerated(EnumType.STRING) private Rol rol;
    private boolean activo = true;
    @Column(name = "intentos_fallidos") private int intentosFallidos;
    @Column(name = "bloqueado_hasta") private Instant bloqueadoHasta;
    @Version private Long version;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
