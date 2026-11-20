package com.helpdesk.api.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.util.UUID;

/** Registro de auditoría con la IP real del cliente (CF-Connecting-IP detrás de Cloudflare). */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final TimeProvider time;

    public AuditService(JdbcTemplate jdbc, TimeProvider time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    public void log(UUID userId, String accion, String recurso, String recursoId, String detalle) {
        String d = detalle != null && detalle.length() > 500 ? detalle.substring(0, 500) : detalle;
        jdbc.update("INSERT INTO audit_log (usuario_id, accion, recurso, recurso_id, ip, detalle, fecha) VALUES (?,?,?,?,?,?,?)",
                userId, accion, recurso, recursoId, clientIp(), d, Timestamp.from(time.now()));
    }

    public static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest req = attrs.getRequest();
        String cf = req.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf;
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real;
        return req.getRemoteAddr();
    }
}
