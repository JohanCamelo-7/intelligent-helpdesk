package com.helpdesk.api.ticket;

import com.helpdesk.api.shared.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TicketHistoryService {

    private final TicketHistorialRepository repo;
    private final TimeProvider time;

    public TicketHistoryService(TicketHistorialRepository repo, TimeProvider time) {
        this.repo = repo;
        this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(UUID ticketId, String tipo, Object anterior, Object nuevo, String detalle, UUID usuarioId) {
        TicketHistorial h = new TicketHistorial();
        h.setTicketId(ticketId);
        h.setTipo(tipo);
        h.setValorAnterior(anterior == null ? null : recortar(anterior.toString()));
        h.setValorNuevo(nuevo == null ? null : recortar(nuevo.toString()));
        h.setDetalle(detalle);
        h.setUsuarioId(usuarioId);
        h.setFecha(time.now());
        repo.save(h);
    }

    private static String recortar(String s) { return s.length() > 200 ? s.substring(0, 200) : s; }
}
