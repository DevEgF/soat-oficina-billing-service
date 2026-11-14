package com.soat.tech.challenge.oficina.billing.infrastructure.messaging.handler

import com.soat.tech.challenge.oficina.billing.application.CancelQuoteCommand
import com.soat.tech.challenge.oficina.billing.application.GenerateQuoteCommand
import com.soat.tech.challenge.oficina.billing.application.PaymentApplicationService
import com.soat.tech.challenge.oficina.billing.application.QuoteApplicationService
import com.soat.tech.challenge.oficina.billing.application.RefundPaymentCommand
import com.soat.tech.challenge.oficina.billing.application.RequestPaymentCommand
import com.soat.tech.challenge.oficina.billing.application.messaging.CancelQuotePayload
import com.soat.tech.challenge.oficina.billing.application.messaging.GenerateQuotePayload
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.RefundPaymentPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.RequestPaymentPayload
import com.soat.tech.challenge.oficina.billing.domain.model.RequestedItem
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.EnvelopeCodec
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.InvalidMessageException
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.MessageEnvelope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Handler of one command type. Each command is one bean of this interface: the listener picks it
 * up by [type] without changes.
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
        payments.requestPayment(
            RequestPaymentCommand(
                workOrderId = payload.workOrderId,
                quoteId = payload.quoteId,
                amountCents = payload.amountCents,
                // Kept with the payment: the periodic check continues this trace when it resolves it.
                traceContext = envelope.traceContext,
            ),
        )
    }
}

@Component
class CancelQuoteHandler(
    private val codec: EnvelopeCodec,
    private val quotes: QuoteApplicationService,
) : CommandHandler {

    override val type = MessageType.CANCEL_QUOTE

    override fun handle(envelope: MessageEnvelope) {
        val payload = codec.payload(envelope, CancelQuotePayload::class.java)
        requireSameSaga(envelope, payload.workOrderId)
        quotes.cancel(CancelQuoteCommand(payload.workOrderId, payload.quoteId, payload.reason))
    }
}

@Component
class RefundPaymentHandler(
    private val codec: EnvelopeCodec,
    private val payments: PaymentApplicationService,
) : CommandHandler {

    override val type = MessageType.REFUND_PAYMENT

    override fun handle(envelope: MessageEnvelope) {
        val payload = codec.payload(envelope, RefundPaymentPayload::class.java)
        requireSameSaga(envelope, payload.workOrderId)
        payments.refund(RefundPaymentCommand(payload.workOrderId, payload.paymentId, payload.reason))
    }
}
