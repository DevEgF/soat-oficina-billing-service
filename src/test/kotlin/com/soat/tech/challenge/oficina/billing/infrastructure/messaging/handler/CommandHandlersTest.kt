package com.soat.tech.challenge.oficina.billing.infrastructure.messaging.handler

import com.soat.tech.challenge.oficina.billing.application.GenerateQuoteCommand
import com.soat.tech.challenge.oficina.billing.application.PaymentApplicationService
import com.soat.tech.challenge.oficina.billing.application.QuoteApplicationService
import com.soat.tech.challenge.oficina.billing.application.RequestPaymentCommand
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.domain.model.RequestedItem
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.EnvelopeCodec
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.InvalidMessageException
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.MessageEnvelope
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

class CommandHandlersTest {

    private val codec = EnvelopeCodec(jacksonMapperBuilder().build())
    private val quotes = mockk<QuoteApplicationService>(relaxed = true)
    private val payments = mockk<PaymentApplicationService>(relaxed = true)
    private val workOrderId = UUID.randomUUID()

    private fun envelope(type: String, payload: Map<String, Any?>, sagaId: UUID = workOrderId) = MessageEnvelope(
        messageId = UUID.randomUUID(),
        sagaId = sagaId,
        type = type,
        occurredAt = Instant.now(),
        correlationId = "corr-x",
        payload = codec.toTree(payload),
    )

    private val generatePayload = mapOf(
        "workOrderId" to workOrderId.toString(),
        "trackingCode" to "TRK",
        "customer" to mapOf("name" to "Maria", "email" to "m@example.com"),
        "services" to listOf(mapOf("code" to "TROCA_OLEO", "quantity" to 1)),
        "parts" to listOf(mapOf("code" to "FILTRO_OLEO", "quantity" to 2)),
    )

    @Test
    fun `GenerateQuote payload becomes the application command`() {
        val handler = GenerateQuoteHandler(codec, quotes)
        val command = slot<GenerateQuoteCommand>()
        every { quotes.generate(capture(command)) } returns Unit

        handler.handle(envelope("GenerateQuote", generatePayload))

        assertEquals(MessageType.GENERATE_QUOTE, handler.type)
        assertEquals(
            GenerateQuoteCommand(
                workOrderId = workOrderId,
                trackingCode = "TRK",
                customerName = "Maria",
                customerEmail = "m@example.com",
                services = listOf(RequestedItem("TROCA_OLEO", 1)),
                parts = listOf(RequestedItem("FILTRO_OLEO", 2)),
                correlationId = "corr-x",
            ),
            command.captured,
        )
    }

    @Test
    fun `RequestPayment payload becomes the application command`() {
        val handler = RequestPaymentHandler(codec, payments)
        val quoteId = UUID.randomUUID()

        handler.handle(
            envelope(
                "RequestPayment",
                mapOf("workOrderId" to workOrderId.toString(), "quoteId" to quoteId.toString(), "amountCents" to 21000),
            ),
        )

        assertEquals(MessageType.REQUEST_PAYMENT, handler.type)
        verify { payments.requestPayment(RequestPaymentCommand(workOrderId, quoteId, 21000)) }
    }

    @Test
    fun `sagaId different from the work order or invalid payload is refused`() {
        assertFailsWith<InvalidMessageException> {
            GenerateQuoteHandler(codec, quotes).handle(envelope("GenerateQuote", generatePayload, sagaId = UUID.randomUUID()))
        }
        assertFailsWith<InvalidMessageException> {
            GenerateQuoteHandler(codec, quotes).handle(envelope("GenerateQuote", generatePayload - "customer"))
        }
        assertFailsWith<InvalidMessageException> {
            RequestPaymentHandler(codec, payments).handle(envelope("RequestPayment", mapOf("workOrderId" to "x")))
        }
        verify(exactly = 0) { quotes.generate(any()) }
        verify(exactly = 0) { payments.requestPayment(any()) }
    }
}
