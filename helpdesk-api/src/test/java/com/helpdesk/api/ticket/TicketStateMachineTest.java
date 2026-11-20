package com.helpdesk.api.ticket;

import com.helpdesk.api.identity.Rol;
import com.helpdesk.api.shared.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");
    private static final TicketStateMachine.Actor TECNICO = new TicketStateMachine.Actor(Rol.TECNICO, false, true);
    private static final TicketStateMachine.Actor OTRO_TECNICO = new TicketStateMachine.Actor(Rol.TECNICO, false, false);
    private static final TicketStateMachine.Actor DUENO = new TicketStateMachine.Actor(Rol.USER, true, false);
    private static final TicketStateMachine.Actor ADMIN = new TicketStateMachine.Actor(Rol.ADMIN, false, false);

    private static TicketStateMachine.Contexto ctx(boolean tecnico, Instant fin, String sol, Long tipo) {
        return new TicketStateMachine.Contexto(tecnico, fin, NOW, sol, tipo);
    }

    @Test
    void iniciarRequiereTecnicoAsignado() {
        assertThatThrownBy(() -> TicketStateMachine.validar(EstadoTicket.PENDIENTE, EstadoTicket.EN_CURSO, ADMIN, ctx(false, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Asigna un técnico");
        assertThatCode(() -> TicketStateMachine.validar(EstadoTicket.PENDIENTE, EstadoTicket.EN_CURSO, TECNICO, ctx(true, null, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void unTecnicoNoAsignadoNoPuedeIniciar() {
        assertThatThrownBy(() -> TicketStateMachine.validar(EstadoTicket.PENDIENTE, EstadoTicket.EN_CURSO, OTRO_TECNICO, ctx(true, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("permiso");
    }

    @Test
    void finalizarExigeSolucionYTipoDeFalla() {
        assertThatThrownBy(() -> TicketStateMachine.validar(EstadoTicket.EN_CURSO, EstadoTicket.FINALIZADA, TECNICO, ctx(true, null, "corto", 1L)))
                .hasMessageContaining("solución");
        assertThatThrownBy(() -> TicketStateMachine.validar(EstadoTicket.EN_CURSO, EstadoTicket.FINALIZADA, TECNICO, ctx(true, null, "Se cambió el cable dañado", null)))
                .hasMessageContaining("tipo de falla");
        assertThatCode(() -> TicketStateMachine.validar(EstadoTicket.EN_CURSO, EstadoTicket.FINALIZADA, TECNICO, ctx(true, null, "Se cambió el cable dañado", 3L)))
                .doesNotThrowAnyException();
    }

    @Test
    void elSolicitanteSoloCancelaMientrasEstaPendiente() {
        assertThatCode(() -> TicketStateMachine.validar(EstadoTicket.PENDIENTE, EstadoTicket.CANCELADA, DUENO, ctx(false, null, null, null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TicketStateMachine.validar(EstadoTicket.EN_CURSO, EstadoTicket.CANCELADA, DUENO, ctx(true, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void reaperturaDentroDeSieteDias() {
        Instant hace3 = NOW.minus(Duration.ofDays(3));
        Instant hace8 = NOW.minus(Duration.ofDays(8));
        assertThatCode(() -> TicketStateMachine.validar(EstadoTicket.FINALIZADA, EstadoTicket.EN_CURSO, DUENO, ctx(true, hace3, null, null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TicketStateMachine.validar(EstadoTicket.FINALIZADA, EstadoTicket.EN_CURSO, DUENO, ctx(true, hace8, null, null)))
                .hasMessageContaining("7 días");
        assertThatCode(() -> TicketStateMachine.validar(EstadoTicket.FINALIZADA, EstadoTicket.EN_CURSO, ADMIN, ctx(true, hace8, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void transicionesInvalidas() {
        assertThatThrownBy(() -> TicketStateMachine.validar(EstadoTicket.PENDIENTE, EstadoTicket.FINALIZADA, ADMIN, ctx(true, null, "solución larga ok", 1L)))
                .hasMessageContaining("directamente");
        assertThatThrownBy(() -> TicketStateMachine.validar(EstadoTicket.CANCELADA, EstadoTicket.EN_CURSO, ADMIN, ctx(true, null, null, null)))
                .hasMessageContaining("cancelado");
    }
}
