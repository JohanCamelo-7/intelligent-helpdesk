package com.helpdesk.api.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface NotificacionRepository extends JpaRepository<Notificacion, UUID> {
    List<Notificacion> findByUsuarioIdOrderByFechaDesc(UUID usuarioId, Pageable pageable);
    long countByUsuarioIdAndLeidaFalse(UUID usuarioId);

    @Modifying
    @Query("update Notificacion n set n.leida = true where n.usuarioId = :usuarioId and n.leida = false")
    int marcarTodas(UUID usuarioId);
}
