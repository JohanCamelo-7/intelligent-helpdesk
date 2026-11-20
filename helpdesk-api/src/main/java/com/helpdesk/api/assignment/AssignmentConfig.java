package com.helpdesk.api.assignment;

import com.helpdesk.api.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/** Pesos y umbrales del algoritmo, editables por el ADMIN (tabla configuracion). */
@Component
public class AssignmentConfig {

    private final JdbcTemplate jdbc;

    public AssignmentConfig(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Valores(AssignmentScorer.Weights pesos, double umbralConfianza, double umbralPuntaje) {}

    public Valores leer() {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT clave, valor FROM configuracion WHERE clave LIKE 'asignacion.%'",
                rs -> { m.put(rs.getString(1), rs.getString(2)); });
        return new Valores(new AssignmentScorer.Weights(
                d(m, "asignacion.peso.especialidad", 0.40), d(m, "asignacion.peso.experiencia", 0.20),
                d(m, "asignacion.peso.carga", 0.25), d(m, "asignacion.peso.desempeno", 0.15),
                m.getOrDefault("asignacion.version", "v1")),
                d(m, "asignacion.umbral.confianza", 0.75), d(m, "asignacion.umbral.puntaje", 0.60));
    }

    @Transactional
    public Valores guardar(double e, double x, double c, double dd, double umbralConfianza, double umbralPuntaje) {
        double suma = e + x + c + dd;
        if (Math.abs(suma - 1.0) > 0.001) throw ApiException.badRequest("PESOS_INVALIDOS", "Los cuatro pesos deben sumar 1");
        if (umbralConfianza < 0 || umbralConfianza > 1 || umbralPuntaje < 0 || umbralPuntaje > 1) {
            throw ApiException.badRequest("UMBRAL_INVALIDO", "Los umbrales deben estar entre 0 y 1");
        }
        String version = "v" + (System.currentTimeMillis() / 1000);
        upsert("asignacion.peso.especialidad", e);
        upsert("asignacion.peso.experiencia", x);
        upsert("asignacion.peso.carga", c);
        upsert("asignacion.peso.desempeno", dd);
        upsert("asignacion.umbral.confianza", umbralConfianza);
        upsert("asignacion.umbral.puntaje", umbralPuntaje);
        jdbc.update("UPDATE configuracion SET valor = ?, updated_at = now() WHERE clave = 'asignacion.version'", version);
        return leer();
    }

    private void upsert(String k, double v) {
        jdbc.update("INSERT INTO configuracion (clave, valor) VALUES (?, ?) ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor, updated_at = now()",
                k, String.valueOf(v));
    }

    private static double d(Map<String, String> m, String k, double def) {
        try { return Double.parseDouble(m.getOrDefault(k, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }
}
