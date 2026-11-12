package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.handler.CommandHandler
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

/**
 * Consumer of `billing.commands`. Decodes the envelope, puts the saga correlation id in the MDC
 * (same key as the CorrelationIdFilter) and dispatches by `type`:
 * - handled command: idempotent processing (processed_message in the effect's transaction);
 * - contract command without a handler: WARN and ack;
 * - invalid JSON, type outside the contract or not a billing command: straight to the DLQ.
 * Any other failure is retried by the container (3 attempts) and then dead-lettered.
 */
@Component
class BillingCommandListener(
    private val codec: EnvelopeCodec,
    private val processor: IdempotentMessageProcessor,
    handlers: List<CommandHandler>,
) {

    private val handlers: Map<MessageType, CommandHandler> = handlers.associateBy { it.type }

    @RabbitListener(id = LISTENER_ID, queues = [RabbitTopology.BILLING_COMMANDS])
    fun onMessage(message: Message) {
        val envelope = codec.decode(message)
        val type = MessageType.fromTypeName(envelope.type)
            ?: throw InvalidMessageException("Type outside the contract: ${envelope.type}")
        if (type.kind != MessageType.Kind.COMMAND) {
            throw InvalidMessageException("Not a Billing command: ${envelope.type}")
        }
        withMdc(envelope) {
            log.info("message.consumed type={} sagaId={} messageId={}", envelope.type, envelope.sagaId, envelope.messageId)
            val handler = handlers[type]
            if (handler == null) {
                log.warn(
                    "message.ignored reason=no-handler type={} sagaId={} messageId={}",
                    envelope.type, envelope.sagaId, envelope.messageId,
                )
                return@withMdc
            }
            val applied = processor.process(envelope) { handler.handle(envelope) }
            if (!applied) {
                log.info(
                    "message.ignored reason=duplicate type={} sagaId={} messageId={}",
                    envelope.type, envelope.sagaId, envelope.messageId,
                )
            }
        }
    }

    private inline fun withMdc(envelope: MessageEnvelope, block: () -> Unit) {
        val previous = MDC.getCopyOfContextMap()
        MDC.put(REQUEST_ID_KEY, envelope.correlationId)
        MDC.put(SAGA_ID_KEY, envelope.sagaId.toString())
        MDC.put(MESSAGE_ID_KEY, envelope.messageId.toString())
        try {
            block()
        } finally {
            if (previous == null) MDC.clear() else MDC.setContextMap(previous)
        }
    }

    companion object {
        const val LISTENER_ID = "billing-commands"

        // Same MDC key as CorrelationIdFilter, so HTTP and message logs share the field.
        private const val REQUEST_ID_KEY = "requestId"
        private const val SAGA_ID_KEY = "sagaId"
        private const val MESSAGE_ID_KEY = "messageId"
        private val log = LoggerFactory.getLogger(BillingCommandListener::class.java)
    }
}
