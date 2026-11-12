package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.infrastructure.messaging.handler.CommandHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

class BillingCommandListenerTest {

    private val codec = EnvelopeCodec(jacksonMapperBuilder().build())
    private val processor = mockk<IdempotentMessageProcessor>()
    private val seenMdc = mutableListOf<Map<String, String>?>()
    private val handled = mutableListOf<MessageEnvelope>()
    private val generateQuote = object : CommandHandler {
        override val type = MessageType.GENERATE_QUOTE
        override fun handle(envelope: MessageEnvelope) {
            seenMdc += MDC.getCopyOfContextMap()
            handled += envelope
        }
    }
    private val listener = BillingCommandListener(codec, processor, listOf(generateQuote))

    @AfterEach
    fun clearMdc() = MDC.clear()

    private fun message(type: String, messageId: UUID = UUID.randomUUID()) = codec.encode(
        MessageEnvelope(
            messageId = messageId,
            sagaId = UUID.randomUUID(),
            type = type,
            occurredAt = Instant.now(),
            correlationId = "corr-$type",
            payload = codec.toTree(mapOf("any" to 1)),
        ),
    )

    private fun processorRunsEffect(applied: Boolean = true) {
        every { processor.process(any(), any()) } answers {
            if (applied) secondArg<() -> Unit>().invoke()
            applied
        }
    }

    @Test
    fun `handled command runs through the idempotent processor with correlation in the MDC`() {
        processorRunsEffect()
        MDC.put("requestId", "outer")

        listener.onMessage(message("GenerateQuote"))

        assertEquals(1, handled.size)
        val mdc = seenMdc.single()!!
        assertEquals("corr-GenerateQuote", mdc["requestId"])
        assertEquals(handled.single().sagaId.toString(), mdc["sagaId"])
        assertEquals(handled.single().messageId.toString(), mdc["messageId"])
        // MDC restored after processing.
        assertEquals("outer", MDC.get("requestId"))
        assertNull(MDC.get("sagaId"))
    }

    @Test
    fun `duplicate is acknowledged without running the handler`() {
        processorRunsEffect(applied = false)

        listener.onMessage(message("GenerateQuote"))

        assertEquals(0, handled.size)
        assertNull(MDC.get("requestId"))
    }

    @Test
    fun `contract commands not handled yet are acknowledged`() {
        listener.onMessage(message("CancelQuote"))
        listener.onMessage(message("RefundPayment"))
        listener.onMessage(message("RequestPayment"))

        verify(exactly = 0) { processor.process(any(), any()) }
    }

    @Test
    fun `type outside the contract or an event goes to the DLQ`() {
        assertFailsWith<InvalidMessageException> { listener.onMessage(message("StartExecution")) }
        assertFailsWith<InvalidMessageException> { listener.onMessage(message("QuoteGenerated")) }
        assertFailsWith<InvalidMessageException> {
            listener.onMessage(Message("{broken".toByteArray(), MessageProperties()))
        }
        verify(exactly = 0) { processor.process(any(), any()) }
    }

    @Test
    fun `handler failure propagates for the container retry`() {
        every { processor.process(any(), any()) } throws IllegalStateException("database down")

        assertFailsWith<IllegalStateException> { listener.onMessage(message("GenerateQuote")) }
        assertNull(MDC.get("requestId"))
    }
}
