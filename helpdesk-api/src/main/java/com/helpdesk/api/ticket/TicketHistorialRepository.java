package com.helpdesk.api.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketHistorialRepository extends JpaRepository<TicketHistorial, UUID> {
    List<TicketHistorial> findByTicketIdOrderByFechaAsc(UUID ticketId);
}
