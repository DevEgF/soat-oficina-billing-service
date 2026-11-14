package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.observability.Tracing
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentGateway
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentProviderException
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Outcome of one pass of the status check, for logs and tests. */
data class StatusCheckSummary(val checked: Int, val resolved: Int, val failed: Int)

/**
 * Resolves pending payments by asking the provider (the creation at Mercado Pago is asynchronous and
 * there is no public URL for a webhook). The provider is called outside any database transaction;
 * only a resolved payment opens a short transaction that reloads it, applies the answer and publishes
 * PaymentApproved or PaymentRejected after the commit, with the saga correlation id. A failure on one
 * payment is logged and does not stop the others.
 *
 * Each payment is checked in its own APM transaction (`Job/payment-status-check`) that first accepts
 * the trace headers stored with the payment, so the published result continues the trace of the
 * RequestPayment that created it.
 */
@Service
class PaymentStatusCheckService(
    private val payments: PaymentRepository,
    private val gateway: PaymentGateway,
    private val events: EventPublisher,
    private val transactions: TransactionOperations,
    private val clock: Clock,
    private val tracing: Tracing,
) {

    private enum class Outcome { STILL_PENDING, RESOLVED, FAILED }

    fun checkPending(): StatusCheckSummary {
        val pending = payments.findPending()
        val outcomes = pending.map { payment ->
            tracing.inJobTransaction(
                name = JOB_NAME,
                traceHeaders = payment.traceContext?.toHeaders().orEmpty(),
                attributes = mapOf(SAGA_ID_KEY to payment.workOrderId.toString(), PAYMENT_ID_KEY to payment.id.toString()),
            ) {
                withCorrelation(payment) { checkSafely(payment) }
            }
        }
        val resolved = outcomes.count { it == Outcome.RESOLVED }
        val failed = outcomes.count { it == Outcome.FAILED }
        if (pending.isNotEmpty()) {
            log.info("payment.status-check.done checked={} resolved={} failed={}", pending.size, resolved, failed)
        }
        return StatusCheckSummary(pending.size, resolved, failed)
    }

    private fun checkSafely(payment: Payment): Outcome = try {
        if (check(payment)) Outcome.RESOLVED else Outcome.STILL_PENDING
    } catch (e: Exception) {
        // Only provider messages are known to be free of secrets; other failures log the class.
        log.warn(
            "payment.status-check.failed workOrderId={} paymentId={} exceptionClass={} detail={}",
            payment.workOrderId, payment.id, e.javaClass.name,
            (e as? PaymentProviderException)?.message,
        )
        Outcome.FAILED
    }

    private fun check(payment: Payment): Boolean {
        val providerPaymentId = payment.providerPaymentId ?: return false
        val answer = gateway.getStatus(providerPaymentId)
        if (answer.status == GatewayPaymentStatus.PENDING) {
            return false
        }
        return transactions.execute { apply(payment, answer) } == true
    }

    private fun apply(snapshot: Payment, answer: GatewayPayment): Boolean {
        // Reloaded inside the transaction: the version guards against a concurrent resolution.
        val payment = payments.findById(snapshot.id)
        if (payment == null || payment.status != PaymentStatus.PENDING) {
            return false
        }
        if (!payment.applyGatewayResult(answer, clock.instant().truncatedTo(ChronoUnit.MILLIS))) {
            return false
        }
        val saved = payments.save(payment)
        log.info(
            "payment.resolved workOrderId={} paymentId={} status={} providerPaymentId={}",
            saved.workOrderId, saved.id, saved.status, saved.providerPaymentId,
        )
        events.publishAfterCommit(paymentResultEvent(saved))
        return true
    }

    private inline fun <T> withCorrelation(payment: Payment, block: () -> T): T {
        MDC.put(REQUEST_ID_KEY, payment.correlationId)
        MDC.put(SAGA_ID_KEY, payment.workOrderId.toString())
        try {
            return block()
        } finally {
            MDC.remove(REQUEST_ID_KEY)
            MDC.remove(SAGA_ID_KEY)
        }
    }

    companion object {
        /** Name of the APM transaction of each payment check. */
        const val JOB_NAME = "payment-status-check"

        // Same MDC keys as the CorrelationIdFilter and the command listener.
        private const val REQUEST_ID_KEY = "requestId"
        private const val SAGA_ID_KEY = "sagaId"
        private const val PAYMENT_ID_KEY = "paymentId"
        private val log = LoggerFactory.getLogger(PaymentStatusCheckService::class.java)
    }
}
