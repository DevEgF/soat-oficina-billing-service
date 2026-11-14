package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentRefundedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentRejectedPayload
import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteStatus
import com.soat.tech.challenge.oficina.billing.domain.model.TraceContext
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentCreation
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentGateway
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentProviderException
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentRepository
import com.soat.tech.challenge.oficina.billing.domain.port.QuoteRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class RequestPaymentCommand(
    val workOrderId: UUID,
    val quoteId: UUID,
    val amountCents: Long,
    /** Trace context of the RequestPayment message, stored with the payment. */
    val traceContext: TraceContext? = null,
)

data class RefundPaymentCommand(
    val workOrderId: UUID,
    /** Payment known by the orchestrator; when present it must match the payment of the work order. */
    val paymentId: UUID?,
    val reason: String?,
)

/**
 * Payments driven by saga commands. Both commands run inside the transaction of the message
 * (processed_message and the effect commit together), so the provider call happens with a database
 * connection held: the listener consumes one message at a time and the HTTP client has connection
 * and read timeouts, which bounds that time. The periodic status check, which calls the provider for
 * many payments, does it outside any transaction (PaymentStatusCheckService).
 */
@Service
class PaymentApplicationService(
    private val quotes: QuoteRepository,
    private val payments: PaymentRepository,
    private val gateway: PaymentGateway,
    private val events: EventPublisher,
    private val clock: Clock,
    private val paymentProperties: PaymentProperties,
) {

    /**
     * RequestPayment: charges the approved quote through the gateway with the cardholder name kept
     * with the approval. APPROVED publishes PaymentApproved, REJECTED publishes PaymentRejected and
     * PENDING is only stored (the periodic status check resolves it). Commands that do not apply to
     * the current state are logged and discarded.
     */
    @Transactional
    fun requestPayment(command: RequestPaymentCommand) {
        val quote = quotes.findByWorkOrderId(command.workOrderId)
        if (quote == null || quote.id != command.quoteId) {
            log.warn(
                "payment.request.ignored reason=quote-not-found workOrderId={} quoteId={}",
                command.workOrderId, command.quoteId,
            )
            return
        }
        if (quote.status != QuoteStatus.APPROVED) {
            log.warn(
                "payment.request.ignored reason=quote-not-approved workOrderId={} quoteId={} status={}",
                quote.workOrderId, quote.id, quote.status,
            )
            return
        }
        payments.findByWorkOrderId(command.workOrderId)?.let { existing ->
            log.warn(
                "payment.request.ignored reason=payment-already-exists workOrderId={} paymentId={} status={}",
                existing.workOrderId, existing.id, existing.status,
            )
            return
        }

        val payment = Payment.create(
            workOrderId = quote.workOrderId,
            quoteId = quote.id,
            amountCents = command.amountCents,
            provider = gateway.provider,
            correlationId = quote.correlationId,
            now = now(),
            traceContext = command.traceContext,
        )
        val resolved = if (command.amountCents != quote.totalCents) {
            payment.rejectLocally("Amount ${command.amountCents} differs from quote total ${quote.totalCents}", now())
            true
        } else {
            payment.applyGatewayResult(gateway.create(creationRequest(payment, quote)), now())
        }
        val saved = payments.save(payment)
        log.info(
            "payment.processed workOrderId={} paymentId={} status={} provider={} providerPaymentId={}",
            saved.workOrderId, saved.id, saved.status, saved.provider, saved.providerPaymentId,
        )
        if (resolved) {
            events.publishAfterCommit(paymentResultEvent(saved))
        }
    }

    /**
     * RefundPayment (compensation): an approved payment is refunded at the provider, goes to REFUNDED
     * and PaymentRefunded is published. A payment already refunded publishes PaymentRefunded again
     * (the orchestrator waits for that confirmation). Any other status, or no payment, is discarded.
     * A provider failure propagates: the message is retried and then dead-lettered.
     */
    @Transactional
    fun refund(command: RefundPaymentCommand) {
        val payment = payments.findByWorkOrderId(command.workOrderId)
        if (payment == null || (command.paymentId != null && command.paymentId != payment.id)) {
            log.warn(
                "payment.refund.ignored reason=payment-not-found workOrderId={} paymentId={}",
                command.workOrderId, command.paymentId,
            )
            return
        }
        when (payment.status) {
            PaymentStatus.REFUNDED -> log.info(
                "payment.refund.confirmed-again workOrderId={} paymentId={}",
                payment.workOrderId, payment.id,
            )
            PaymentStatus.APPROVED -> {
                val providerPaymentId = payment.providerPaymentId
                    ?: throw PaymentProviderException("Approved payment ${payment.id} has no provider payment id")
                val result = gateway.refund(providerPaymentId, payment.amountCents)
                if (result.status != GatewayPaymentStatus.REFUNDED) {
                    throw PaymentProviderException(
                        "Refund of payment ${payment.id} answered ${result.status} (${result.statusDetail})",
                    )
                }
                payment.markRefunded(now(), result.statusDetail)
                payments.save(payment)
                log.info(
                    "payment.refunded workOrderId={} paymentId={} providerPaymentId={} reason={}",
                    payment.workOrderId, payment.id, providerPaymentId, command.reason,
                )
            }
            else -> {
                log.warn(
                    "payment.refund.ignored reason=status-not-refundable workOrderId={} paymentId={} status={}",
                    payment.workOrderId, payment.id, payment.status,
                )
                return
            }
        }
        events.publishAfterCommit(
            OutgoingEvent(
                MessageType.PAYMENT_REFUNDED,
                payment.workOrderId,
                payment.correlationId,
                PaymentRefundedPayload(payment.workOrderId, payment.id),
            ),
        )
    }

    @Transactional(readOnly = true)
    fun get(workOrderId: UUID): Payment =
        payments.findByWorkOrderId(workOrderId) ?: throw NotFoundException("Payment not found for work order $workOrderId")

    private fun creationRequest(payment: Payment, quote: Quote) = PaymentCreation(
        paymentId = payment.id,
        workOrderId = payment.workOrderId,
        quoteId = payment.quoteId,
        amountCents = payment.amountCents,
        description = "Oficina - OS ${quote.trackingCode ?: quote.workOrderId}",
        payerEmail = quote.customerEmail,
        cardholderName = quote.cardholderName ?: paymentProperties.defaultCardholderName,
        // One charge per quote: a redelivered RequestPayment (rolled back after the gateway call)
        // reaches the provider with the same key and does not charge twice.
        idempotencyKey = "quote-${quote.id}",
    )

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    private companion object {
        val log = LoggerFactory.getLogger(PaymentApplicationService::class.java)
    }
}

/** PaymentApproved or PaymentRejected for a payment that has just left PENDING. */
internal fun paymentResultEvent(payment: Payment): OutgoingEvent = when (payment.status) {
    PaymentStatus.APPROVED -> OutgoingEvent(
        MessageType.PAYMENT_APPROVED,
        payment.workOrderId,
        payment.correlationId,
        PaymentApprovedPayload(
            workOrderId = payment.workOrderId,
            quoteId = payment.quoteId,
            paymentId = payment.id,
            amountCents = payment.amountCents,
            provider = payment.provider,
            providerPaymentId = payment.providerPaymentId,
        ),
    )
    PaymentStatus.REJECTED -> OutgoingEvent(
        MessageType.PAYMENT_REJECTED,
        payment.workOrderId,
        payment.correlationId,
        PaymentRejectedPayload(
            workOrderId = payment.workOrderId,
            quoteId = payment.quoteId,
            paymentId = payment.id,
            reason = payment.statusDetail ?: "Payment rejected by ${payment.provider}",
        ),
    )
    else -> error("Payment ${payment.id} is ${payment.status}: no result event")
}
