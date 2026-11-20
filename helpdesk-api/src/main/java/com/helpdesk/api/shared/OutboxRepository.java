package com.helpdesk.api.shared;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = "SELECT * FROM outbox WHERE published_at IS NULL ORDER BY occurred_at LIMIT 500 FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<OutboxEvent> lockPending();
}
