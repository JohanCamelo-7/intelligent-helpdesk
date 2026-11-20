package com.helpdesk.api.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Idempotencia de consumidores: un event_id se procesa una sola vez. */
@Component
public class ProcessedEventStore {
    private final JdbcTemplate jdbc;

    public ProcessedEventStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** @return true si es la primera vez que se ve el evento (y queda registrado). */
    public boolean markIfNew(UUID eventId) {
        return jdbc.update("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 1;
    }
}
