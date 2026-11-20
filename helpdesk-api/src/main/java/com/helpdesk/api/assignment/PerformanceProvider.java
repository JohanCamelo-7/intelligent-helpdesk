package com.helpdesk.api.assignment;

import com.helpdesk.api.analytics.TrinoGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Valor D del algoritmo: desempeño de cada técnico en una categoría (últimos 90 días),
 * leído de la capa Gold (gold.agg_rendimiento_tecnico). Si el lago no responde o aún
 * no tiene datos, devuelve un mapa vacío y el algoritmo usa 0,5 (queda en la explicación).
 */
@Component
public class PerformanceProvider {

    private static final Logger log = LoggerFactory.getLogger(PerformanceProvider.class);
    private static final Duration VIGENCIA = Duration.ofMinutes(5);
    private final TrinoGateway trino;
    /** Caché por categoría: la capa Gold cambia como mucho una vez por hora y en cargas masivas se pide miles de veces. */
    private final Map<String, Cacheado> cache = new ConcurrentHashMap<>();

    private record Cacheado(Map<UUID, Double> valores, Instant vence) {}

    public PerformanceProvider(TrinoGateway trino) { this.trino = trino; }

    public Map<UUID, Double> desempeno(String categoriaCodigo) {
        if (categoriaCodigo == null) return new HashMap<>();
        Cacheado c = cache.get(categoriaCodigo);
        if (c != null && c.vence().isAfter(Instant.now())) return c.valores();
        Map<UUID, Double> valores = consultar(categoriaCodigo);
        cache.put(categoriaCodigo, new Cacheado(Map.copyOf(valores), Instant.now().plus(VIGENCIA)));
        return valores;
    }

    private Map<UUID, Double> consultar(String categoriaCodigo) {
        Map<UUID, Double> out = new HashMap<>();
        try {
            List<Map<String, Object>> rows = trino.query(
                    "SELECT tecnico_id, desempeno FROM gold.agg_rendimiento_tecnico WHERE categoria_codigo = ? AND resueltos_90d > 0",
                    List.of(categoriaCodigo));
            for (Map<String, Object> r : rows) {
                Object d = r.get("desempeno");
                if (r.get("tecnico_id") != null && d instanceof Number n) {
                    out.put(UUID.fromString(r.get("tecnico_id").toString()), n.doubleValue());
                }
            }
        } catch (RuntimeException e) {
            log.info("Desempeño no disponible en la capa Gold ({}); se usa el valor neutro", e.getMessage());
        }
        return out;
    }
}
