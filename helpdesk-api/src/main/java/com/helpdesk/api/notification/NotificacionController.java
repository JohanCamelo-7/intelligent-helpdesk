package com.helpdesk.api.notification;

import com.helpdesk.api.shared.ApiException;
import com.helpdesk.api.shared.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notificaciones")
public class NotificacionController {

    private final NotificacionRepository repo;

    public NotificacionController(NotificacionRepository repo) { this.repo = repo; }

    public record NotificacionDto(UUID id, String titulo, String mensaje, UUID ticketId, boolean leida, Instant fecha) {}
    public record Bandeja(long noLeidas, List<NotificacionDto> items) {}

    @GetMapping
    public Bandeja listar() {
        UUID me = CurrentUser.get().id();
        List<NotificacionDto> items = repo.findByUsuarioIdOrderByFechaDesc(me, PageRequest.of(0, 30)).stream()
                .map(n -> new NotificacionDto(n.getId(), n.getTitulo(), n.getMensaje(), n.getTicketId(), n.isLeida(), n.getFecha()))
                .toList();
        return new Bandeja(repo.countByUsuarioIdAndLeidaFalse(me), items);
    }

    @PostMapping("/{id}/leida")
    @Transactional
    public void marcar(@PathVariable UUID id) {
        Notificacion n = repo.findById(id).orElseThrow(() -> ApiException.notFound("Notificación"));
        if (!n.getUsuarioId().equals(CurrentUser.get().id())) throw ApiException.forbidden();
        n.setLeida(true);
    }

    @PostMapping("/leidas")
    @Transactional
    public void marcarTodas() {
        repo.marcarTodas(CurrentUser.get().id());
    }
}
