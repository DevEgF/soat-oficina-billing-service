package com.soat.tech.challenge.oficina.billing.infrastructure.payment

import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentCreation
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Simulated provider (app.payment.gateway=simulated, the default). Follows the cardholder name
 * convention of the Mercado Pago test environment: APRO approves at once, CONT stays pending
 * (and keeps answering PENDING), any other name is rejected with the name as the reason.
 * State lives in memory only, which is enough for tests, compose and the demo.
 */
@Component
@ConditionalOnProperty(name = ["app.payment.gateway"], havingValue = "simulated", matchIfMissing = true)
class SimulatedPaymentGateway : PaymentGateway {

    override val provider: String = PROVIDER

    private val byIdempotencyKey = ConcurrentHashMap<String, String>()
    private val byProviderId = ConcurrentHashMap<String, GatewayPayment>()

    override fun create(request: PaymentCreation): GatewayPayment {
        val providerPaymentId = byIdempotencyKey.computeIfAbsent(request.idempotencyKey) {
            val id = "sim-${UUID.randomUUID()}"
            byProviderId[id] = GatewayPayment(id, outcome(request.cardholderName), detail(request.cardholderName))
            id
        }
        return byProviderId.getValue(providerPaymentId)
    }

    override fun getStatus(providerPaymentId: String): GatewayPayment =
        byProviderId[providerPaymentId] ?: throw NotFoundException("Simulated payment not found: $providerPaymentId")

    override fun refund(providerPaymentId: String, amountCents: Long): GatewayPayment {
        val refunded = getStatus(providerPaymentId).copy(status = GatewayPaymentStatus.REFUNDED, statusDetail = REFUNDED_DETAIL)
        byProviderId[providerPaymentId] = refunded
        return refunded
    }

    /**
     * Resolves a pending charge, as the real provider does some time after the creation. Lets the
     * local demo and the tests drive a CONT payment to its final status.
     */
    fun settle(providerPaymentId: String, status: GatewayPaymentStatus, detail: String? = null): GatewayPayment {
        val current = getStatus(providerPaymentId)
        check(current.status == GatewayPaymentStatus.PENDING) { "Simulated payment is not pending: $providerPaymentId" }
        val settled = current.copy(status = status, statusDetail = detail)
        byProviderId[providerPaymentId] = settled
        return settled
    }

    private fun outcome(cardholderName: String): GatewayPaymentStatus = when (normalized(cardholderName)) {
        APPROVE -> GatewayPaymentStatus.APPROVED
        PENDING -> GatewayPaymentStatus.PENDING
        else -> GatewayPaymentStatus.REJECTED
    }

    private fun detail(cardholderName: String): String = when (normalized(cardholderName)) {
        APPROVE -> APPROVED_DETAIL
        PENDING -> PENDING_DETAIL
        else -> cardholderName.trim()
    }

    private fun normalized(cardholderName: String) = cardholderName.trim().uppercase()

    companion object {
        const val PROVIDER = "SIMULATED"
        private const val APPROVE = "APRO"
        private const val PENDING = "CONT"
        private const val APPROVED_DETAIL = "accredited"
        private const val PENDING_DETAIL = "in_process"
        private const val REFUNDED_DETAIL = "refunded"
    }
}
