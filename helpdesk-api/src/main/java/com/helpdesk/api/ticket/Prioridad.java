package com.helpdesk.api.ticket;

public enum Prioridad {
    BAJA(1), MEDIA(2), ALTA(3), CRITICA(4);

    private final int peso;
    Prioridad(int peso) { this.peso = peso; }
    public int peso() { return peso; }

    public static Prioridad dePeso(int peso) {
        int p = Math.max(1, Math.min(4, peso));
        for (Prioridad x : values()) if (x.peso == p) return x;
        return MEDIA;
    }

    public boolean alta() { return this == ALTA || this == CRITICA; }
}
