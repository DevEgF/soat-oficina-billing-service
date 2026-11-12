package com.soat.tech.challenge.oficina.billing.domain.model

import com.soat.tech.challenge.oficina.billing.domain.exception.InvalidStatusTransitionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

class QuoteTest {

    private val t0 = Instant.parse("2026-11-12T10:00:00Z")

    private fun quote(lines: List<QuoteLine> = defaultLines) = Quote.create(
        workOrderId = UUID.randomUUID(),
        trackingCode = "TRK-1",
        customerName = "Maria",
        customerEmail = null,
        correlationId = "corr-1",
        lines = lines,
        now = t0,
    )

    private val defaultLines = listOf(
        QuoteLine(LineKind.SERVICE, "TROCA_OLEO", "Troca de óleo", 1, 12000),
        QuoteLine(LineKind.PART, "FILTRO_OLEO", "Filtro de óleo", 2, 4500),
    )

    @Nested
    inner class Totals {

        @Test
        fun `totals follow recalculateQuote of the monolith`() {
            val quote = quote(
                listOf(
                    QuoteLine(LineKind.SERVICE, "A", "A", 2, 18000),
                    QuoteLine(LineKind.SERVICE, "B", "B", 1, 8000),
                    QuoteLine(LineKind.PART, "C", "C", 4, 42000),
                ),
            )
            assertEquals(44000, quote.servicesTotalCents)
            assertEquals(168000, quote.partsTotalCents)
            assertEquals(212000, quote.totalCents)
        }

        @Test
        fun `quote with services only has zero parts total`() {
            val quote = quote(listOf(QuoteLine(LineKind.SERVICE, "A", "A", 1, 100)))
            assertEquals(100, quote.servicesTotalCents)
            assertEquals(0, quote.partsTotalCents)
            assertEquals(100, quote.totalCents)
        }

        @Test
        fun `large quantities do not overflow int`() {
            val quote = quote(listOf(QuoteLine(LineKind.PART, "P", "P", 100_000, 52_000)))
            assertEquals(5_200_000_000L, quote.totalCents)
        }

        @Test
        fun `line total is quantity times unit price`() {
            assertEquals(9000, defaultLines[1].totalCents)
        }

        @Test
        fun `invalid lines and empty quote are refused`() {
            assertFailsWith<IllegalArgumentException> { QuoteLine(LineKind.PART, "P", "P", 0, 1) }
            assertFailsWith<IllegalArgumentException> { QuoteLine(LineKind.PART, "P", "P", 1, -1) }
            assertFailsWith<IllegalArgumentException> { quote(emptyList()) }
        }
    }

    @Nested
    inner class Transitions {

        @Test
        fun `new quote is pending approval`() {
            val quote = quote()
            assertEquals(QuoteStatus.PENDING_APPROVAL, quote.status)
            assertNull(quote.decidedAt)
            assertEquals(t0, quote.updatedAt)
        }

        @Test
        fun `approve from pending`() {
            val quote = quote()
            quote.approve(t0.plusSeconds(5))
            assertEquals(QuoteStatus.APPROVED, quote.status)
            assertEquals(t0.plusSeconds(5), quote.decidedAt)
            assertEquals(t0.plusSeconds(5), quote.updatedAt)
        }

        @Test
        fun `reject from pending keeps a trimmed reason`() {
            val quote = quote()
            quote.reject(t0.plusSeconds(5), "  caro demais ")
            assertEquals(QuoteStatus.REJECTED, quote.status)
            assertEquals("caro demais", quote.decisionReason)

            val blank = quote()
            blank.reject(t0, "   ")
            assertNull(blank.decisionReason)
        }

        @Test
        fun `decision only from pending approval`() {
            val approved = quote().apply { approve(t0) }
            val error = assertFailsWith<InvalidStatusTransitionException> { approved.approve(t0) }
            assertEquals("APPROVED", error.currentStatus)
            assertEquals("approve", error.action)
            assertFailsWith<InvalidStatusTransitionException> { approved.reject(t0, null) }

            val rejected = quote().apply { reject(t0, null) }
            assertFailsWith<InvalidStatusTransitionException> { rejected.approve(t0) }
        }

        @Test
        fun `cancel from pending or approved only`() {
            quote().apply { cancel(t0, "prazo") }.also {
                assertEquals(QuoteStatus.CANCELLED, it.status)
                assertEquals("prazo", it.decisionReason)
            }
            quote().apply { approve(t0); cancel(t0.plusSeconds(1), null) }.also {
                assertEquals(QuoteStatus.CANCELLED, it.status)
                assertEquals(t0, it.decidedAt)
            }
            val rejected = quote().apply { reject(t0, null) }
            assertFailsWith<InvalidStatusTransitionException> { rejected.cancel(t0, null) }
        }
    }
}
