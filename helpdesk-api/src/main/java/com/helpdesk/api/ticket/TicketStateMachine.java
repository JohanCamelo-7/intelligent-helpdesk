package com.helpdesk.api.ticket;

import com.helpdesk.api.identity.Rol;
import com.helpdesk.api.shared.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Reglas documentadas de cambio de estado (docs/arquitectura/reglas-de-estado.md).
 *
 * <pre>
 * PENDIENTE  -> EN_CURSO    técnico asignado o ADMIN; requiere técnico asignado
 * EN_CURSO   -> FINALIZADA  técnico asignado o ADMIN; requiere solución y tipo de falla final
 * PENDIENTE  -> CANCELADA   ADMIN, o el solicitante mientras siga PENDIENTE
 * EN_CURSO   -> CANCELADA   ADMIN
 * FINALIZADA -> EN_CURSO    reapertura: ADMIN, o el solicitante hasta 7 días después del cierre
 * </pre>
 */
public final class TicketStateMachine {

    public static final Duration VENTANA_REAPERTURA = Duration.ofDays(7);

    private TicketStateMachine() {}

    public record Actor(Rol rol, boolean esSolicitante, boolean esTecnicoAsignado) {
        boolean admin() { return rol == Rol.ADMIN; }
    }

    public record Contexto(boolean tieneTecnico, Instant fechaFinalizacion, Instant ahora,
                           String solucion, Long tipoFallaFinalId) {}

    public static void validar(EstadoTicket desde, EstadoTicket hacia, Actor actor, Contexto ctx) {
        if (desde == hacia) throw invalida("El ticket ya está en estado " + hacia);
        switch (desde) {
            case PENDIENTE -> {
                if (hacia == EstadoTicket.EN_CURSO) {
                    if (!ctx.tieneTecnico()) throw invalida("Asigna un técnico antes de iniciar la atención");
                    requerir(actor.admin() || actor.esTecnicoAsignado());
                } else if (hacia == EstadoTicket.CANCELADA) {
                    requerir(actor.admin() || actor.esSolicitante());
                } else {
                    throw invalida("Un ticket pendiente no puede pasar directamente a " + hacia);
                }
            }
            case EN_CURSO -> {
                if (hacia == EstadoTicket.FINALIZADA) {
                    requerir(actor.admin() || actor.esTecnicoAsignado());
                    if (ctx.solucion() == null || ctx.solucion().trim().length() < 10) {
                        throw invalida("Documenta la solución (mínimo 10 caracteres) para finalizar");
                    }
                    if (ctx.tipoFallaFinalId() == null) throw invalida("Indica el tipo de falla confirmado");
                } else if (hacia == EstadoTicket.CANCELADA) {
                    requerir(actor.admin());
                } else {
                    throw invalida("Un ticket en curso no puede volver a " + hacia);
                }
            }
            case FINALIZADA -> {
                if (hacia != EstadoTicket.EN_CURSO) throw invalida("Un ticket finalizado solo puede reabrirse");
                if (actor.admin()) return;
                requerir(actor.esSolicitante());
                if (ctx.fechaFinalizacion() != null
                        && ctx.fechaFinalizacion().plus(VENTANA_REAPERTURA).isBefore(ctx.ahora())) {
                    throw invalida("Solo puedes reabrir un ticket hasta 7 días después de su cierre");
                }
            }
            case CANCELADA -> throw invalida("Un ticket cancelado no puede cambiar de estado");
        }
    }

    private static void requerir(boolean condicion) {
        if (!condicion) throw ApiException.forbidden();
    }

    private static ApiException invalida(String msg) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSICION_INVALIDA", msg);
    }
}
