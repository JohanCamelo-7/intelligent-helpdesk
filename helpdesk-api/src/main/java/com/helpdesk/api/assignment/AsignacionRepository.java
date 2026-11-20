package com.helpdesk.api.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AsignacionRepository extends JpaRepository<Asignacion, UUID> {
    List<Asignacion> findByTicketIdOrderByFechaAsc(UUID ticketId);
    List<Asignacion> findByTicketIdAndActivaTrue(UUID ticketId);
}
