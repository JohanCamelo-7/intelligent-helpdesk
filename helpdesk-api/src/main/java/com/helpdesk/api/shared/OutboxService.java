package com.helpdesk.api.shared;

import com.helpdesk.api.config.CorrelationFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Patrón transactional outbox: el evento se guarda en la MISMA transacción que el
 * cambio de negocio. OutboxPublisher lo envía luego a RabbitMQ (entrega al menos una vez).
 * El sobre es idéntico al que llega a Bronze en el Data Lake.
 */
@Service
public class OutboxService {

    public static final String SOURCE = "helpdesk-api";
    private final OutboxRepository repo;
    private final Json json;
    private final TimeProvider time;

    public OutboxService(OutboxRepository repo, Json json, TimeProvider time) {
        this.repo = repo;
        this.json = json;
        this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(String aggregateType, Object aggregateId, String eventType, Map<String, Object> payload) {
        return record(UUID.randomUUID(), aggregateType, aggregateId, eventType, payload, SOURCE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(UUID eventId, String aggregateType, Object aggregateId, String eventType,
                       Map<String, Object> payload, String source) {
        Instant now = time.now();
        Map<String, Object> envelope = Maps.of(
                "event_id", eventId.toString(),
                "event_type", eventType,
                "event_version", 1,
                "occurred_at", now.toString(),
                "aggregate_type", aggregateType,
                "aggregate_id", String.valueOf(aggregateId),
                "correlation_id", CorrelationFilter.current(),
                "source", source,
                "payload", payload);
        OutboxEvent e = new OutboxEvent();
        e.setId(eventId);
        e.setAggregateType(aggregateType);
        e.setAggregateId(String.valueOf(aggregateId));
        e.setEventType(eventType);
        e.setRoutingKey(routingKey(eventType));
        e.setEnvelope(json.write(envelope));
        e.setOccurredAt(now);
        repo.save(e);
        return eventId;
    }

    /** TicketCreated -> ticket.created ; AiDiagnosisCompleted -> ai.diagnosis.completed */
    public static String routingKey(String eventType) {
        return eventType.replaceAll("([a-z0-9])([A-Z])", "$1.$2").toLowerCase();
    }
}
