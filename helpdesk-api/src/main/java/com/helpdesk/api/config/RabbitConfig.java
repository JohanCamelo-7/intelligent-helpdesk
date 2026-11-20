package com.helpdesk.api.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de eventos:
 *  - helpdesk.events (topic): todos los eventos de dominio. Routing key = tipo en minúsculas con puntos.
 *  - helpdesk.ai-results: resultados de IA que consume este servicio (ai.#).
 *  - helpdesk.dlx / helpdesk.dlq: mensajes que fallan tras los reintentos.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "helpdesk.events";
    public static final String DLX = "helpdesk.dlx";
    public static final String AI_RESULTS_QUEUE = "helpdesk.ai-results";

    @Bean TopicExchange eventsExchange() { return new TopicExchange(EXCHANGE, true, false); }

    @Bean FanoutExchange deadLetterExchange() { return new FanoutExchange(DLX, true, false); }

    @Bean Queue deadLetterQueue() { return QueueBuilder.durable("helpdesk.dlq").build(); }

    @Bean Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange());
    }

    @Bean Queue aiResultsQueue() {
        return QueueBuilder.durable(AI_RESULTS_QUEUE).deadLetterExchange(DLX).build();
    }

    @Bean Binding aiResultsBinding() {
        return BindingBuilder.bind(aiResultsQueue()).to(eventsExchange()).with("ai.#");
    }
}
