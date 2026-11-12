package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentRejectedPayload
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentGateway
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentProviderException
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PaymentStatusCheckServiceTest {

    private val now = Instant.parse("2026-11-12T15:04:05Z")
    private val payments = mockk<PaymentRepository>()
    private val gateway = mockk<PaymentGateway>()
    private val events = mockk<EventPublisher>(relaxed = true)
    private val service = PaymentStatusCheckService(
        payments,
        gateway,
        events,
        TransactionOperations.withoutTransaction(),
        Clock.fixed(now, ZoneOffset.UTC),
    )
    private val published = mutableListOf<OutgoingEvent>()

    private fun pending(providerPaymentId: String?, correlationId: String = "corr-saga"): Payment {
        val payment = Payment(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 21000, "FAKE", correlationId,
            PaymentStatus.PENDING, providerPaymentId, "in_process", now, now,
        )
        every { payments.findById(payment.id) } answers {
            // Fresh copy, as a reload inside the transaction would return.
            Payment(
                payment.id, payment.workOrderId, payment.quoteId, payment.amountCents, payment.provider,
                payment.correlationId, payment.status, payment.providerPaymentId, payment.statusDetail,
                payment.createdAt, payment.updatedAt,
            )
        }
        return payment
    }

    private fun stubSaveAndPublish() {
        every { payments.save(any()) } answers { firstArg() }
        every { events.publishAfterCommit(any()) } answers { published += firstArg<OutgoingEvent>() }
    }

    @Test
    fun `provider resolution publishes PaymentApproved or PaymentRejected with the saga correlation id`() {
        val approved = pending("ord-1")
        val rejected = pending("ord-2")
        val stillPending = pending("ord-3")
        every { payments.findPending() } returns listOf(approved, rejected, stillPending)
        every { gateway.getStatus("ord-1") } returns GatewayPayment("ord-1", GatewayPaymentStatus.APPROVED, "accredited")
        every { gateway.getStatus("ord-2") } returns GatewayPayment("ord-2", GatewayPaymentStatus.REJECTED, "rejected_by_issuer")
        every { gateway.getStatus("ord-3") } returns GatewayPayment("ord-3", GatewayPaymentStatus.PENDING, "in_process")
        stubSaveAndPublish()

        val summary = service.checkPending()

        assertEquals(StatusCheckSummary(checked = 3, resolved = 2, failed = 0), summary)
        assertEquals(listOf(MessageType.PAYMENT_APPROVED, MessageType.PAYMENT_REJECTED), published.map { it.type })
        assertEquals(listOf("corr-saga", "corr-saga"), published.map { it.correlationId })
        assertEquals(
            PaymentApprovedPayload(approved.workOrderId, approved.quoteId, approved.id, 21000, "FAKE", "ord-1"),
            published[0].payload,
        )
        assertEquals(
            PaymentRejectedPayload(rejected.workOrderId, rejected.quoteId, rejected.id, "rejected_by_issuer"),
            published[1].payload,
        )
        verify(exactly = 2) { payments.save(any()) }
        assertNull(MDC.get("requestId"))
    }

    @Test
    fun `failure on one payment does not stop the others`() {
        val failing = pending("ord-1")
        val refundedWhilePending = pending("ord-2")
        val approved = pending("ord-3")
        every { payments.findPending() } returns listOf(failing, refundedWhilePending, approved)
        every { gateway.getStatus("ord-1") } throws PaymentProviderException("get-order: HTTP 500")
        every { gateway.getStatus("ord-2") } returns GatewayPayment("ord-2", GatewayPaymentStatus.REFUNDED)
        every { gateway.getStatus("ord-3") } returns GatewayPayment("ord-3", GatewayPaymentStatus.APPROVED)
        stubSaveAndPublish()

        val summary = service.checkPending()

        assertEquals(StatusCheckSummary(checked = 3, resolved = 1, failed = 2), summary)
        assertEquals(listOf(MessageType.PAYMENT_APPROVED), published.map { it.type })
    }

    @Test
    fun `payment resolved meanwhile or without provider id is skipped`() {
        val withoutProviderId = pending(null)
        val resolvedMeanwhile = pending("ord-2")
        every { payments.findById(resolvedMeanwhile.id) } returns Payment(
            resolvedMeanwhile.id, resolvedMeanwhile.workOrderId, resolvedMeanwhile.quoteId, 21000, "FAKE", "c",
            PaymentStatus.APPROVED, "ord-2", "accredited", now, now,
        )
        every { payments.findPending() } returns listOf(withoutProviderId, resolvedMeanwhile)
        every { gateway.getStatus("ord-2") } returns GatewayPayment("ord-2", GatewayPaymentStatus.APPROVED)

        assertEquals(StatusCheckSummary(checked = 2, resolved = 0, failed = 0), service.checkPending())

        verify(exactly = 1) { gateway.getStatus(any()) }
        verify(exactly = 0) { payments.save(any()) }
        verify(exactly = 0) { events.publishAfterCommit(any()) }
    }

    @Test
    fun `nothing pending does nothing`() {
        every { payments.findPending() } returns emptyList()

        assertEquals(StatusCheckSummary(0, 0, 0), service.checkPending())
        verify(exactly = 0) { gateway.getStatus(any()) }
    }
}
