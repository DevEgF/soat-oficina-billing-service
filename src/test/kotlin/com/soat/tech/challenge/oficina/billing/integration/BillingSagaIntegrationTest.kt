package com.soat.tech.challenge.oficina.billing.integration

import com.soat.tech.challenge.oficina.billing.support.IntegrationTest
import com.soat.tech.challenge.oficina.billing.support.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
 * Billing exercised by its edges, with the test playing the OS Service: commands are published on the
 * `oficina` exchange with the envelope of the spec, events are read from a test queue bound to the
 * exchange, decisions and queries go through HTTP.
 */
@IntegrationTest
class BillingSagaIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rabbit: RabbitTemplate

    @Autowired
    private lateinit var admin: AmqpAdmin

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: JsonMapper

    private val http: HttpClient = HttpClient.newHttpClient()
    private val receivedEvents = mutableListOf<Message>()
    private val deadLettered = mutableListOf<Message>()

    @BeforeEach
    fun declareTestQueue() {
        val queue = Queue(EVENTS_QUEUE, false, false, false)
        admin.declareQueue(queue)
        admin.declareBinding(BindingBuilder.bind(queue).to(TopicExchange(EXCHANGE)).with("event.billing.#"))
    }

    @Test
    fun `catalog lists the five services and five parts of the contract`() {
        val services = getJson("/api/catalog/services")
        val parts = getJson("/api/catalog/parts")

        assertEquals(5, services.size())
        assertEquals(5, parts.size())
        val servicePrices = services.associate { it["code"].stringValue() to it["priceCents"].asLong() }
        assertEquals(
            mapOf(
                "TROCA_OLEO" to 12000L,
                "ALINHAMENTO_BALANCEAMENTO" to 18000L,
                "REVISAO_FREIOS" to 25000L,
                "DIAGNOSTICO_ELETRONICO" to 15000L,
                "TROCA_PNEUS" to 8000L,
            ),
            servicePrices,
        )
        val partPrices = parts.associate { it["code"].stringValue() to it["priceCents"].asLong() }
        assertEquals(
            mapOf(
                "FILTRO_OLEO" to 4500L,
                "OLEO_5W30_1L" to 5500L,
                "PASTILHA_FREIO" to 16000L,
                "PNEU_195_55_R15" to 42000L,
                "BATERIA_60AH" to 52000L,
            ),
            partPrices,
        )
        assertEquals(30, services.first { it["code"].stringValue() == "TROCA_OLEO" }["estimatedMinutes"].asInt())
        assertEquals(401, get("/api/catalog/services", token = null).statusCode())
    }

    @Test
    fun `happy path from GenerateQuote to PaymentApproved`() {
        val workOrderId = UUID.randomUUID()
        val correlationId = "corr-${UUID.randomUUID()}"
        assertEquals(404, get("/api/work-orders/$workOrderId/quote").statusCode())

        publishCommand("GenerateQuote", "command.billing.generate-quote", workOrderId, generateQuotePayload(workOrderId), correlationId)

        val generated = awaitEvent("QuoteGenerated", workOrderId)
        val generatedEnvelope = envelope(generated)
        assertEquals(MessageProperties.CONTENT_TYPE_JSON, generated.messageProperties.contentType)
        assertEquals(MessageDeliveryMode.PERSISTENT, generated.messageProperties.receivedDeliveryMode)
        assertEquals("QuoteGenerated", generated.messageProperties.type)
        assertEquals(generatedEnvelope["messageId"].stringValue(), generated.messageProperties.messageId)
        assertEquals(correlationId, generated.messageProperties.headers["x-correlation-id"])
        assertEquals("event.billing.quote-generated", generated.messageProperties.receivedRoutingKey)
        assertEquals(workOrderId.toString(), generatedEnvelope["sagaId"].stringValue())
        assertEquals(correlationId, generatedEnvelope["correlationId"].stringValue())
        Instant.parse(generatedEnvelope["occurredAt"].stringValue())
        val generatedPayload = generatedEnvelope["payload"]
        val quoteId = generatedPayload["quoteId"].stringValue()
        assertEquals(workOrderId.toString(), generatedPayload["workOrderId"].stringValue())
        assertEquals(12000, generatedPayload["servicesTotalCents"].asLong())
        assertEquals(9000, generatedPayload["partsTotalCents"].asLong())
        assertEquals(21000, generatedPayload["totalCents"].asLong())

        val quote = getJson("/api/work-orders/$workOrderId/quote")
        assertEquals(quoteId, quote["id"].stringValue())
        assertEquals("PENDING_APPROVAL", quote["status"].stringValue())
        assertEquals(21000, quote["totalCents"].asLong())
        assertTrue(quote["decidedAt"].isNull)
        val lines = quote["lines"]
        assertEquals(2, lines.size())
        assertEquals("SERVICE", lines[0]["kind"].stringValue())
        assertEquals("Troca de óleo", lines[0]["name"].stringValue())
        assertEquals("PART", lines[1]["kind"].stringValue())
        assertEquals(2, lines[1]["quantity"].asInt())
        assertEquals(4500, lines[1]["unitPriceCents"].asLong())
        assertEquals(9000, lines[1]["totalCents"].asLong())

        assertEquals(400, post("/api/work-orders/$workOrderId/quote/decision", "{}").statusCode())
        val decision = post("/api/work-orders/$workOrderId/quote/decision", """{"approved": true}""")
        assertEquals(200, decision.statusCode())
        val approvedQuote = mapper.readTree(decision.body())
        assertEquals("APPROVED", approvedQuote["status"].stringValue())
        assertFalse(approvedQuote["decidedAt"].isNull)
        assertEquals(409, post("/api/work-orders/$workOrderId/quote/decision", """{"approved": false}""").statusCode())

        val approved = envelope(awaitEvent("QuoteApproved", workOrderId))
        // Published from an HTTP request, with the correlation id stored with the quote.
        assertEquals(correlationId, approved["correlationId"].stringValue())
        assertEquals(quoteId, approved["payload"]["quoteId"].stringValue())
        assertEquals(21000, approved["payload"]["totalCents"].asLong())

        assertEquals(404, get("/api/work-orders/$workOrderId/payment").statusCode())
        publishCommand(
            "RequestPayment",
            "command.billing.request-payment",
            workOrderId,
            mapOf("workOrderId" to workOrderId.toString(), "quoteId" to quoteId, "amountCents" to 21000),
            correlationId,
        )

        val paymentApproved = envelope(awaitEvent("PaymentApproved", workOrderId))
        val paymentPayload = paymentApproved["payload"]
        assertEquals(correlationId, paymentApproved["correlationId"].stringValue())
        assertEquals(quoteId, paymentPayload["quoteId"].stringValue())
        assertEquals(21000, paymentPayload["amountCents"].asLong())
        assertEquals("SIMULATED", paymentPayload["provider"].stringValue())
        assertTrue(paymentPayload["providerPaymentId"].stringValue().startsWith("sim-"))

        val payment = getJson("/api/work-orders/$workOrderId/payment")
        assertEquals(paymentPayload["paymentId"].stringValue(), payment["id"].stringValue())
        assertEquals("APPROVED", payment["status"].stringValue())
        assertEquals(quoteId, payment["quoteId"].stringValue())
        assertEquals("SIMULATED", payment["provider"].stringValue())
        assertEquals(paymentPayload["providerPaymentId"].stringValue(), payment["providerPaymentId"].stringValue())
        assertNotNull(payment["createdAt"].stringValue())
    }

    @Test
    fun `item outside the catalog publishes QuoteGenerationFailed and stores no quote`() {
        val workOrderId = UUID.randomUUID()
        val payload = generateQuotePayload(workOrderId, serviceCode = "SERVICO_INEXISTENTE")

        publishCommand("GenerateQuote", "command.billing.generate-quote", workOrderId, payload)

        val failed = envelope(awaitEvent("QuoteGenerationFailed", workOrderId))
        assertTrue(failed["payload"]["reason"].stringValue().contains("SERVICO_INEXISTENTE"))
        assertEquals(404, get("/api/work-orders/$workOrderId/quote").statusCode())
    }

    @Test
    fun `customer rejection publishes QuoteRejected with the reason`() {
        val workOrderId = UUID.randomUUID()
        publishCommand("GenerateQuote", "command.billing.generate-quote", workOrderId, generateQuotePayload(workOrderId))
        awaitEvent("QuoteGenerated", workOrderId)

        val response = post("/api/work-orders/$workOrderId/quote/decision", """{"approved": false, "reason": "Muito caro"}""")

        assertEquals(200, response.statusCode())
        assertEquals("REJECTED", mapper.readTree(response.body())["status"].stringValue())
        val rejected = envelope(awaitEvent("QuoteRejected", workOrderId))
        assertEquals("Muito caro", rejected["payload"]["reason"].stringValue())
        assertEquals(404, post("/api/work-orders/${UUID.randomUUID()}/quote/decision", """{"approved": true}""").statusCode())
    }

    @Test
    fun `repeated messageId does not duplicate the effect`() {
        val workOrderId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        val payload = generateQuotePayload(workOrderId)

        publishCommand("GenerateQuote", "command.billing.generate-quote", workOrderId, payload, messageId = messageId)
        awaitEvent("QuoteGenerated", workOrderId)
        publishCommand("GenerateQuote", "command.billing.generate-quote", workOrderId, payload, messageId = messageId)
        // Single consumer, in order: once the sentinel is answered the duplicate was already handled.
        awaitSentinel()

        assertEquals(1, receivedEvents.count { isEvent(it, "QuoteGenerated", workOrderId) })
        assertEquals(1, count("SELECT COUNT(*) FROM quote WHERE work_order_id = ?", workOrderId))
        assertEquals(1, count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", messageId))
    }

    @Test
    fun `invalid JSON and type outside the contract go to the DLQ`() {
        val invalidJsonId = UUID.randomUUID().toString()
        rabbit.send(EXCHANGE, "command.billing.generate-quote", rawMessage("{not json".toByteArray(), invalidJsonId))
        val unknownTypeId = UUID.randomUUID()
        publishCommand("DoSomethingElse", "command.billing.generate-quote", UUID.randomUUID(), mapOf("x" to 1), messageId = unknownTypeId)
        val missingFieldId = UUID.randomUUID()
        val workOrderId = UUID.randomUUID()
        publishCommand(
            "RequestPayment",
            "command.billing.request-payment",
            workOrderId,
            mapOf("workOrderId" to workOrderId.toString()),
            messageId = missingFieldId,
        )

        listOf(invalidJsonId, unknownTypeId.toString(), missingFieldId.toString()).forEach { id ->
            val dead = awaitDeadLetter(id)
            assertEquals("billing.commands.dlq", dead.messageProperties.receivedRoutingKey)
        }
    }

    @Test
    fun `failure during processing is retried three times and then dead-lettered`() {
        val workOrderId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        // tracking_code is VARCHAR(64): the insert fails in the database, a failure the listener
        // does not classify as an invalid message, so the container retry applies.
        val payload = generateQuotePayload(workOrderId) + ("trackingCode" to "T".repeat(80))
        val start = System.currentTimeMillis()

        publishCommand("GenerateQuote", "command.billing.generate-quote", workOrderId, payload, messageId = messageId)

        awaitDeadLetter(messageId.toString())
        // 3 attempts with 500 ms and 1 s between them.
        assertTrue(System.currentTimeMillis() - start >= 1_400, "dead-lettered before the retries")
        assertEquals(0, count("SELECT COUNT(*) FROM quote WHERE work_order_id = ?", workOrderId))
        assertEquals(0, count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", messageId))
    }

    @Test
    fun `compensation commands and out of order commands are acknowledged without effect`() {
        val workOrderId = UUID.randomUUID()
        val cancelId = UUID.randomUUID()
        publishCommand(
            "CancelQuote",
            "command.billing.cancel-quote",
            workOrderId,
            mapOf("workOrderId" to workOrderId.toString(), "quoteId" to UUID.randomUUID().toString(), "reason" to "x"),
            messageId = cancelId,
        )
        val earlyPaymentId = UUID.randomUUID()
        publishCommand(
            "RequestPayment",
            "command.billing.request-payment",
            workOrderId,
            mapOf("workOrderId" to workOrderId.toString(), "quoteId" to UUID.randomUUID().toString(), "amountCents" to 100),
            messageId = earlyPaymentId,
        )
        awaitSentinel()

        drainDeadLetters()
        assertFalse(deadLettered.any { it.messageProperties.messageId in setOf(cancelId.toString(), earlyPaymentId.toString()) })
        assertEquals(404, get("/api/work-orders/$workOrderId/payment").statusCode())
        assertEquals(0, count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", cancelId))
        assertEquals(1, count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", earlyPaymentId))
    }

    // ---------- OS Service side ----------

    private fun generateQuotePayload(workOrderId: UUID, serviceCode: String = "TROCA_OLEO") = mapOf(
        "workOrderId" to workOrderId.toString(),
        "trackingCode" to "TRK-${workOrderId.toString().take(8)}",
        "customer" to mapOf("name" to "Maria Silva", "email" to "maria@example.com"),
        "services" to listOf(mapOf("code" to serviceCode, "quantity" to 1)),
        "parts" to listOf(mapOf("code" to "FILTRO_OLEO", "quantity" to 2)),
        "unknownField" to "ignored",
    )

    private fun publishCommand(
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

    private fun rawMessage(body: ByteArray, messageId: String) = Message(
        body,
        MessageProperties().apply {
            contentType = MessageProperties.CONTENT_TYPE_JSON
            deliveryMode = MessageDeliveryMode.PERSISTENT
            this.messageId = messageId
        },
    )

    /** A valid GenerateQuote whose answer proves every message published before it was consumed. */
    private fun awaitSentinel() {
        val sentinel = UUID.randomUUID()
        publishCommand("GenerateQuote", "command.billing.generate-quote", sentinel, generateQuotePayload(sentinel))
        awaitEvent("QuoteGenerated", sentinel)
    }

    private fun awaitEvent(type: String, workOrderId: UUID): Message {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (true) {
            receivedEvents.firstOrNull { isEvent(it, type, workOrderId) }?.let { return it }
            check(System.currentTimeMillis() < deadline) { "No $type for $workOrderId within ${TIMEOUT_MILLIS}ms" }
            rabbit.receive(EVENTS_QUEUE, POLL_MILLIS)?.let(receivedEvents::add)
        }
    }

    private fun isEvent(message: Message, type: String, workOrderId: UUID): Boolean {
        val envelope = envelope(message)
        return envelope["type"].stringValue() == type && envelope["sagaId"].stringValue() == workOrderId.toString()
    }

    private fun awaitDeadLetter(messageId: String): Message {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (true) {
            deadLettered.firstOrNull { it.messageProperties.messageId == messageId }?.let { return it }
            check(System.currentTimeMillis() < deadline) { "Message $messageId not dead-lettered within ${TIMEOUT_MILLIS}ms" }
            rabbit.receive(DLQ, POLL_MILLIS)?.let(deadLettered::add)
        }
    }

    private fun drainDeadLetters() {
        while (true) {
            deadLettered += rabbit.receive(DLQ, POLL_MILLIS) ?: return
        }
    }

    private fun envelope(message: Message): JsonNode = mapper.readTree(message.body)

    private fun count(sql: String, id: UUID): Int = jdbc.queryForObject(sql, Int::class.java, id)!!

    // ---------- HTTP ----------

    private fun get(path: String, token: String? = TestJwt.token()): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        token?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun getJson(path: String): JsonNode {
        val response = get(path)
        assertEquals(200, response.statusCode(), response.body())
        return mapper.readTree(response.body())
    }

    private fun post(path: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Authorization", "Bearer ${TestJwt.token()}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        const val EXCHANGE = "oficina"
        const val EVENTS_QUEUE = "test.billing.events"
        const val DLQ = "billing.commands.dlq"
        const val TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 250L
    }
}
