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

    private fun creation(key: String, cardholderName: String = "APRO") = PaymentCreation(
        paymentId = UUID.randomUUID(),
        workOrderId = UUID.randomUUID(),
        quoteId = UUID.randomUUID(),
        amountCents = 21000,
        description = "Oficina - OS X",
        payerEmail = null,
        cardholderName = cardholderName,
        idempotencyKey = key,
    )

    @Test
    fun `APRO approves at once with the SIMULATED provider`() {
        val payment = gateway.create(creation("quote-1"))

        assertEquals("SIMULATED", gateway.provider)
        assertEquals(GatewayPaymentStatus.APPROVED, payment.status)
        assertTrue(payment.providerPaymentId.startsWith("sim-"))
        assertEquals(payment, gateway.getStatus(payment.providerPaymentId))
        assertEquals(GatewayPaymentStatus.APPROVED, gateway.create(creation("quote-lower", " apro ")).status)
    }

    @Test
    fun `CONT stays pending until settled`() {
        val payment = gateway.create(creation("quote-1", "CONT"))

        assertEquals(GatewayPaymentStatus.PENDING, payment.status)
        assertEquals(GatewayPaymentStatus.PENDING, gateway.getStatus(payment.providerPaymentId).status)

        val settled = gateway.settle(payment.providerPaymentId, GatewayPaymentStatus.APPROVED, "accredited")
        assertEquals(GatewayPaymentStatus.APPROVED, settled.status)
        assertEquals(settled, gateway.getStatus(payment.providerPaymentId))
        assertFailsWith<IllegalStateException> { gateway.settle(payment.providerPaymentId, GatewayPaymentStatus.REJECTED) }
    }

    @Test
    fun `any other cardholder name is rejected with the name as reason`() {
        val payment = gateway.create(creation("quote-1", "OTHE"))

        assertEquals(GatewayPaymentStatus.REJECTED, payment.status)
        assertEquals("OTHE", payment.statusDetail)
    }

    @Test
    fun `same idempotency key returns the same charge`() {
        val first = gateway.create(creation("quote-1"))
        assertEquals(first, gateway.create(creation("quote-1", "OTHE")))
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
        assertFailsWith<NotFoundException> { gateway.settle("sim-unknown", GatewayPaymentStatus.APPROVED) }
    }
}
