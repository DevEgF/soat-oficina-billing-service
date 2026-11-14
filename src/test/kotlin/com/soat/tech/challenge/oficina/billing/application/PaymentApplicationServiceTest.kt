package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentRefundedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.PaymentRejectedPayload
import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.billing.domain.model.LineKind
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteLine
import com.soat.tech.challenge.oficina.billing.domain.model.TraceContext
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentCreation
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentGateway
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentProviderException
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
    private val service = PaymentApplicationService(
        quotes,
        payments,
        gateway,
        events,
        Clock.fixed(now, ZoneOffset.UTC),
        PaymentProperties(defaultCardholderName = "APRO"),
    )
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

    private fun command(amount: Long = 12000, quoteId: UUID = quote.id, traceContext: TraceContext? = null) =
        RequestPaymentCommand(workOrderId, quoteId, amount, traceContext)

    @Test
    fun `trace context of the command is stored with the payment`() {
        quote.approve(now, "CONT")
        every { gateway.create(any()) } returns GatewayPayment("prov-3", GatewayPaymentStatus.PENDING, "in_process")
        val traceContext = TraceContext(traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")

        service.requestPayment(command(traceContext = traceContext))

        assertEquals(PaymentStatus.PENDING, saved.captured.status)
        assertEquals(traceContext, saved.captured.traceContext)
    }

    @Test
    fun `approved charge publishes PaymentApproved`() {
        quote.approve(now, "APRO")
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
        assertEquals("APRO", creation.captured.cardholderName)
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

    @Test
    fun `cardholder name of the approval reaches the gateway, default when absent`() {
        val creation = slot<PaymentCreation>()
        every { gateway.create(capture(creation)) } returns GatewayPayment("prov-1", GatewayPaymentStatus.PENDING)

        quote.approve(now, "OTHE")
        service.requestPayment(command())
        assertEquals("OTHE", creation.captured.cardholderName)

        val withoutName = Quote.create(workOrderId, "TRK", "Maria", null, "corr", quote.lines, now).apply { approve(now) }
        every { quotes.findByWorkOrderId(workOrderId) } returns withoutName
        service.requestPayment(command(quoteId = withoutName.id))
        assertEquals("APRO", creation.captured.cardholderName)
    }

    private fun approvedPayment() = Payment.create(workOrderId, quote.id, 12000, "FAKE", "corr-saga", now).apply {
        applyGatewayResult(GatewayPayment("prov-9", GatewayPaymentStatus.APPROVED, "accredited"), now)
    }

    @Test
    fun `refund of an approved payment refunds at the provider and publishes PaymentRefunded`() {
        val payment = approvedPayment()
        every { payments.findByWorkOrderId(workOrderId) } returns payment
        every { gateway.refund("prov-9", 12000) } returns GatewayPayment("prov-9", GatewayPaymentStatus.REFUNDED, "refunded")

        service.refund(RefundPaymentCommand(workOrderId, payment.id, "ExecutionFailed"))

        assertEquals(PaymentStatus.REFUNDED, saved.captured.status)
        assertEquals("refunded", saved.captured.statusDetail)
        assertEquals(MessageType.PAYMENT_REFUNDED, event.captured.type)
        assertEquals("corr-saga", event.captured.correlationId)
        assertEquals(PaymentRefundedPayload(workOrderId, payment.id), event.captured.payload)
    }

    @Test
    fun `refund of a refunded payment confirms again without calling the provider`() {
        val payment = approvedPayment().apply { markRefunded(now) }
        every { payments.findByWorkOrderId(workOrderId) } returns payment

        service.refund(RefundPaymentCommand(workOrderId, null, "ExecutionFailed"))

        verify(exactly = 0) { gateway.refund(any(), any()) }
        verify(exactly = 0) { payments.save(any()) }
        assertEquals(PaymentRefundedPayload(workOrderId, payment.id), event.captured.payload)
    }

    @Test
    fun `refund that does not apply is discarded`() {
        // No payment.
        service.refund(RefundPaymentCommand(workOrderId, UUID.randomUUID(), "x"))
        // Another payment id.
        every { payments.findByWorkOrderId(workOrderId) } returns approvedPayment()
        service.refund(RefundPaymentCommand(workOrderId, UUID.randomUUID(), "x"))
        // Pending and rejected payments.
        every { payments.findByWorkOrderId(workOrderId) } returns Payment.create(workOrderId, quote.id, 12000, "FAKE", "c", now)
        service.refund(RefundPaymentCommand(workOrderId, null, "x"))
        every { payments.findByWorkOrderId(workOrderId) } returns
            Payment.create(workOrderId, quote.id, 12000, "FAKE", "c", now).apply { rejectLocally("no", now) }
        service.refund(RefundPaymentCommand(workOrderId, null, "x"))

        verify(exactly = 0) { gateway.refund(any(), any()) }
        verify(exactly = 0) { payments.save(any()) }
        verify(exactly = 0) { events.publishAfterCommit(any()) }
    }

    @Test
    fun `provider failure or unexpected answer on refund propagates for retry`() {
        val payment = approvedPayment()
        every { payments.findByWorkOrderId(workOrderId) } returns payment
        every { gateway.refund(any(), any()) } throws PaymentProviderException("refund-order: HTTP 500")
        assertFailsWith<PaymentProviderException> { service.refund(RefundPaymentCommand(workOrderId, null, "x")) }

        every { gateway.refund(any(), any()) } returns GatewayPayment("prov-9", GatewayPaymentStatus.APPROVED)
        assertFailsWith<PaymentProviderException> { service.refund(RefundPaymentCommand(workOrderId, null, "x")) }

        val withoutProviderId = Payment(
            UUID.randomUUID(), workOrderId, quote.id, 12000, "FAKE", "c", PaymentStatus.APPROVED, null, null, now, now,
        )
        every { payments.findByWorkOrderId(workOrderId) } returns withoutProviderId
        assertFailsWith<PaymentProviderException> { service.refund(RefundPaymentCommand(workOrderId, null, "x")) }

        assertEquals(PaymentStatus.APPROVED, payment.status)
        verify(exactly = 0) { payments.save(any()) }
        verify(exactly = 0) { events.publishAfterCommit(any()) }
    }

    @Test
    fun `result event exists only for resolved payments`() {
        assertFailsWith<IllegalStateException> { paymentResultEvent(Payment.create(workOrderId, quote.id, 1, "FAKE", "c", now)) }
    }
}
