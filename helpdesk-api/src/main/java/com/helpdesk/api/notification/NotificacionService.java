package com.helpdesk.api.notification;

import com.helpdesk.api.identity.Rol;
import com.helpdesk.api.identity.UsuarioRepository;
import com.helpdesk.api.shared.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Notificaciones dentro de la aplicación (bandeja del usuario). */
@Service
public class NotificacionService {

    private final NotificacionRepository repo;
    private final UsuarioRepository usuarios;
    private final TimeProvider time;

    public NotificacionService(NotificacionRepository repo, UsuarioRepository usuarios, TimeProvider time) {
        this.repo = repo;
        this.usuarios = usuarios;
        this.time = time;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void notificar(UUID usuarioId, String titulo, String mensaje, UUID ticketId) {
        // Los escenarios de demostración (hora desplazada) no generan notificaciones.
        if (usuarioId == null || time.isOverridden()) return;
        Notificacion n = new Notificacion();
        n.setUsuarioId(usuarioId);
        n.setTitulo(recortar(titulo, 160));
        n.setMensaje(recortar(mensaje, 600));
        n.setTicketId(ticketId);
        n.setFecha(time.now());
        repo.save(n);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void notificarAdmins(String titulo, String mensaje, UUID ticketId) {
        usuarios.findByRolAndActivoTrue(Rol.ADMIN).forEach(a -> notificar(a.getId(), titulo, mensaje, ticketId));
    }

    private static String recortar(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
