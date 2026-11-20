package com.helpdesk.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sedes")
@Getter @Setter @NoArgsConstructor
public class Sede {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String codigo;
    private String nombre;
    private String ciudad;
    private boolean activo = true;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }
}
