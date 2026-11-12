package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarable
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.ExchangeBuilder
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Names of the saga topology. Identical in the three services. */
object RabbitTopology {
    const val EXCHANGE = "oficina"
    const val DEAD_LETTER_EXCHANGE = "oficina.dlx"

    const val BILLING_COMMANDS = "billing.commands"
    const val OS_EVENTS = "os.events"

    const val BILLING_COMMANDS_BINDING = "command.billing.#"
    const val OS_EVENTS_BINDING = "event.#"

    const val CORRELATION_ID_HEADER = "x-correlation-id"

    fun deadLetterQueue(queue: String) = "$queue.dlq"
}

/**
 * Declared on startup by the RabbitAdmin (idempotent): both exchanges, the queue this service consumes
 * (billing.commands) and the one it publishes to (os.events), each with its DLQ. Arguments must stay
 * identical to the other services, or the broker answers PRECONDITION_FAILED.
 */
@Configuration
class RabbitTopologyConfiguration {

    @Bean
    fun billingTopology(): Declarables {
        val exchange = ExchangeBuilder.topicExchange(RabbitTopology.EXCHANGE).durable(true).build<TopicExchange>()
        val deadLetterExchange =
            ExchangeBuilder.directExchange(RabbitTopology.DEAD_LETTER_EXCHANGE).durable(true).build<DirectExchange>()
        val declarables = mutableListOf<Declarable>(exchange, deadLetterExchange)
        declarables += queueWithDeadLetter(RabbitTopology.BILLING_COMMANDS, RabbitTopology.BILLING_COMMANDS_BINDING, exchange, deadLetterExchange)
        declarables += queueWithDeadLetter(RabbitTopology.OS_EVENTS, RabbitTopology.OS_EVENTS_BINDING, exchange, deadLetterExchange)
        return Declarables(declarables)
    }

    private fun queueWithDeadLetter(
        name: String,
        bindingKey: String,
        exchange: TopicExchange,
        deadLetterExchange: DirectExchange,
    ): List<Declarable> {
        val deadLetterKey = RabbitTopology.deadLetterQueue(name)
        // Durable, not exclusive, not auto-delete; no x-queue-type and no TTL.
        val queue: Queue = QueueBuilder.durable(name)
            .deadLetterExchange(RabbitTopology.DEAD_LETTER_EXCHANGE)
            .deadLetterRoutingKey(deadLetterKey)
            .build()
        val deadLetterQueue: Queue = QueueBuilder.durable(deadLetterKey).build()
        val binding: Binding = BindingBuilder.bind(queue).to(exchange).with(bindingKey)
        val deadLetterBinding: Binding = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(deadLetterKey)
        return listOf(queue, deadLetterQueue, binding, deadLetterBinding)
    }
}
