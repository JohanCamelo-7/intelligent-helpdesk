package com.helpdesk.api.identity;

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
@Table(name = "refresh_tokens")
@Getter @Setter @NoArgsConstructor
public class RefreshToken {
    @Id private UUID id;
    @Column(name = "usuario_id") private UUID usuarioId;
    @Column(name = "token_hash") private String tokenHash;
    @Column(name = "expira_en") private Instant expiraEn;
    private boolean revocado;
    @Column(name = "creado_en") private Instant creadoEn;
}
