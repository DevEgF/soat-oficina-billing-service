package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
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
 * there is no public URL for a webhook). The provider is called outside any transaction; only a
 * resolved payment opens a short transaction that reloads it, applies the answer and publishes
 * PaymentApproved or PaymentRejected after the commit, with the saga correlation id. A failure on one
 * payment is logged and does not stop the others.
 */
@Service
class PaymentStatusCheckService(
    private val payments: PaymentRepository,
    private val gateway: PaymentGateway,
    private val events: EventPublisher,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) {

    fun checkPending(): StatusCheckSummary {
        val pending = payments.findPending()
        var resolved = 0
        var failed = 0
        pending.forEach { payment ->
            withCorrelation(payment) {
                try {
                    if (check(payment)) resolved++
                } catch (e: Exception) {
                    failed++
                    // Only provider messages are known to be free of secrets; other failures log the class.
                    log.warn(
                        "payment.status-check.failed workOrderId={} paymentId={} exceptionClass={} detail={}",
                        payment.workOrderId, payment.id, e.javaClass.name,
                        (e as? PaymentProviderException)?.message,
                    )
                }
            }
        }
        if (pending.isNotEmpty()) {
            log.info("payment.status-check.done checked={} resolved={} failed={}", pending.size, resolved, failed)
        }
        return StatusCheckSummary(pending.size, resolved, failed)
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

    private inline fun withCorrelation(payment: Payment, block: () -> Unit) {
        MDC.put(REQUEST_ID_KEY, payment.correlationId)
        MDC.put(SAGA_ID_KEY, payment.workOrderId.toString())
        try {
            block()
        } finally {
            MDC.remove(REQUEST_ID_KEY)
            MDC.remove(SAGA_ID_KEY)
        }
    }

    private companion object {
        // Same MDC keys as the CorrelationIdFilter and the command listener.
        const val REQUEST_ID_KEY = "requestId"
        const val SAGA_ID_KEY = "sagaId"
        val log = LoggerFactory.getLogger(PaymentStatusCheckService::class.java)
    }
}
