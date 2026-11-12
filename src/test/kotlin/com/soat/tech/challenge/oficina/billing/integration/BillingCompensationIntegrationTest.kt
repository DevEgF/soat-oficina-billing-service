package com.soat.tech.challenge.oficina.billing.integration

import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.infrastructure.payment.SimulatedPaymentGateway
import com.soat.tech.challenge.oficina.billing.support.IntegrationTest
import com.soat.tech.challenge.oficina.billing.support.SagaIntegrationSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Compensations (CancelQuote, RefundPayment) and the periodic check of pending payments, exercised
 * by the edges of the service with the test playing the OS Service.
 */
@IntegrationTest
class BillingCompensationIntegrationTest : SagaIntegrationSupport() {

    @Autowired
    private lateinit var simulatedGateway: SimulatedPaymentGateway

    @Test
    fun `CancelQuote cancels an open quote and a repeated command confirms again`() {
        val workOrderId = UUID.randomUUID()
        val correlationId = "corr-${UUID.randomUUID()}"
        val quoteId = generateQuote(workOrderId, correlationId)
        val messageId = UUID.randomUUID()

        cancelQuote(workOrderId, quoteId, messageId = messageId)

        val cancelled = envelope(awaitEvent("QuoteCancelled", workOrderId))
        assertEquals(correlationId, cancelled["correlationId"].stringValue())
        assertEquals(quoteId, cancelled["payload"]["quoteId"].stringValue())
        assertEquals(workOrderId.toString(), cancelled["payload"]["workOrderId"].stringValue())
        assertEquals("CANCELLED", getJson("/api/work-orders/$workOrderId/quote")["status"].stringValue())
        assertEquals(409, post("/api/work-orders/$workOrderId/quote/decision", """{"approved": true}""").statusCode())

        // Same messageId: ignored. New messageId: QuoteCancelled again, state unchanged.
        cancelQuote(workOrderId, quoteId, messageId = messageId)
        awaitSentinel()
        assertEquals(1, countEvents("QuoteCancelled", workOrderId))
        cancelQuote(workOrderId, quoteId)
        awaitSentinel()
        drainEvents()
        assertEquals(2, countEvents("QuoteCancelled", workOrderId))
        assertEquals("CANCELLED", getJson("/api/work-orders/$workOrderId/quote")["status"].stringValue())
    }

    @Test
    fun `CancelQuote cancels an approved quote and confirms a rejected one without changing it`() {
        val approvedId = UUID.randomUUID()
        val approvedQuote = generateQuote(approvedId)
        assertEquals(200, post("/api/work-orders/$approvedId/quote/decision", """{"approved": true}""").statusCode())
        val rejectedId = UUID.randomUUID()
        val rejectedQuote = generateQuote(rejectedId)
        assertEquals(200, post("/api/work-orders/$rejectedId/quote/decision", """{"approved": false}""").statusCode())

        cancelQuote(approvedId, approvedQuote)
        cancelQuote(rejectedId, rejectedQuote)

        awaitEvent("QuoteCancelled", approvedId)
        awaitEvent("QuoteCancelled", rejectedId)
        assertEquals("CANCELLED", getJson("/api/work-orders/$approvedId/quote")["status"].stringValue())
        assertEquals("REJECTED", getJson("/api/work-orders/$rejectedId/quote")["status"].stringValue())
    }

