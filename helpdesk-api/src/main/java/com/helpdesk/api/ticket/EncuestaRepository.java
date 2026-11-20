package com.helpdesk.api.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncuestaRepository extends JpaRepository<Encuesta, UUID> {
    Optional<Encuesta> findByTicketId(UUID ticketId);
}
