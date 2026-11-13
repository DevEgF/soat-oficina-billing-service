package com.soat.tech.challenge.oficina.billing.domain.model

import com.soat.tech.challenge.oficina.billing.domain.exception.InvalidStatusTransitionException
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import java.time.Instant
import java.util.UUID

enum class PaymentStatus { PENDING, APPROVED, REJECTED, REFUNDED }

/**
 * Payment of an approved quote (aggregate). It is born PENDING and follows the gateway answer:
 * a gateway may approve or reject right away or keep it pending until a later status query.
 */
class Payment(
    val id: UUID,
    val workOrderId: UUID,
    val quoteId: UUID,
    val amountCents: Long,
    val provider: String,
    val correlationId: String,
    status: PaymentStatus,
    providerPaymentId: String?,
    statusDetail: String?,
    val createdAt: Instant,
    updatedAt: Instant,
    /** Trace context of the RequestPayment that created the payment; continued when it is resolved later. */
    val traceContext: TraceContext? = null,
) {
    var status: PaymentStatus = status
        private set
    var providerPaymentId: String? = providerPaymentId
        private set
    var statusDetail: String? = statusDetail
        private set
    var updatedAt: Instant = updatedAt
        private set

    init {
        require(amountCents >= 0) { "Amount cannot be negative" }
    }

    private fun requireStatus(action: String, expected: PaymentStatus) {
        if (status != expected) {
            throw InvalidStatusTransitionException(status.name, action)
        }
    }

    /**
     * Applies the gateway answer (creation or status query) to a pending payment.
     * Returns true when the payment left PENDING, which is when an event must be published.
     */
    fun applyGatewayResult(result: GatewayPayment, now: Instant): Boolean {
        requireStatus("applyGatewayResult", PaymentStatus.PENDING)
        providerPaymentId = result.providerPaymentId
        statusDetail = result.statusDetail
        updatedAt = now
        status = when (result.status) {
            GatewayPaymentStatus.PENDING -> PaymentStatus.PENDING
            GatewayPaymentStatus.APPROVED -> PaymentStatus.APPROVED
            GatewayPaymentStatus.REJECTED -> PaymentStatus.REJECTED
            GatewayPaymentStatus.REFUNDED ->
                throw InvalidStatusTransitionException(status.name, "applyGatewayResult(REFUNDED)")
        }
        return status != PaymentStatus.PENDING
    }

    /** Rejected before reaching the gateway (for instance, amount different from the quote). */
    fun rejectLocally(reason: String, now: Instant) {
        requireStatus("rejectLocally", PaymentStatus.PENDING)
        status = PaymentStatus.REJECTED
        statusDetail = reason
        updatedAt = now
    }

    /** Saga compensation (RefundPayment), after the provider confirmed the refund. */
    fun markRefunded(now: Instant, detail: String? = null) {
        requireStatus("refund", PaymentStatus.APPROVED)
        status = PaymentStatus.REFUNDED
        detail?.let { statusDetail = it }
        updatedAt = now
    }

    companion object {
        fun create(
            workOrderId: UUID,
            quoteId: UUID,
            amountCents: Long,
            provider: String,
            correlationId: String,
            now: Instant,
            traceContext: TraceContext? = null,
            id: UUID = UUID.randomUUID(),
        ) = Payment(
            id = id,
            workOrderId = workOrderId,
            quoteId = quoteId,
            amountCents = amountCents,
            provider = provider,
            correlationId = correlationId,
            status = PaymentStatus.PENDING,
            providerPaymentId = null,
            statusDetail = null,
            createdAt = now,
            updatedAt = now,
            traceContext = traceContext,
        )
    }
}
