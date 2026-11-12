package com.soat.tech.challenge.oficina.billing.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.amqp.core.AmqpAdmin
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * Edge of the Billing Service as the OS Service sees it: commands published on the `oficina` exchange
 * with the saga envelope, events read from a test queue bound to the exchange, HTTP calls with a
 * valid token.
 */
abstract class SagaIntegrationSupport {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    protected lateinit var rabbit: RabbitTemplate

    @Autowired
    private lateinit var admin: AmqpAdmin

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @Autowired
    protected lateinit var mapper: JsonMapper

    private val http: HttpClient = HttpClient.newHttpClient()
    protected val receivedEvents = mutableListOf<Message>()
    protected val deadLettered = mutableListOf<Message>()

    @BeforeEach
    fun declareTestQueue() {
        val queue = Queue(EVENTS_QUEUE, false, false, false)
        admin.declareQueue(queue)
        admin.declareBinding(BindingBuilder.bind(queue).to(TopicExchange(EXCHANGE)).with("event.billing.#"))
    }

    // ---------- OS Service side ----------

    protected fun generateQuotePayload(workOrderId: UUID, serviceCode: String = "TROCA_OLEO") = mapOf(
        "workOrderId" to workOrderId.toString(),
        "trackingCode" to "TRK-${workOrderId.toString().take(8)}",
        "customer" to mapOf("name" to "Maria Silva", "email" to "maria@example.com"),
        "services" to listOf(mapOf("code" to serviceCode, "quantity" to 1)),
        "parts" to listOf(mapOf("code" to "FILTRO_OLEO", "quantity" to 2)),
        "unknownField" to "ignored",
    )

    protected fun publishCommand(
        type: String,
        routingKey: String,
        sagaId: UUID,
        payload: Any,
        correlationId: String = "corr-${UUID.randomUUID()}",
        messageId: UUID = UUID.randomUUID(),
    ) {
        val envelope = mapOf(
            "messageId" to messageId.toString(),
            "sagaId" to sagaId.toString(),
            "type" to type,
            "occurredAt" to Instant.now().toString(),
            "correlationId" to correlationId,
            "payload" to payload,
        )
        val message = rawMessage(mapper.writeValueAsBytes(envelope), messageId.toString())
        message.messageProperties.type = type
        message.messageProperties.setHeader("x-correlation-id", correlationId)
        rabbit.send(EXCHANGE, routingKey, message)
    }

    protected fun rawMessage(body: ByteArray, messageId: String) = Message(
        body,
        MessageProperties().apply {
            contentType = MessageProperties.CONTENT_TYPE_JSON
            deliveryMode = MessageDeliveryMode.PERSISTENT
            this.messageId = messageId
        },
    )

    /** A valid GenerateQuote whose answer proves every message published before it was consumed. */
    protected fun awaitSentinel() {
        val sentinel = UUID.randomUUID()
        publishCommand("GenerateQuote", "command.billing.generate-quote", sentinel, generateQuotePayload(sentinel))
        awaitEvent("QuoteGenerated", sentinel)
    }

    protected fun awaitEvent(type: String, workOrderId: UUID, timeoutMillis: Long = TIMEOUT_MILLIS): Message {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            receivedEvents.firstOrNull { isEvent(it, type, workOrderId) }?.let { return it }
            check(System.currentTimeMillis() < deadline) { "No $type for $workOrderId within ${timeoutMillis}ms" }
            rabbit.receive(EVENTS_QUEUE, POLL_MILLIS)?.let(receivedEvents::add)
        }
    }

    /** Reads what is already in the test queue, so counts include every event published so far. */
    protected fun drainEvents() {
        while (true) {
            receivedEvents += rabbit.receive(EVENTS_QUEUE, POLL_MILLIS) ?: return
        }
    }

    protected fun countEvents(type: String, workOrderId: UUID): Int = receivedEvents.count { isEvent(it, type, workOrderId) }

    protected fun isEvent(message: Message, type: String, workOrderId: UUID): Boolean {
        val envelope = envelope(message)
        return envelope["type"].stringValue() == type && envelope["sagaId"].stringValue() == workOrderId.toString()
    }

    protected fun awaitDeadLetter(messageId: String): Message {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (true) {
            deadLettered.firstOrNull { it.messageProperties.messageId == messageId }?.let { return it }
            check(System.currentTimeMillis() < deadline) { "Message $messageId not dead-lettered within ${TIMEOUT_MILLIS}ms" }
            rabbit.receive(DLQ, POLL_MILLIS)?.let(deadLettered::add)
        }
    }

    protected fun drainDeadLetters() {
        while (true) {
            deadLettered += rabbit.receive(DLQ, POLL_MILLIS) ?: return
        }
    }

    protected fun envelope(message: Message): JsonNode = mapper.readTree(message.body)

    protected fun count(sql: String, id: UUID): Int = jdbc.queryForObject(sql, Int::class.java, id)!!

    // ---------- HTTP ----------

    protected fun get(path: String, token: String? = TestJwt.token()): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        token?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun getJson(path: String): JsonNode {
        val response = get(path)
        assertEquals(200, response.statusCode(), response.body())
        return mapper.readTree(response.body())
    }

    protected fun post(path: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Authorization", "Bearer ${TestJwt.token()}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    protected companion object {
        const val EXCHANGE = "oficina"
        const val EVENTS_QUEUE = "test.billing.events"
        const val DLQ = "billing.commands.dlq"
        const val TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 250L
    }
}
