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
 * Simulated provider (app.payment.gateway=simulated, the default): approves every charge at once.
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
            byProviderId[id] = GatewayPayment(id, GatewayPaymentStatus.APPROVED, APPROVED_DETAIL)
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

    companion object {
        const val PROVIDER = "SIMULATED"
        private const val APPROVED_DETAIL = "accredited"
        private const val REFUNDED_DETAIL = "refunded"
    }
}
