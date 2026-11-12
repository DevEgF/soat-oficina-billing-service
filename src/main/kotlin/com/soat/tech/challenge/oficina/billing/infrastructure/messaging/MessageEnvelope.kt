package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** Common envelope of every saga message. */
data class MessageEnvelope(
    val messageId: UUID,
    val sagaId: UUID,
    val type: String,
    val occurredAt: Instant,
    val correlationId: String,
    val payload: JsonNode,
)

/**
 * Message that can never be processed (invalid JSON, missing envelope field, type outside the
 * contract, invalid payload). Goes straight to the DLQ: rejected without requeue and not retried.
 */
class InvalidMessageException(message: String, cause: Throwable? = null) :
    AmqpRejectAndDontRequeueException(message, cause)

/**
 * Explicit (de)serialization of the envelope with the Jackson 3 mapper of Spring Boot: no
 * `__TypeId__` header, body is UTF-8 JSON, type dispatch is done on the `type` field.
 */
@Component
class EnvelopeCodec(private val mapper: JsonMapper) {

    fun decode(message: Message): MessageEnvelope {
        val root = try {
            mapper.readTree(message.body)
        } catch (e: JacksonException) {
            throw InvalidMessageException("Body is not valid JSON", e)
        }
        if (root == null || !root.isObject) {
            throw InvalidMessageException("Body is not a JSON object")
        }
        val payload = root.get("payload")?.takeIf { it.isObject }
            ?: throw InvalidMessageException("Envelope without payload object")
        val correlationId = root.text("correlationId")
            ?: (message.messageProperties.headers[RabbitTopology.CORRELATION_ID_HEADER] as? String)?.takeIf { it.isNotBlank() }
        return MessageEnvelope(
            messageId = root.uuid("messageId"),
            sagaId = root.uuid("sagaId"),
            type = root.text("type") ?: throw InvalidMessageException("Envelope without type"),
            occurredAt = root.instant("occurredAt"),
            correlationId = correlationId ?: throw InvalidMessageException("Envelope without correlationId"),
            payload = payload,
        )
    }

    fun <T : Any> payload(envelope: MessageEnvelope, type: Class<T>): T = try {
        mapper.treeToValue(envelope.payload, type)
    } catch (e: JacksonException) {
        throw InvalidMessageException("Invalid ${envelope.type} payload", e)
    }

    fun encode(envelope: MessageEnvelope): Message {
        val properties = MessageProperties().apply {
            contentType = MessageProperties.CONTENT_TYPE_JSON
            contentEncoding = Charsets.UTF_8.name()
            deliveryMode = MessageDeliveryMode.PERSISTENT
            messageId = envelope.messageId.toString()
            type = envelope.type
            setHeader(RabbitTopology.CORRELATION_ID_HEADER, envelope.correlationId)
        }
        return Message(mapper.writeValueAsBytes(envelope), properties)
    }

    fun toTree(payload: Any): JsonNode = mapper.valueToTree(payload)

    private fun JsonNode.text(field: String): String? =
        get(field)?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() }

    private fun JsonNode.uuid(field: String): UUID {
        val value = text(field) ?: throw InvalidMessageException("Envelope without $field")
        return try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw InvalidMessageException("Envelope $field is not a UUID", e)
        }
    }

    private fun JsonNode.instant(field: String): Instant {
        val value = text(field) ?: throw InvalidMessageException("Envelope without $field")
        return try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            throw InvalidMessageException("Envelope $field is not an ISO-8601 instant", e)
        }
    }
}
