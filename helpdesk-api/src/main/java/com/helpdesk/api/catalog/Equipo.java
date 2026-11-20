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
@Table(name = "equipos")
@Getter @Setter @NoArgsConstructor
public class Equipo {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "codigo_inventario") private String codigoInventario;
    private String tipo;
    private String marca;
    private String modelo;
    @Column(name = "sede_id") private Long sedeId;
    private boolean activo = true;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }
}
