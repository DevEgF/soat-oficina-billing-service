package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import com.soat.tech.challenge.oficina.billing.application.messaging.GenerateQuotePayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteGeneratedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.RequestPaymentPayload
import com.soat.tech.challenge.oficina.billing.domain.model.TraceContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

class EnvelopeCodecTest {

    private val mapper = jacksonMapperBuilder().build()
    private val codec = EnvelopeCodec(mapper)
    private val messageId = UUID.fromString("0b0e6f0e-8a57-4a5e-9d0b-3a1f0c1d2e3f")
    private val sagaId = UUID.fromString("7d4b1c6a-1111-4a5e-9d0b-3a1f0c1d2e3f")

    private fun message(json: String, headers: Map<String, Any> = emptyMap()) =
        Message(json.toByteArray(), MessageProperties().apply { headers.forEach(::setHeader) })

    private fun envelopeJson(
        payload: String = """{"workOrderId":"$sagaId","quoteId":"$messageId","amountCents":100}""",
        correlation: String? = "\"corr-1\"",
        type: String = "\"RequestPayment\"",
        occurredAt: String = "\"2026-11-12T15:04:05.123Z\"",
        messageIdValue: String = "\"$messageId\"",
    ) = buildString {
        append("""{"messageId":$messageIdValue,"sagaId":"$sagaId","type":$type,"occurredAt":$occurredAt""")
        correlation?.let { append(""","correlationId":$it""") }
        append(""","payload":$payload,"extra":true}""")
    }

    @Test
    fun `decodes the saga envelope and its payload ignoring unknown fields`() {
        val envelope = codec.decode(message(envelopeJson()))

        assertEquals(messageId, envelope.messageId)
        assertEquals(sagaId, envelope.sagaId)
        assertEquals("RequestPayment", envelope.type)
        assertEquals(Instant.parse("2026-11-12T15:04:05.123Z"), envelope.occurredAt)
        assertEquals("corr-1", envelope.correlationId)
        assertEquals(RequestPaymentPayload(sagaId, messageId, 100), codec.payload(envelope, RequestPaymentPayload::class.java))
    }

    @Test
    fun `trace headers of the message become the trace context of the envelope`() {
        val traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val message = message(
            envelopeJson(),
            mapOf("traceparent" to traceparent, "newrelic" to "eyJ2IjpbMCwxXX0=", "x-death-count" to 1),
        )

        val envelope = codec.decode(message)

        assertEquals(TraceContext(traceparent = traceparent, newrelic = "eyJ2IjpbMCwxXX0="), envelope.traceContext)
        assertEquals(mapOf("traceparent" to traceparent, "newrelic" to "eyJ2IjpbMCwxXX0="), codec.transportHeaders(message))
        assertNull(codec.decode(message(envelopeJson())).traceContext)
        // The trace context travels in AMQP headers only, never in the JSON body.
        assertFalse(mapper.readTree(codec.encode(envelope).body).has("traceContext"))
    }

    @Test
    fun `optional payload fields take their defaults`() {
        val payload = """{"workOrderId":"$sagaId","customer":{"name":"Maria"},"services":[{"code":"TROCA_OLEO","quantity":1}],"x":1}"""
        val decoded = codec.payload(codec.decode(message(envelopeJson(payload = payload))), GenerateQuotePayload::class.java)

        assertNull(decoded.trackingCode)
        assertNull(decoded.customer.email)
        assertEquals(emptyList<Any>(), decoded.parts)
        assertEquals("TROCA_OLEO", decoded.services.single().code)
    }

    @Test
    fun `correlation id falls back to the header`() {
        val envelope = codec.decode(message(envelopeJson(correlation = null), mapOf("x-correlation-id" to "from-header")))
        assertEquals("from-header", envelope.correlationId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{not json", "[1,2]", "\"text\"", ""])
    fun `body that is not a JSON object is invalid`(body: String) {
        assertFailsWith<InvalidMessageException> { codec.decode(message(body)) }
    }

    @Test
    fun `missing or malformed envelope fields are invalid`() {
        val invalid = listOf(
            envelopeJson(payload = "[]"),
            envelopeJson(payload = "null"),
            envelopeJson(correlation = null),
            envelopeJson(correlation = "\"  \""),
            envelopeJson(type = "null"),
            envelopeJson(type = "42"),
            envelopeJson(occurredAt = "\"yesterday\""),
            envelopeJson(occurredAt = "null"),
            envelopeJson(messageIdValue = "\"not-a-uuid\""),
            envelopeJson(messageIdValue = "null"),
        )
        invalid.forEach { json ->
            assertFailsWith<InvalidMessageException>(json) { codec.decode(message(json)) }
        }
    }

    @Test
    fun `payload that does not match the contract is invalid`() {
        val envelope = codec.decode(message(envelopeJson(payload = """{"workOrderId":"$sagaId"}""")))
        assertFailsWith<InvalidMessageException> { codec.payload(envelope, RequestPaymentPayload::class.java) }
    }

    @Test
    fun `encodes json body with the contract message properties`() {
        val quoteId = UUID.randomUUID()
        val envelope = MessageEnvelope(
            messageId = messageId,
            sagaId = sagaId,
            type = "QuoteGenerated",
            occurredAt = Instant.parse("2026-11-12T15:04:05.123Z"),
            correlationId = "corr-1",
            payload = codec.toTree(QuoteGeneratedPayload(sagaId, quoteId, 1, 2, 3)),
        )

        val message = codec.encode(envelope)

        val properties = message.messageProperties
        assertEquals("application/json", properties.contentType)
        assertEquals("UTF-8", properties.contentEncoding)
        assertEquals(MessageDeliveryMode.PERSISTENT, properties.deliveryMode)
        assertEquals(messageId.toString(), properties.messageId)
        assertEquals("QuoteGenerated", properties.type)
        assertEquals("corr-1", properties.headers["x-correlation-id"])
        assertFalse(properties.headers.containsKey("__TypeId__"))
        val json = mapper.readTree(message.body)
        assertEquals("2026-11-12T15:04:05.123Z", json["occurredAt"].stringValue())
        assertEquals(sagaId.toString(), json["sagaId"].stringValue())
        assertEquals(quoteId.toString(), json["payload"]["quoteId"].stringValue())
        assertEquals(3, json["payload"]["totalCents"].asLong())
        // Round trip: numeric node types may differ (LongNode vs IntNode), the JSON does not.
        val decoded = codec.decode(message)
        assertEquals(envelope.copy(payload = decoded.payload), decoded)
        assertEquals(envelope.payload.toString(), decoded.payload.toString())
    }
}
