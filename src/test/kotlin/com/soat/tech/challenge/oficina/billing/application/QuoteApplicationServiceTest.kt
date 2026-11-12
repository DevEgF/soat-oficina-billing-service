package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteApprovedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteCancelledPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteGeneratedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteGenerationFailedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteRejectedPayload
import com.soat.tech.challenge.oficina.billing.domain.exception.InvalidStatusTransitionException
import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.billing.domain.model.CatalogPart
import com.soat.tech.challenge.oficina.billing.domain.model.CatalogService
import com.soat.tech.challenge.oficina.billing.domain.model.LineKind
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteLine
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteStatus
import com.soat.tech.challenge.oficina.billing.domain.model.RequestedItem
import com.soat.tech.challenge.oficina.billing.domain.port.CatalogRepository
import com.soat.tech.challenge.oficina.billing.domain.port.QuoteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertFailsWith

class QuoteApplicationServiceTest {

    private val now = Instant.parse("2026-11-12T15:04:05.123456Z")
    private val quotes = mockk<QuoteRepository>()
    private val catalog = mockk<CatalogRepository>()
    private val events = mockk<EventPublisher>(relaxed = true)
    private val service = QuoteApplicationService(
        quotes,
        catalog,
        events,
        Clock.fixed(now, ZoneOffset.UTC),
        PaymentProperties(defaultCardholderName = "APRO"),
    )
    private val workOrderId = UUID.randomUUID()

    private fun command(services: List<RequestedItem>, parts: List<RequestedItem> = emptyList()) = GenerateQuoteCommand(
        workOrderId = workOrderId,
        trackingCode = "TRK",
        customerName = "Maria",
        customerEmail = "maria@example.com",
        services = services,
        parts = parts,
        correlationId = "corr-1",
    )

    private fun existingQuote() = Quote.create(
        workOrderId = workOrderId,
        trackingCode = "TRK",
        customerName = "Maria",
        customerEmail = null,
        correlationId = "corr-saga",
        lines = listOf(QuoteLine(LineKind.SERVICE, "TROCA_OLEO", "Troca de óleo", 1, 12000)),
        now = now,
    )

    @Test
    fun `generate prices with the catalog stores the quote and publishes QuoteGenerated`() {
        every { quotes.findByWorkOrderId(workOrderId) } returns null
        every { catalog.findServicesByCodes(setOf("TROCA_OLEO")) } returns listOf(CatalogService("TROCA_OLEO", "Troca de óleo", 12000, 30))
        every { catalog.findPartsByCodes(setOf("FILTRO_OLEO")) } returns listOf(CatalogPart("FILTRO_OLEO", "Filtro de óleo", 4500))
        val saved = slot<Quote>()
        every { quotes.save(capture(saved)) } answers { saved.captured }
        val event = slot<OutgoingEvent>()
        every { events.publishAfterCommit(capture(event)) } returns Unit

        service.generate(command(listOf(RequestedItem("TROCA_OLEO", 1)), listOf(RequestedItem("FILTRO_OLEO", 2))))

        val quote = saved.captured
        assertEquals(QuoteStatus.PENDING_APPROVAL, quote.status)
        assertEquals("corr-1", quote.correlationId)
        assertEquals("maria@example.com", quote.customerEmail)
        assertEquals(Instant.parse("2026-11-12T15:04:05.123Z"), quote.createdAt)
        assertEquals(MessageType.QUOTE_GENERATED, event.captured.type)
        assertEquals(workOrderId, event.captured.sagaId)
        assertEquals("corr-1", event.captured.correlationId)
        assertEquals(QuoteGeneratedPayload(workOrderId, quote.id, 12000, 9000, 21000), event.captured.payload)
    }

    @Test
    fun `item outside the catalog publishes QuoteGenerationFailed without storing`() {
        every { quotes.findByWorkOrderId(workOrderId) } returns null
        every { catalog.findServicesByCodes(any()) } returns emptyList()
        every { catalog.findPartsByCodes(any()) } returns emptyList()
        val event = slot<OutgoingEvent>()
        every { events.publishAfterCommit(capture(event)) } returns Unit

        service.generate(command(listOf(RequestedItem("NAO_EXISTE", 1))))

        verify(exactly = 0) { quotes.save(any()) }
        assertEquals(MessageType.QUOTE_GENERATION_FAILED, event.captured.type)
        assertEquals(
            QuoteGenerationFailedPayload(workOrderId, "Unknown catalog codes: services=NAO_EXISTE"),
            event.captured.payload,
        )
    }

    @Test
    fun `second GenerateQuote for the same work order is discarded`() {
        every { quotes.findByWorkOrderId(workOrderId) } returns existingQuote()

        service.generate(command(listOf(RequestedItem("TROCA_OLEO", 1))))

        verify(exactly = 0) { quotes.save(any()) }
        verify(exactly = 0) { events.publishAfterCommit(any()) }
    }

