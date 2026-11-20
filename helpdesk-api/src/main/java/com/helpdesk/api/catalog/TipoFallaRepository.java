package com.helpdesk.api.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TipoFallaRepository extends JpaRepository<TipoFalla, Long> {
    Optional<TipoFalla> findByCodigo(String codigo);
    List<TipoFalla> findAllByOrderByNombreAsc();
}
