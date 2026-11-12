package com.soat.tech.challenge.oficina.billing.infrastructure.payment

import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentCreation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFailsWith

class SimulatedPaymentGatewayTest {

    private val gateway = SimulatedPaymentGateway()

    private fun creation(key: String) = PaymentCreation(
        paymentId = UUID.randomUUID(),
        workOrderId = UUID.randomUUID(),
        quoteId = UUID.randomUUID(),
        amountCents = 21000,
        description = "Oficina - OS X",
        payerEmail = null,
        idempotencyKey = key,
    )

    @Test
    fun `approves at once with the SIMULATED provider`() {
        val payment = gateway.create(creation("quote-1"))

        assertEquals("SIMULATED", gateway.provider)
        assertEquals(GatewayPaymentStatus.APPROVED, payment.status)
        assertTrue(payment.providerPaymentId.startsWith("sim-"))
        assertEquals(payment, gateway.getStatus(payment.providerPaymentId))
    }

    @Test
    fun `same idempotency key returns the same charge`() {
        val first = gateway.create(creation("quote-1"))
        assertEquals(first, gateway.create(creation("quote-1")))
        assertNotEquals(first.providerPaymentId, gateway.create(creation("quote-2")).providerPaymentId)
    }

    @Test
    fun `refund marks the charge refunded`() {
        val payment = gateway.create(creation("quote-1"))

        val refunded = gateway.refund(payment.providerPaymentId, 21000)

        assertEquals(GatewayPaymentStatus.REFUNDED, refunded.status)
        assertEquals(GatewayPaymentStatus.REFUNDED, gateway.getStatus(payment.providerPaymentId).status)
    }

    @Test
    fun `unknown charge is not found`() {
        assertFailsWith<NotFoundException> { gateway.getStatus("sim-unknown") }
        assertFailsWith<NotFoundException> { gateway.refund("sim-unknown", 1) }
    }
}
