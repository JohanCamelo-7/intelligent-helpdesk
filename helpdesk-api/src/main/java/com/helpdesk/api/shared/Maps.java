package com.helpdesk.api.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mapa ordenado que admite valores nulos (Map.of no los admite). */
public final class Maps {
    private Maps() {}

    public static Map<String, Object> of(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("Pares clave/valor incompletos");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static double dbl(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return def;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return def; }
    }

    public static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }
}
