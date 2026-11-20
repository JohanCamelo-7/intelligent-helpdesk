package com.helpdesk.api.ticket;

import java.util.Set;

/**
 * Reglas definidas para estimar la prioridad (no la decide el modelo de IA).
 *
 * <pre>
 * puntaje = impacto (individual 1, área 2, sede 3) + 1 si bloquea el trabajo
 * 1 -> BAJA, 2 -> MEDIA, 3 -> ALTA, 4 -> CRÍTICA
 * Si la categoría es REDES o ACCESOS y el impacto es de área o sede, sube un nivel
 * (una caída de red o de cuentas detiene a varias personas a la vez).
 * </pre>
 */
public final class PriorityRules {

    private static final Set<String> CATEGORIAS_CRITICAS = Set.of("REDES", "ACCESOS");

    private PriorityRules() {}

    public static Prioridad estimar(Impacto impacto, boolean bloqueaTrabajo, String categoriaCodigo) {
        int puntaje = switch (impacto) {
            case INDIVIDUAL -> 1;
            case AREA -> 2;
            case SEDE -> 3;
        };
        if (bloqueaTrabajo) puntaje++;
        if (categoriaCodigo != null && CATEGORIAS_CRITICAS.contains(categoriaCodigo) && impacto != Impacto.INDIVIDUAL) {
            puntaje++;
        }
        return Prioridad.dePeso(puntaje);
    }
}
