package com.helpdesk.api.agent;

import com.helpdesk.api.shared.CurrentUser;
import com.helpdesk.api.shared.Maps;
import com.helpdesk.api.shared.OutboxService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadatos de las sesiones con el agente de IA (no la conversación ni el audio).
 * El ai-service los envía con el JWT del propio usuario al terminar la sesión.
 */
@RestController
@RequestMapping("/api/v1/agente/sesiones")
public class AgenteController {

    private final JdbcTemplate jdbc;
    private final OutboxService outbox;

    public AgenteController(JdbcTemplate jdbc, OutboxService outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record SesionRequest(@NotNull UUID sesionId, @NotNull Instant inicio, @NotNull Instant fin,
                                @Min(0) @Max(500) int turnos, @Size(max = 40) String intencion, UUID ticketId,
                                boolean usoVoz, boolean consentimiento, @Size(max = 40) String proveedor) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public void registrar(@Valid @RequestBody SesionRequest r) {
        UUID usuario = CurrentUser.get().id();
        int n = jdbc.update("""
                INSERT INTO agente_sesiones (id, usuario_id, inicio, fin, turnos, intencion, ticket_id, uso_voz, consentimiento, proveedor)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING""",
                r.sesionId(), usuario, Timestamp.from(r.inicio()), Timestamp.from(r.fin()), r.turnos(), r.intencion(),
                r.ticketId(), r.usoVoz(), r.consentimiento(), r.proveedor());
        if (n == 0) return;
        outbox.record("AgentSession", r.sesionId(), "AgentSessionEnded", Maps.of(
                "sesion_id", r.sesionId().toString(),
                "usuario_id", usuario.toString(),
                "inicio", r.inicio().toString(),
                "fin", r.fin().toString(),
                "duracion_seg", Math.max(0, r.fin().getEpochSecond() - r.inicio().getEpochSecond()),
                "turnos", r.turnos(),
                "intencion", r.intencion(),
                "ticket_id", r.ticketId() == null ? null : r.ticketId().toString(),
                "uso_voz", r.usoVoz(),
                "consentimiento", r.consentimiento(),
                "proveedor", r.proveedor()));
    }
}
