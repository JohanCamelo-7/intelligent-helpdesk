package com.helpdesk.api.technician;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TecnicoRepository extends JpaRepository<Tecnico, UUID> {
}
