package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Publishes saga events on the `oficina` exchange after the commit of the current transaction
 * (emitAfterCommit pattern of the monolith). Without a transaction it publishes right away.
 */
@Component
class RabbitEventPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val codec: EnvelopeCodec,
    private val clock: Clock,
) : EventPublisher {

    override fun publishAfterCommit(event: OutgoingEvent) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = send(event)
            })
        } else {
            send(event)
        }
    }

    internal fun send(event: OutgoingEvent) {
        val envelope = MessageEnvelope(
            messageId = UUID.randomUUID(),
            sagaId = event.sagaId,
            type = event.type.typeName,
            occurredAt = clock.instant().truncatedTo(ChronoUnit.MILLIS),
            correlationId = event.correlationId,
            payload = codec.toTree(event.payload),
        )
        try {
            rabbitTemplate.send(RabbitTopology.EXCHANGE, event.type.routingKey, codec.encode(envelope))
            log.info(
                "message.published type={} sagaId={} messageId={} routingKey={}",
                envelope.type, envelope.sagaId, envelope.messageId, event.type.routingKey,
            )
        } catch (e: AmqpException) {
            // The transaction is already committed: the failure is logged for manual replay.
            log.error(
                "message.publish.failed type={} sagaId={} messageId={} exceptionClass={}",
                envelope.type, envelope.sagaId, envelope.messageId, e.javaClass.name,
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RabbitEventPublisher::class.java)
    }
}
