package com.helpdesk.api.shared;

import com.helpdesk.api.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Relé del outbox: publica eventos pendientes con confirmación del broker. */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository repo;
    private final RabbitTemplate rabbit;

    public OutboxPublisher(OutboxRepository repo, RabbitTemplate rabbit) {
        this.repo = repo;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 250)   // lotes de 500 cada 250 ms (~2.000 eventos/s) para cargas masivas
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = repo.lockPending();
        if (pending.isEmpty()) return;
        try {
            rabbit.invoke(ops -> {
                for (OutboxEvent e : pending) {
                    ops.send(RabbitConfig.EXCHANGE, e.getRoutingKey(), toMessage(e));
                }
                ops.waitForConfirmsOrDie(10_000);
                return null;
            });
            Instant now = Instant.now();
            pending.forEach(e -> { e.setPublishedAt(now); e.setAttempts(e.getAttempts() + 1); });
        } catch (RuntimeException ex) {
            // Sin confirmación no se marca como publicado: se reintenta en el siguiente ciclo.
            log.warn("No se pudieron publicar {} eventos: {}", pending.size(), ex.getMessage());
            pending.forEach(e -> e.setAttempts(e.getAttempts() + 1));
        }
    }

    private Message toMessage(OutboxEvent e) {
        return MessageBuilder.withBody(e.getEnvelope().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("utf-8")
                .setMessageId(e.getId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("event_type", e.getEventType())
                .build();
    }
}
