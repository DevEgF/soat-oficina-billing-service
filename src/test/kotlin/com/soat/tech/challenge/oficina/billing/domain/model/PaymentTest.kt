package com.soat.tech.challenge.oficina.billing.domain.model

import com.soat.tech.challenge.oficina.billing.domain.exception.InvalidStatusTransitionException
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

class PaymentTest {

    private val t0 = Instant.parse("2026-11-12T10:00:00Z")

    private fun payment() = Payment.create(UUID.randomUUID(), UUID.randomUUID(), 21000, "SIMULATED", "corr", t0)

    @Test
    fun `new payment is pending without provider id`() {
        val payment = payment()
        assertEquals(PaymentStatus.PENDING, payment.status)
        assertNull(payment.providerPaymentId)
        assertEquals(t0, payment.createdAt)
        assertEquals(t0, payment.updatedAt)
    }

    @Test
    fun `approved gateway result approves and is resolved`() {
        val payment = payment()
        assertTrue(payment.applyGatewayResult(GatewayPayment("p-1", GatewayPaymentStatus.APPROVED, "accredited"), t0.plusSeconds(1)))
        assertEquals(PaymentStatus.APPROVED, payment.status)
        assertEquals("p-1", payment.providerPaymentId)
        assertEquals("accredited", payment.statusDetail)
        assertEquals(t0.plusSeconds(1), payment.updatedAt)
    }

    @Test
    fun `rejected gateway result rejects`() {
        val payment = payment()
        assertTrue(payment.applyGatewayResult(GatewayPayment("p-1", GatewayPaymentStatus.REJECTED, "cc_rejected_other_reason"), t0))
        assertEquals(PaymentStatus.REJECTED, payment.status)
    }

    @Test
    fun `pending gateway result stays pending and can be resolved later`() {
        val payment = payment()
        assertFalse(payment.applyGatewayResult(GatewayPayment("p-1", GatewayPaymentStatus.PENDING), t0))
        assertEquals(PaymentStatus.PENDING, payment.status)
        assertEquals("p-1", payment.providerPaymentId)
        assertTrue(payment.applyGatewayResult(GatewayPayment("p-1", GatewayPaymentStatus.APPROVED), t0.plusSeconds(60)))
        assertEquals(PaymentStatus.APPROVED, payment.status)
    }

    @Test
    fun `gateway result applies only to pending payments and never as refund`() {
        assertFailsWith<InvalidStatusTransitionException> {
            payment().applyGatewayResult(GatewayPayment("p-1", GatewayPaymentStatus.REFUNDED), t0)
        }
        val approved = payment().apply { applyGatewayResult(GatewayPayment("p-1", GatewayPaymentStatus.APPROVED), t0) }
        assertFailsWith<InvalidStatusTransitionException> {
            approved.applyGatewayResult(GatewayPayment("p-1", GatewayPaymentStatus.REJECTED), t0)
        }
    }

    @Test
    fun `local rejection keeps the reason`() {
        val payment = payment()
        payment.rejectLocally("amount mismatch", t0)
        assertEquals(PaymentStatus.REJECTED, payment.status)
        assertEquals("amount mismatch", payment.statusDetail)
        assertFailsWith<InvalidStatusTransitionException> { payment.rejectLocally("again", t0) }
    }

    @Test
    fun `refund only from approved`() {
        val approved = payment().apply { applyGatewayResult(GatewayPayment("p-1", GatewayPaymentStatus.APPROVED), t0) }
        approved.markRefunded(t0.plusSeconds(10), "refunded")
        assertEquals(PaymentStatus.REFUNDED, approved.status)
        assertEquals("refunded", approved.statusDetail)
        assertFailsWith<InvalidStatusTransitionException> { payment().markRefunded(t0) }
    }

    @Test
    fun `negative amount is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Payment.create(UUID.randomUUID(), UUID.randomUUID(), -1, "SIMULATED", "corr", t0)
        }
    }
}
