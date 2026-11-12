package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentRejectedPayload
import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentCreation
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentGateway
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
)

@Service
class PaymentApplicationService(
    private val quotes: QuoteRepository,
    private val payments: PaymentRepository,
    private val gateway: PaymentGateway,
    private val events: EventPublisher,
    private val clock: Clock,
) {

    /**
     * RequestPayment: charges the approved quote through the gateway. APPROVED publishes
     * PaymentApproved, REJECTED publishes PaymentRejected and PENDING is only stored (a periodic
     * status check will resolve it). Commands that do not apply to the current state are
     * logged and discarded.
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
        )
        val resolved = if (command.amountCents != quote.totalCents) {
            payment.rejectLocally("Amount ${command.amountCents} differs from quote total ${quote.totalCents}", now())
            true
        } else {
            payment.applyGatewayResult(gateway.create(creationRequest(payment, quote)), now())
        }
        val saved = payments.save(payment)
        log.info(
            "payment.processed workOrderId={} paymentId={} status={} provider={}",
            saved.workOrderId, saved.id, saved.status, saved.provider,
        )
        if (resolved) {
            events.publishAfterCommit(resultEvent(saved))
        }
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
        // One charge per quote: a redelivered RequestPayment (rolled back after the gateway call)
        // reaches the provider with the same key and does not charge twice.
        idempotencyKey = "quote-${quote.id}",
    )

    private fun resultEvent(payment: Payment): OutgoingEvent = when (payment.status) {
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
        else -> OutgoingEvent(
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
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    private companion object {
        val log = LoggerFactory.getLogger(PaymentApplicationService::class.java)
    }
}