    @Test
    fun `compensation without a quote or payment is acknowledged without event`() {
        val workOrderId = UUID.randomUUID()
        val cancelId = UUID.randomUUID()
        val refundId = UUID.randomUUID()
        cancelQuote(workOrderId, UUID.randomUUID().toString(), messageId = cancelId)
        refundPayment(workOrderId, UUID.randomUUID().toString(), messageId = refundId)

        awaitSentinel()
        drainEvents()
        drainDeadLetters()

        assertEquals(0, countEvents("QuoteCancelled", workOrderId))
        assertEquals(0, countEvents("PaymentRefunded", workOrderId))
        assertFalse(deadLettered.any { it.messageProperties.messageId in setOf(cancelId.toString(), refundId.toString()) })
        assertEquals(1, count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", cancelId))
        assertEquals(1, count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", refundId))
    }

    @Test
    fun `RefundPayment refunds an approved payment and a repeated command confirms again`() {
        val workOrderId = UUID.randomUUID()
        val correlationId = "corr-${UUID.randomUUID()}"
        val quoteId = generateQuote(workOrderId, correlationId)
        approve(workOrderId, "APRO")
        requestPayment(workOrderId, quoteId)
        val paymentId = envelope(awaitEvent("PaymentApproved", workOrderId))["payload"]["paymentId"].stringValue()
        val messageId = UUID.randomUUID()

        refundPayment(workOrderId, paymentId, messageId = messageId)

        val refunded = envelope(awaitEvent("PaymentRefunded", workOrderId))
        assertEquals(correlationId, refunded["correlationId"].stringValue())
        assertEquals(paymentId, refunded["payload"]["paymentId"].stringValue())
        assertEquals("REFUNDED", getJson("/api/work-orders/$workOrderId/payment")["status"].stringValue())

        refundPayment(workOrderId, paymentId, messageId = messageId)
        awaitSentinel()
        assertEquals(1, countEvents("PaymentRefunded", workOrderId))
        refundPayment(workOrderId, paymentId)
        awaitSentinel()
        drainEvents()
        assertEquals(2, countEvents("PaymentRefunded", workOrderId))
        assertEquals("REFUNDED", getJson("/api/work-orders/$workOrderId/payment")["status"].stringValue())
    }

    @Test
    fun `RefundPayment of a payment that was not approved is acknowledged without event`() {
        val workOrderId = UUID.randomUUID()
        val quoteId = generateQuote(workOrderId)
        approve(workOrderId, "OTHE")
        requestPayment(workOrderId, quoteId)
        val paymentId = envelope(awaitEvent("PaymentRejected", workOrderId))["payload"]["paymentId"].stringValue()

        refundPayment(workOrderId, paymentId)
        awaitSentinel()
        drainEvents()

        assertEquals(0, countEvents("PaymentRefunded", workOrderId))
        assertEquals("REJECTED", getJson("/api/work-orders/$workOrderId/payment")["status"].stringValue())
    }

    @Test
    fun `provider failure on refund is retried and dead-lettered keeping the payment approved`() {
        val workOrderId = UUID.randomUUID()
        val quoteId = UUID.fromString(generateQuote(workOrderId))
        approve(workOrderId, "APRO")
        // Approved payment whose charge the provider does not know: the refund call fails.
        val paymentId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            INSERT INTO payment (id, work_order_id, quote_id, amount_cents, status, provider, provider_payment_id,
                                 status_detail, correlation_id, created_at, updated_at, version)
            VALUES (?, ?, ?, 21000, 'APPROVED', 'SIMULATED', 'sim-unknown-charge', 'accredited', 'corr-x', ?, ?, 0)
            """.trimIndent(),
            paymentId, workOrderId, quoteId, now, now,
        )
        val messageId = UUID.randomUUID()

        refundPayment(workOrderId, paymentId.toString(), messageId = messageId)

        awaitDeadLetter(messageId.toString())
        assertEquals("APPROVED", getJson("/api/work-orders/$workOrderId/payment")["status"].stringValue())
        assertEquals(0, count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", messageId))
    }

    @Test
    fun `periodic check publishes PaymentApproved when the provider approves a pending payment`() {
        val workOrderId = UUID.randomUUID()
        val correlationId = "corr-${UUID.randomUUID()}"
        val quoteId = generateQuote(workOrderId, correlationId)
        approve(workOrderId, "CONT")
        requestPayment(workOrderId, quoteId)
        val providerPaymentId = awaitPendingPayment(workOrderId)

        simulatedGateway.settle(providerPaymentId, GatewayPaymentStatus.APPROVED, "accredited")

        val approved = envelope(awaitEvent("PaymentApproved", workOrderId))
        assertEquals(correlationId, approved["correlationId"].stringValue())
        assertEquals(providerPaymentId, approved["payload"]["providerPaymentId"].stringValue())
        assertEquals(21000, approved["payload"]["amountCents"].asLong())
        assertEquals("APPROVED", getJson("/api/work-orders/$workOrderId/payment")["status"].stringValue())
    }

    @Test
    fun `periodic check publishes PaymentRejected when the provider rejects a pending payment`() {
        val workOrderId = UUID.randomUUID()
        val quoteId = generateQuote(workOrderId)
        approve(workOrderId, "CONT")
        requestPayment(workOrderId, quoteId)
        val providerPaymentId = awaitPendingPayment(workOrderId)

        simulatedGateway.settle(providerPaymentId, GatewayPaymentStatus.REJECTED, "rejected_by_issuer")

        val rejected = envelope(awaitEvent("PaymentRejected", workOrderId))
        assertEquals("rejected_by_issuer", rejected["payload"]["reason"].stringValue())
        val payment = getJson("/api/work-orders/$workOrderId/payment")
        assertEquals("REJECTED", payment["status"].stringValue())
        assertEquals("rejected_by_issuer", payment["statusDetail"].stringValue())
    }

    // ---------- helpers ----------

    private fun generateQuote(workOrderId: UUID, correlationId: String = "corr-${UUID.randomUUID()}"): String {
        publishCommand(
            "GenerateQuote",
            "command.billing.generate-quote",
            workOrderId,
            generateQuotePayload(workOrderId),
            correlationId,
        )
        return envelope(awaitEvent("QuoteGenerated", workOrderId))["payload"]["quoteId"].stringValue()
    }

    private fun approve(workOrderId: UUID, cardholderName: String) {
        val response = post(
            "/api/work-orders/$workOrderId/quote/decision",
            """{"approved": true, "payment": {"cardholderName": "$cardholderName"}}""",
        )
        assertEquals(200, response.statusCode(), response.body())
    }

    private fun requestPayment(workOrderId: UUID, quoteId: String) = publishCommand(
        "RequestPayment",
        "command.billing.request-payment",
        workOrderId,
        mapOf("workOrderId" to workOrderId.toString(), "quoteId" to quoteId, "amountCents" to 21000),
    )

    private fun cancelQuote(workOrderId: UUID, quoteId: String, messageId: UUID = UUID.randomUUID()) = publishCommand(
        "CancelQuote",
        "command.billing.cancel-quote",
        workOrderId,
        mapOf("workOrderId" to workOrderId.toString(), "quoteId" to quoteId, "reason" to "PaymentRejected"),
        messageId = messageId,
    )

    private fun refundPayment(workOrderId: UUID, paymentId: String, messageId: UUID = UUID.randomUUID()) = publishCommand(
        "RefundPayment",
        "command.billing.refund-payment",
        workOrderId,
        mapOf("workOrderId" to workOrderId.toString(), "paymentId" to paymentId, "reason" to "ExecutionFailed"),
        messageId = messageId,
    )

    /** Waits for the payment to be stored PENDING and checks that no result was published meanwhile. */
    private fun awaitPendingPayment(workOrderId: UUID): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (get("/api/work-orders/$workOrderId/payment").statusCode() == 404) {
            check(System.currentTimeMillis() < deadline) { "No payment for $workOrderId" }
            Thread.sleep(POLL_MILLIS)
        }
        val payment = getJson("/api/work-orders/$workOrderId/payment")
        assertEquals("PENDING", payment["status"].stringValue())
        // Several passes of the periodic check (1 s) while the provider keeps it pending.
        Thread.sleep(2_500)
        drainEvents()
        assertEquals(0, countEvents("PaymentApproved", workOrderId) + countEvents("PaymentRejected", workOrderId))
        assertEquals("PENDING", getJson("/api/work-orders/$workOrderId/payment")["status"].stringValue())
        return payment["providerPaymentId"].stringValue().also { assertTrue(it.startsWith("sim-")) }
    }
}
