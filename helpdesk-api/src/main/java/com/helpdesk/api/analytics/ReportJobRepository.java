package com.helpdesk.api.analytics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {
    List<ReportJob> findTop20BySolicitadoPorOrderByCreadoEnDesc(UUID solicitadoPor);
}
