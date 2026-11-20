package com.helpdesk.api.ticket;

import com.helpdesk.api.config.RabbitConfig;
import com.helpdesk.api.shared.Json;
import com.helpdesk.api.shared.ProcessedEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Consume resultados del ai-service (AiDiagnosisCompleted) de forma idempotente. */
@Component
public class AiResultListener {

    private static final Logger log = LoggerFactory.getLogger(AiResultListener.class);
    private final Json json;
    private final ProcessedEventStore processed;
    private final TicketService tickets;
    private final TransactionTemplate tx;

    public AiResultListener(Json json, ProcessedEventStore processed, TicketService tickets, TransactionTemplate tx) {
        this.json = json;
        this.processed = processed;
        this.tickets = tickets;
        this.tx = tx;
    }

    @RabbitListener(queues = RabbitConfig.AI_RESULTS_QUEUE)
    @SuppressWarnings("unchecked")
    public void onMessage(Message message) {
        Map<String, Object> envelope = json.readMap(new String(message.getBody(), StandardCharsets.UTF_8));
        String type = String.valueOf(envelope.get("event_type"));
        if (!"AiDiagnosisCompleted".equals(type)) return;
        UUID eventId = UUID.fromString(String.valueOf(envelope.get("event_id")));
        Object payload = envelope.get("payload");
        if (!(payload instanceof Map<?, ?>)) {
            log.warn("Evento {} sin payload; se descarta", eventId);
            return;
        }
        tx.executeWithoutResult(status -> {
            if (!processed.markIfNew(eventId)) {
                log.debug("Evento {} ya procesado", eventId);
                return;
            }
            tickets.aplicarResultadoIa((Map<String, Object>) payload);
        });
    }
}