    @Test
    fun `approval publishes QuoteApproved with the stored correlation id`() {
        val quote = existingQuote()
        every { quotes.findByWorkOrderId(workOrderId) } returns quote
        every { quotes.save(quote) } returns quote
        val event = slot<OutgoingEvent>()
        every { events.publishAfterCommit(capture(event)) } returns Unit

        val result = service.decide(workOrderId, approved = true, reason = null)

        assertEquals(QuoteStatus.APPROVED, result.status)
        assertEquals("APRO", result.cardholderName)
        assertEquals(MessageType.QUOTE_APPROVED, event.captured.type)
        assertEquals("corr-saga", event.captured.correlationId)
        assertEquals(QuoteApprovedPayload(workOrderId, quote.id, 12000), event.captured.payload)
    }

    @Test
    fun `rejection publishes QuoteRejected with the reason`() {
        val quote = existingQuote()
        every { quotes.findByWorkOrderId(workOrderId) } returns quote
        every { quotes.save(quote) } returns quote
        val event = slot<OutgoingEvent>()
        every { events.publishAfterCommit(capture(event)) } returns Unit

        service.decide(workOrderId, approved = false, reason = "caro")

        assertEquals(MessageType.QUOTE_REJECTED, event.captured.type)
        assertEquals(QuoteRejectedPayload(workOrderId, quote.id, "caro"), event.captured.payload)
    }

    @Test
    fun `decision outside pending approval fails and publishes nothing`() {
        val quote = existingQuote().apply { approve(now) }
        every { quotes.findByWorkOrderId(workOrderId) } returns quote

        assertFailsWith<InvalidStatusTransitionException> { service.decide(workOrderId, approved = true, reason = null) }

        verify(exactly = 0) { events.publishAfterCommit(any()) }
    }

    @Test
    fun `unknown work order is not found`() {
        every { quotes.findByWorkOrderId(workOrderId) } returns null

        assertFailsWith<NotFoundException> { service.get(workOrderId) }
        assertFailsWith<NotFoundException> { service.decide(workOrderId, true, null) }
    }

    @Test
    fun `get returns the stored quote`() {
        val quote = existingQuote()
        every { quotes.findByWorkOrderId(workOrderId) } returns quote

        assertEquals(quote, service.get(workOrderId))
    }

    @Test
    fun `approval keeps the cardholder name sent with the decision`() {
        val quote = existingQuote()
        every { quotes.findByWorkOrderId(workOrderId) } returns quote
        every { quotes.save(quote) } returns quote

        assertEquals("CONT", service.decide(workOrderId, approved = true, reason = null, cardholderName = " CONT ").cardholderName)
    }

    @Test
    fun `cancel of an open or approved quote cancels and publishes QuoteCancelled`() {
        listOf(existingQuote(), existingQuote().apply { approve(now) }).forEach { quote ->
            every { quotes.findByWorkOrderId(workOrderId) } returns quote
            every { quotes.save(quote) } returns quote
            val event = slot<OutgoingEvent>()
            every { events.publishAfterCommit(capture(event)) } returns Unit

            service.cancel(CancelQuoteCommand(workOrderId, quote.id, "ApprovalTimeout"))

            assertEquals(QuoteStatus.CANCELLED, quote.status)
            assertEquals("ApprovalTimeout", quote.decisionReason)
            assertEquals(MessageType.QUOTE_CANCELLED, event.captured.type)
            assertEquals("corr-saga", event.captured.correlationId)
            assertEquals(QuoteCancelledPayload(workOrderId, quote.id), event.captured.payload)
            verify { quotes.save(quote) }
        }
    }

    @Test
    fun `cancel of a closed quote confirms again without changing it`() {
        val cancelled = existingQuote().apply { cancel(now, "first") }
        val rejected = existingQuote().apply { reject(now, "caro") }
        listOf(cancelled to QuoteStatus.CANCELLED, rejected to QuoteStatus.REJECTED).forEach { (quote, status) ->
            every { quotes.findByWorkOrderId(workOrderId) } returns quote
            val event = slot<OutgoingEvent>()
            every { events.publishAfterCommit(capture(event)) } returns Unit

            // Without quoteId the quote of the work order is used.
            service.cancel(CancelQuoteCommand(workOrderId, null, "PaymentRejected"))

            assertEquals(status, quote.status)
            assertEquals(QuoteCancelledPayload(workOrderId, quote.id), event.captured.payload)
        }
        verify(exactly = 0) { quotes.save(any()) }
    }

    @Test
    fun `cancel of an unknown quote is discarded`() {
        every { quotes.findByWorkOrderId(workOrderId) } returns null
        service.cancel(CancelQuoteCommand(workOrderId, UUID.randomUUID(), "x"))
        every { quotes.findByWorkOrderId(workOrderId) } returns existingQuote()
        service.cancel(CancelQuoteCommand(workOrderId, UUID.randomUUID(), "x"))

        verify(exactly = 0) { quotes.save(any()) }
        verify(exactly = 0) { events.publishAfterCommit(any()) }
    }
}
