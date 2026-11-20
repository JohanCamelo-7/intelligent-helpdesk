package com.helpdesk.api.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EquipoRepository extends JpaRepository<Equipo, Long> {
    List<Equipo> findAllByOrderByCodigoInventarioAsc();
    List<Equipo> findBySedeId(Long sedeId);
}
