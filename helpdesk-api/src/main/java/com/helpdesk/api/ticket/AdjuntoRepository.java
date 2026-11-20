package com.helpdesk.api.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdjuntoRepository extends JpaRepository<Adjunto, UUID> {
    List<Adjunto> findByTicketIdOrderByFechaAsc(UUID ticketId);
    long countByTicketId(UUID ticketId);
}
