package com.soat.tech.challenge.oficina.billing.infrastructure.messaging.handler

import com.soat.tech.challenge.oficina.billing.application.GenerateQuoteCommand
import com.soat.tech.challenge.oficina.billing.application.PaymentApplicationService
import com.soat.tech.challenge.oficina.billing.application.QuoteApplicationService
import com.soat.tech.challenge.oficina.billing.application.RequestPaymentCommand
import com.soat.tech.challenge.oficina.billing.application.messaging.GenerateQuotePayload
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.RequestPaymentPayload
import com.soat.tech.challenge.oficina.billing.domain.model.RequestedItem
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.EnvelopeCodec
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.InvalidMessageException
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.MessageEnvelope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Handler of one command type. A new command (CancelQuote, RefundPayment) is one more
 * bean of this interface: the listener picks it up by [type] without changes.
 */
interface CommandHandler {
    val type: MessageType

    /** Runs inside the idempotency transaction. */
    fun handle(envelope: MessageEnvelope)
}

private fun requireSameSaga(envelope: MessageEnvelope, workOrderId: UUID) {
    if (envelope.sagaId != workOrderId) {
        throw InvalidMessageException("sagaId ${envelope.sagaId} differs from payload workOrderId $workOrderId")
    }
}

@Component
class GenerateQuoteHandler(
    private val codec: EnvelopeCodec,
    private val quotes: QuoteApplicationService,
) : CommandHandler {

    override val type = MessageType.GENERATE_QUOTE

    override fun handle(envelope: MessageEnvelope) {
        val payload = codec.payload(envelope, GenerateQuotePayload::class.java)
        requireSameSaga(envelope, payload.workOrderId)
        quotes.generate(
            GenerateQuoteCommand(
                workOrderId = payload.workOrderId,
                trackingCode = payload.trackingCode,
                customerName = payload.customer.name,
                customerEmail = payload.customer.email,
                services = payload.services.map { RequestedItem(it.code, it.quantity) },
                parts = payload.parts.map { RequestedItem(it.code, it.quantity) },
                correlationId = envelope.correlationId,
            ),
        )
    }
}

@Component
class RequestPaymentHandler(
    private val codec: EnvelopeCodec,
    private val payments: PaymentApplicationService,
) : CommandHandler {

    override val type = MessageType.REQUEST_PAYMENT

    override fun handle(envelope: MessageEnvelope) {
        val payload = codec.payload(envelope, RequestPaymentPayload::class.java)
        requireSameSaga(envelope, payload.workOrderId)
        payments.requestPayment(RequestPaymentCommand(payload.workOrderId, payload.quoteId, payload.amountCents))
    }
}
