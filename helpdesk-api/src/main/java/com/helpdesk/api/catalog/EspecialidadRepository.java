package com.helpdesk.api.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EspecialidadRepository extends JpaRepository<Especialidad, Long> {
    List<Especialidad> findAllByOrderByNombreAsc();
}
