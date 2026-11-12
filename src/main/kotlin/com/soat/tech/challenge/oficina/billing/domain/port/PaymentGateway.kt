package com.soat.tech.challenge.oficina.billing.domain.port

import java.util.UUID

/**
 * Payment provider port. Shaped after the Mercado Pago Orders API: creation may come back
 * PENDING and be resolved later by [getStatus]; [refund] reverses an approved payment.
 * Implementations must be idempotent on [PaymentCreation.idempotencyKey].
 */
interface PaymentGateway {

    /** Provider name stored with the payment and published in PaymentApproved (e.g. SIMULATED). */
    val provider: String

    fun create(request: PaymentCreation): GatewayPayment

    fun getStatus(providerPaymentId: String): GatewayPayment

    /** Refunds an approved payment; asking again for one already refunded answers REFUNDED. */
    fun refund(providerPaymentId: String, amountCents: Long): GatewayPayment
}

/**
 * Provider failure (unreachable, timeout, unexpected answer). Messages carry only the operation,
 * the HTTP status and the provider error code: never tokens or card data.
 */
class PaymentProviderException(message: String) : RuntimeException(message)

data class PaymentCreation(
    val paymentId: UUID,
    val workOrderId: UUID,
    val quoteId: UUID,
    val amountCents: Long,
    val description: String,
    val payerEmail: String?,
    /**
     * Name of the card holder. In the provider test environment it selects the result
     * (APRO approves, CONT stays pending, other names are rejected).
     */
    val cardholderName: String,
    /** Same key on every retry of the same charge, so the provider never charges twice. */
    val idempotencyKey: String,
)

enum class GatewayPaymentStatus { PENDING, APPROVED, REJECTED, REFUNDED }

data class GatewayPayment(
    val providerPaymentId: String,
    val status: GatewayPaymentStatus,
    /** Provider detail (status_detail in Mercado Pago), used as the rejection reason. */
    val statusDetail: String? = null,
)
