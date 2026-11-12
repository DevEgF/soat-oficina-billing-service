package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentRejectedPayload
import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.billing.domain.model.LineKind
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteLine
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentCreation
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentGateway
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentRepository
import com.soat.tech.challenge.oficina.billing.domain.port.QuoteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertFailsWith

class PaymentApplicationServiceTest {

    private val now = Instant.parse("2026-11-12T15:04:05Z")
    private val quotes = mockk<QuoteRepository>()
    private val payments = mockk<PaymentRepository>()
    private val gateway = mockk<PaymentGateway>()
    private val events = mockk<EventPublisher>(relaxed = true)
    private val service = PaymentApplicationService(quotes, payments, gateway, events, Clock.fixed(now, ZoneOffset.UTC))
    private val workOrderId = UUID.randomUUID()
    private val quote = Quote.create(
        workOrderId = workOrderId,
        trackingCode = "TRK-9",
        customerName = "Maria",
        customerEmail = "maria@example.com",
        correlationId = "corr-saga",
        lines = listOf(QuoteLine(LineKind.SERVICE, "TROCA_OLEO", "Troca de óleo", 1, 12000)),
        now = now,
    )
    private val saved = slot<Payment>()
    private val event = slot<OutgoingEvent>()

    @BeforeEach
    fun setUp() {
        every { gateway.provider } returns "FAKE"
        every { quotes.findByWorkOrderId(workOrderId) } returns quote
        every { payments.findByWorkOrderId(workOrderId) } returns null
        every { payments.save(capture(saved)) } answers { saved.captured }
        every { events.publishAfterCommit(capture(event)) } returns Unit
    }

    private fun command(amount: Long = 12000, quoteId: UUID = quote.id) = RequestPaymentCommand(workOrderId, quoteId, amount)

    @Test
    fun `approved charge publishes PaymentApproved`() {
        quote.approve(now)
        val creation = slot<PaymentCreation>()
        every { gateway.create(capture(creation)) } returns GatewayPayment("prov-1", GatewayPaymentStatus.APPROVED, "accredited")

        service.requestPayment(command())

        val payment = saved.captured
        assertEquals(PaymentStatus.APPROVED, payment.status)
        assertEquals("FAKE", payment.provider)
        assertEquals("corr-saga", payment.correlationId)
        assertEquals("quote-${quote.id}", creation.captured.idempotencyKey)
        assertEquals("maria@example.com", creation.captured.payerEmail)
        assertEquals(12000, creation.captured.amountCents)
        assertEquals("Oficina - OS TRK-9", creation.captured.description)
        assertEquals(MessageType.PAYMENT_APPROVED, event.captured.type)
        assertEquals("corr-saga", event.captured.correlationId)
        assertEquals(
            PaymentApprovedPayload(workOrderId, quote.id, payment.id, 12000, "FAKE", "prov-1"),
            event.captured.payload,
        )
    }

    @Test
    fun `rejected charge publishes PaymentRejected with provider detail`() {
        quote.approve(now)
        every { gateway.create(any()) } returns GatewayPayment("prov-2", GatewayPaymentStatus.REJECTED, "cc_rejected_insufficient_amount")

        service.requestPayment(command())

        assertEquals(PaymentStatus.REJECTED, saved.captured.status)
        assertEquals(MessageType.PAYMENT_REJECTED, event.captured.type)
        assertEquals(
            PaymentRejectedPayload(workOrderId, quote.id, saved.captured.id, "cc_rejected_insufficient_amount"),
            event.captured.payload,
        )
    }

    @Test
    fun `rejection without detail gets a generic reason`() {
        quote.approve(now)
        every { gateway.create(any()) } returns GatewayPayment("prov-2", GatewayPaymentStatus.REJECTED)

        service.requestPayment(command())

        assertEquals("Payment rejected by FAKE", (event.captured.payload as PaymentRejectedPayload).reason)
    }

    @Test
    fun `pending charge is stored and publishes nothing`() {
        quote.approve(now)
        every { gateway.create(any()) } returns GatewayPayment("prov-3", GatewayPaymentStatus.PENDING)

        service.requestPayment(command())

        assertEquals(PaymentStatus.PENDING, saved.captured.status)
        assertEquals("prov-3", saved.captured.providerPaymentId)
        verify(exactly = 0) { events.publishAfterCommit(any()) }
    }

    @Test
    fun `amount different from the quote is rejected without calling the gateway`() {
        quote.approve(now)

        service.requestPayment(command(amount = 1))

        verify(exactly = 0) { gateway.create(any()) }
        assertEquals(PaymentStatus.REJECTED, saved.captured.status)
        assertEquals(MessageType.PAYMENT_REJECTED, event.captured.type)
        assertEquals(
            "Amount 1 differs from quote total 12000",
            (event.captured.payload as PaymentRejectedPayload).reason,
        )
    }

    @Test
    fun `commands that do not apply to the current state are discarded`() {
        // Quote still pending approval.
        service.requestPayment(command())
        // Quote id of another quote.
        quote.approve(now)
        service.requestPayment(command(quoteId = UUID.randomUUID()))
        // Payment already exists.
        every { payments.findByWorkOrderId(workOrderId) } returns
            Payment.create(workOrderId, quote.id, 12000, "FAKE", "corr", now)
        service.requestPayment(command())
        // Unknown work order.
        every { quotes.findByWorkOrderId(workOrderId) } returns null
        service.requestPayment(command())

        verify(exactly = 0) { gateway.create(any()) }
        verify(exactly = 0) { payments.save(any()) }
        verify(exactly = 0) { events.publishAfterCommit(any()) }
    }

    @Test
    fun `get returns the payment or not found`() {
        assertFailsWith<NotFoundException> { service.get(workOrderId) }

        val payment = Payment.create(workOrderId, quote.id, 12000, "FAKE", "corr", now)
        every { payments.findByWorkOrderId(workOrderId) } returns payment
        assertEquals(payment, service.get(workOrderId))
    }
}
