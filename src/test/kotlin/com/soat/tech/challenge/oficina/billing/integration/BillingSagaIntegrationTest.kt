package com.soat.tech.challenge.oficina.billing.integration

import com.soat.tech.challenge.oficina.billing.support.IntegrationTest
import com.soat.tech.challenge.oficina.billing.support.SagaIntegrationSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import java.time.Instant
import java.util.UUID

/**
 * Billing exercised by its edges, with the test playing the OS Service: commands are published on the
 * `oficina` exchange with the saga envelope, events are read from a test queue bound to the
 * exchange, decisions and queries go through HTTP.
 */
@IntegrationTest
class BillingSagaIntegrationTest : SagaIntegrationSupport() {

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
    fun `cardholder name of the approval drives the simulated provider`() {
        val workOrderId = UUID.randomUUID()
        publishCommand("GenerateQuote", "command.billing.generate-quote", workOrderId, generateQuotePayload(workOrderId))
        val quoteId = envelope(awaitEvent("QuoteGenerated", workOrderId))["payload"]["quoteId"].stringValue()

        val invalid = post(
            "/api/work-orders/$workOrderId/quote/decision",
            """{"approved": true, "payment": {"cardholderName": " "}}""",
        )
        assertEquals(400, invalid.statusCode())
        val decision = post(
            "/api/work-orders/$workOrderId/quote/decision",
            """{"approved": true, "payment": {"cardholderName": "OTHE"}}""",
        )
        assertEquals(200, decision.statusCode())
        publishCommand(
            "RequestPayment",
            "command.billing.request-payment",
            workOrderId,
            mapOf("workOrderId" to workOrderId.toString(), "quoteId" to quoteId, "amountCents" to 21000),
        )

        val rejected = envelope(awaitEvent("PaymentRejected", workOrderId))["payload"]
        assertEquals("OTHE", rejected["reason"].stringValue())
        assertEquals(quoteId, rejected["quoteId"].stringValue())
        val payment = getJson("/api/work-orders/$workOrderId/payment")
        assertEquals("REJECTED", payment["status"].stringValue())
        assertEquals("OTHE", payment["statusDetail"].stringValue())
    }

    @Test
    fun `out of order commands are acknowledged without effect`() {
        val workOrderId = UUID.randomUUID()
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
        assertFalse(deadLettered.any { it.messageProperties.messageId == earlyPaymentId.toString() })
        assertEquals(404, get("/api/work-orders/$workOrderId/payment").statusCode())
        assertEquals(1, count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", earlyPaymentId))
    }
}
