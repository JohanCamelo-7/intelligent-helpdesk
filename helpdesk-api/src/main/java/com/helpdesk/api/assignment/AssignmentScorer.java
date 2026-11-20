package com.helpdesk.api.assignment;

import com.helpdesk.api.technician.Nivel;
import com.helpdesk.api.ticket.Prioridad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Algoritmo explicable de asignación (función pura, sin acceso a datos).
 *
 * <pre>
 * Filtros: activo y disponible, atiende la categoría, carga &lt; carga máxima,
 *          y en prioridad CRÍTICA nivel SEMI_SENIOR o superior.
 * S = wE·E + wX·X + wC·(1 − C/Cmax) + wD·D
 *   E = 1 especialidad exacta en el tipo de falla, 0,5 solo la categoría
 *   X = junior 0,33 · semi-senior 0,66 · senior 1
 *   C = carga ponderada por prioridad (baja 1 … crítica 4)
 *   D = tickets de la categoría resueltos sin reapertura (90 días, capa Gold); 0,5 sin historial
 * En prioridad ALTA o CRÍTICA se intercambian wX y wC (pesa más la experiencia).
 * Asignación automática solo si confianza ≥ umbral y mejor puntaje ≥ umbral de puntaje.
 * </pre>
 */
public final class AssignmentScorer {

    public static final double D_SIN_HISTORIAL = 0.5;

    private AssignmentScorer() {}

    public enum Decision { AUTO, REVISION }

    public record Weights(double especialidad, double experiencia, double carga, double desempeno, String version) {
        public Weights paraPrioridad(Prioridad p) {
            return p != null && p.alta() ? new Weights(especialidad, carga, experiencia, desempeno, version) : this;
        }
    }

    public record Candidate(UUID id, String nombre, Nivel nivel, boolean especialidadExacta, boolean atiendeCategoria,
                            int carga, int cargaMaxima, Double desempeno, boolean disponible, boolean activo) {}

    public record Breakdown(UUID id, String nombre, double e, double x, double c, double d, boolean dSinHistorial,
                            int carga, int cargaMaxima, double puntaje, String explicacion) {}

    public record Excluded(UUID id, String nombre, String motivo) {}

    public record Result(List<Breakdown> ranking, List<Excluded> excluidos, Decision decision, String motivo,
                         Weights pesos) {
        public Breakdown mejor() { return ranking.isEmpty() ? null : ranking.get(0); }
    }

    public static Result evaluar(Prioridad prioridad, double confianza, List<Candidate> candidatos, Weights base,
                                 double umbralConfianza, double umbralPuntaje) {
        Weights w = base.paraPrioridad(prioridad);
        List<Breakdown> ranking = new ArrayList<>();
        List<Excluded> excluidos = new ArrayList<>();

        for (Candidate c : candidatos) {
            String motivo = exclusion(c, prioridad);
            if (motivo != null) {
                excluidos.add(new Excluded(c.id(), c.nombre(), motivo));
                continue;
            }
            double e = c.especialidadExacta() ? 1.0 : 0.5;
            double x = c.nivel().factor();
            double ratio = Math.min(1.0, (double) c.carga() / c.cargaMaxima());
            boolean sinD = c.desempeno() == null;
            double d = sinD ? D_SIN_HISTORIAL : clamp(c.desempeno());
            double s = w.especialidad() * e + w.experiencia() * x + w.carga() * (1 - ratio) + w.desempeno() * d;
            s = Math.round(s * 10000) / 10000.0;
            ranking.add(new Breakdown(c.id(), c.nombre(), e, x, ratio, d, sinD, c.carga(), c.cargaMaxima(), s,
                    explicar(c, d, sinD)));
        }

        ranking.sort(Comparator.comparingDouble(Breakdown::puntaje).reversed()
                .thenComparingInt(Breakdown::carga)
                .thenComparing(Breakdown::nombre));

        if (ranking.isEmpty()) {
            return new Result(ranking, excluidos, Decision.REVISION,
                    "No hay técnicos disponibles que atiendan esta categoría", w);
        }
        if (confianza < umbralConfianza) {
            return new Result(ranking, excluidos, Decision.REVISION,
                    String.format(Locale.ROOT, "La confianza del diagnóstico (%.2f) es menor que el umbral (%.2f)",
                            confianza, umbralConfianza), w);
        }
        if (ranking.get(0).puntaje() < umbralPuntaje) {
            return new Result(ranking, excluidos, Decision.REVISION,
                    String.format(Locale.ROOT, "El mejor puntaje (%.2f) es menor que el mínimo (%.2f)",
                            ranking.get(0).puntaje(), umbralPuntaje), w);
        }
        return new Result(ranking, excluidos, Decision.AUTO, ranking.get(0).explicacion(), w);
    }

    private static String exclusion(Candidate c, Prioridad prioridad) {
        if (!c.activo()) return "Usuario inactivo";
        if (!c.disponible()) return "No disponible";
        if (!c.atiendeCategoria()) return "No atiende esta categoría";
        if (c.carga() >= c.cargaMaxima()) return "Carga máxima alcanzada";
        if (prioridad == Prioridad.CRITICA && c.nivel() == Nivel.JUNIOR) return "Prioridad crítica requiere nivel semi-senior o superior";
        return null;
    }

    private static String explicar(Candidate c, double d, boolean sinD) {
        String esp = c.especialidadExacta() ? "especialidad exacta en el tipo de falla" : "atiende la categoría";
        String nivel = switch (c.nivel()) {
            case JUNIOR -> "nivel junior";
            case SEMI_SENIOR -> "nivel semi-senior";
            case SENIOR -> "nivel senior";
        };
        String desempeno = sinD ? "sin historial de desempeño" :
                String.format(Locale.ROOT, "desempeño %.0f%% sin reaperturas", d * 100);
        return String.format("Elegido por %s, %s, carga %d de %d y %s", esp, nivel, c.carga(), c.cargaMaxima(), desempeno);
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
