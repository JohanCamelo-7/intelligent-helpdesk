package com.helpdesk.api.ticket;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
@Getter @Setter @NoArgsConstructor
public class Ticket {
    @Id private UUID id = UUID.randomUUID();
    private String numero;
    @Column(name = "solicitante_id") private UUID solicitanteId;
    @Column(name = "cargo_solicitante") private String cargoSolicitante;
    @Column(name = "correo_solicitante") private String correoSolicitante;
    @Column(name = "sede_id") private Long sedeId;
    @Column(name = "equipo_id") private Long equipoId;
    private String descripcion;
    @Enumerated(EnumType.STRING) private Impacto impacto;
    @Column(name = "bloquea_trabajo") private boolean bloqueaTrabajo;
    @Enumerated(EnumType.STRING) private Canal canal;
    @Column(name = "categoria_id") private Long categoriaId;
    @Column(name = "tipo_falla_id") private Long tipoFallaId;
    @Enumerated(EnumType.STRING) @Column(name = "prioridad_codigo") private Prioridad prioridad;
    @Enumerated(EnumType.STRING) @Column(name = "estado_codigo") private EstadoTicket estado;
    @Column(name = "tecnico_id") private UUID tecnicoId;
    @Column(name = "tecnico_recomendado_id") private UUID tecnicoRecomendadoId;
    @Column(name = "recomendacion_json") private String recomendacionJson;
    @Column(name = "requiere_revision") private boolean requiereRevision;
    @Column(name = "ai_confianza") private BigDecimal aiConfianza;
    @Column(name = "ia_categoria_codigo") private String iaCategoriaCodigo;
    @Column(name = "ia_tipo_falla_codigo") private String iaTipoFallaCodigo;
    private int reaperturas;
    private String solucion;
    @Column(name = "es_demo") private boolean esDemo;
    @Column(name = "fecha_creacion") private Instant fechaCreacion;
    @Column(name = "fecha_asignacion") private Instant fechaAsignacion;
    @Column(name = "fecha_inicio_atencion") private Instant fechaInicioAtencion;
    @Column(name = "fecha_finalizacion") private Instant fechaFinalizacion;
    @Version private Long version;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }
}
