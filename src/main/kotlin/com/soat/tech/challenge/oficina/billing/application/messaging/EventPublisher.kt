package com.soat.tech.challenge.oficina.billing.application.messaging

import java.util.UUID

/** Event to publish on the `oficina` exchange; the envelope (messageId, occurredAt) is built on send. */
data class OutgoingEvent(
    val type: MessageType,
    /** Always the work order id. */
    val sagaId: UUID,
    /** Saga correlation id stored with the quote/payment. */
    val correlationId: String,
    val payload: Any,
) {
    init {
        require(type.kind == MessageType.Kind.EVENT) { "Billing publishes only events: ${type.typeName}" }
    }
}

/**
 * Publication port. Inside a transaction the event leaves only after the commit
 * (emitAfterCommit of the monolith); a rollback discards it.
 */
interface EventPublisher {
    fun publishAfterCommit(event: OutgoingEvent)
}
