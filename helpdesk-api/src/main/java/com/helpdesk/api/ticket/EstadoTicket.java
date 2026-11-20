package com.helpdesk.api.ticket;

public enum EstadoTicket {
    PENDIENTE, EN_CURSO, FINALIZADA, CANCELADA;

    public boolean abierto() { return this == PENDIENTE || this == EN_CURSO; }
}
