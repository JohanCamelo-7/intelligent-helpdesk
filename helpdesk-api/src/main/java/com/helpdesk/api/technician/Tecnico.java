package com.helpdesk.api.technician;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Perfil técnico. Su identificador es el mismo del usuario. */
@Entity
@Table(name = "tecnicos")
@Getter @Setter @NoArgsConstructor
public class Tecnico {
    @Id
    @Column(name = "usuario_id")
    private UUID usuarioId;
    @Enumerated(EnumType.STRING) private Nivel nivel;
    private boolean disponible = true;
    @Column(name = "carga_maxima") private int cargaMaxima = 12;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tecnico_especialidades", joinColumns = @JoinColumn(name = "tecnico_id"))
    @Column(name = "especialidad_id")
    private Set<Long> especialidadIds = new HashSet<>();
    @Version private Long version;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }
}
