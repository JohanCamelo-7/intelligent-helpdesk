package com.helpdesk.api.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
@Getter @Setter @NoArgsConstructor
public class OutboxEvent {
    @Id
    private UUID id;
    @Column(name = "aggregate_type") private String aggregateType;
    @Column(name = "aggregate_id") private String aggregateId;
    @Column(name = "event_type") private String eventType;
    @Column(name = "routing_key") private String routingKey;
    private String envelope;
    @Column(name = "occurred_at") private Instant occurredAt;
    @Column(name = "published_at") private Instant publishedAt;
    private int attempts;
}
