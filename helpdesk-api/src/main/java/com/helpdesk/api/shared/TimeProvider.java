package com.helpdesk.api.shared;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Fuente única de la hora actual. El generador de escenarios de demostración la
 * desplaza al pasado dentro de su propio hilo; el resto del sistema usa la hora real.
 */
@Component
public class TimeProvider {

    private static final ThreadLocal<Instant> OVERRIDE = new ThreadLocal<>();

    public Instant now() {
        Instant o = OVERRIDE.get();
        return o != null ? o : Instant.now();
    }

    public <T> T at(Instant instant, Supplier<T> work) {
        Instant previous = OVERRIDE.get();
        OVERRIDE.set(instant);
        try {
            return work.get();
        } finally {
            if (previous == null) OVERRIDE.remove(); else OVERRIDE.set(previous);
        }
    }

    public boolean isOverridden() {
        return OVERRIDE.get() != null;
    }
}
