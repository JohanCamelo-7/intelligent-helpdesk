package com.helpdesk.api.technician;

public enum Nivel {
    JUNIOR(0.33), SEMI_SENIOR(0.66), SENIOR(1.0);

    private final double factor;
    Nivel(double factor) { this.factor = factor; }
    /** Valor X del algoritmo de asignación. */
    public double factor() { return factor; }
}
