package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteApprovedPayload
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.ProcessedMessageEntity
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.ProcessedMessageJpaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.amqp.AmqpConnectException
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.Exchange
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException
import org.springframework.boot.retry.RetryPolicySettings
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.net.ConnectException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertFailsWith

class MessagingInfrastructureTest {

    private val mapper = jacksonMapperBuilder().build()
    private val codec = EnvelopeCodec(mapper)
    private val clock = Clock.fixed(Instant.parse("2026-11-12T15:04:05.123456Z"), ZoneOffset.UTC)

    @AfterEach
    fun clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    // ---------- Topology ----------

    @Test
    fun `topology declares exchanges queues and DLQs with the contract arguments`() {
        val declarables = RabbitTopologyConfiguration().billingTopology()

        val exchanges = declarables.getDeclarablesByType(Exchange::class.java).associateBy { it.name }
        assertEquals("topic", exchanges.getValue("oficina").type)
        assertEquals("direct", exchanges.getValue("oficina.dlx").type)
        assertTrue(exchanges.values.all { it.isDurable && !it.isAutoDelete })

        val queues = declarables.getDeclarablesByType(Queue::class.java).associateBy { it.name }
        assertEquals(setOf("billing.commands", "billing.commands.dlq", "os.events", "os.events.dlq"), queues.keys)
        listOf("billing.commands", "os.events").forEach { name ->
            val queue = queues.getValue(name)
            assertTrue(queue.isDurable && !queue.isExclusive && !queue.isAutoDelete)
            assertEquals(mapOf("x-dead-letter-exchange" to "oficina.dlx", "x-dead-letter-routing-key" to "$name.dlq"), queue.arguments)
            val dlq = queues.getValue("$name.dlq")
            assertTrue(dlq.isDurable && dlq.arguments.isEmpty())
        }

        val bindings = declarables.getDeclarablesByType(Binding::class.java)
            .map { Triple(it.exchange, it.destination, it.routingKey) }.toSet()
        assertEquals(
            setOf(
                Triple("oficina", "billing.commands", "command.billing.#"),
                Triple("oficina", "os.events", "event.#"),
                Triple("oficina.dlx", "billing.commands.dlq", "billing.commands.dlq"),
                Triple("oficina.dlx", "os.events.dlq", "os.events.dlq"),
            ),
            bindings,
        )
    }

    // ---------- Retry policy ----------

    @Test
    fun `rejected messages are not retried and transient failures are`() {
        val invalid = InvalidMessageException("bad")
        assertFalse(ListenerRetryConfiguration.isRetryable(invalid))
        assertFalse(ListenerRetryConfiguration.isRetryable(ListenerExecutionFailedException("wrapped", invalid)))
        assertFalse(ListenerRetryConfiguration.isRetryable(AmqpRejectAndDontRequeueException("reject")))
        assertTrue(ListenerRetryConfiguration.isRetryable(ListenerExecutionFailedException("wrapped", IllegalStateException())))
        assertTrue(ListenerRetryConfiguration.isRetryable(RuntimeException()))

        val settings = RetryPolicySettings()
        ListenerRetryConfiguration().skipRetryForRejectedMessages().customize(settings)
        assertFalse(settings.exceptionPredicate!!.test(invalid))
        assertTrue(settings.exceptionPredicate!!.test(IllegalStateException()))
    }

    // ---------- Publisher ----------

    private val rabbitTemplate = mockk<RabbitTemplate>(relaxed = true)
    private val publisher = RabbitEventPublisher(rabbitTemplate, codec, clock)
    private val workOrderId = UUID.randomUUID()
    private val event = OutgoingEvent(
        MessageType.QUOTE_APPROVED,
        workOrderId,
        "corr-saga",
        QuoteApprovedPayload(workOrderId, UUID.randomUUID(), 21000),
    )

    @Test
    fun `without transaction the event is published at once with the saga envelope`() {
        val sent = slot<Message>()
        every { rabbitTemplate.send("oficina", "event.billing.quote-approved", capture(sent)) } returns Unit

        publisher.publishAfterCommit(event)

        val envelope = codec.decode(sent.captured)
        assertEquals("QuoteApproved", envelope.type)
        assertEquals(workOrderId, envelope.sagaId)
        assertEquals("corr-saga", envelope.correlationId)
        assertEquals(Instant.parse("2026-11-12T15:04:05.123Z"), envelope.occurredAt)
        assertEquals(21000, envelope.payload["totalCents"].asLong())
        assertEquals(envelope.messageId.toString(), sent.captured.messageProperties.messageId)
    }

    @Test
    fun `inside a transaction the event waits for the commit`() {
        TransactionSynchronizationManager.initSynchronization()

        publisher.publishAfterCommit(event)
        verify(exactly = 0) { rabbitTemplate.send(any<String>(), any<String>(), any<Message>()) }

        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        verify(exactly = 1) { rabbitTemplate.send("oficina", "event.billing.quote-approved", any<Message>()) }
    }

    @Test
    fun `broker failure after commit is logged and not thrown`() {
        every { rabbitTemplate.send(any<String>(), any<String>(), any<Message>()) } throws
            AmqpConnectException(ConnectException("refused"))

        publisher.publishAfterCommit(event)
    }

    @Test
    fun `billing publishes only events`() {
        assertFailsWith<IllegalArgumentException> {
            OutgoingEvent(MessageType.GENERATE_QUOTE, workOrderId, "corr", Unit)
        }
    }

    @Test
    fun `message types resolve by contract name`() {
        assertEquals(MessageType.GENERATE_QUOTE, MessageType.fromTypeName("GenerateQuote"))
        assertEquals(MessageType.PAYMENT_REFUNDED, MessageType.fromTypeName("PaymentRefunded"))
        assertEquals(null, MessageType.fromTypeName("StartExecution"))
        assertEquals(null, MessageType.fromTypeName(null))
        assertTrue(MessageType.entries.filter { it.kind == MessageType.Kind.COMMAND }.all { it.routingKey.startsWith("command.billing.") })
        assertTrue(MessageType.entries.filter { it.kind == MessageType.Kind.EVENT }.all { it.routingKey.startsWith("event.billing.") })
    }

    // ---------- Idempotency ----------

    private val processedRepository = mockk<ProcessedMessageJpaRepository>()
    private val processor = IdempotentMessageProcessor(processedRepository, clock)
    private val envelope = MessageEnvelope(
        UUID.randomUUID(),
        workOrderId,
        "GenerateQuote",
        Instant.now(),
        "corr",
        codec.toTree(mapOf("a" to 1)),
    )

    @Test
    fun `first delivery runs the effect and records the message id`() {
        every { processedRepository.existsById(envelope.messageId) } returns false
        val record = slot<ProcessedMessageEntity>()
        every { processedRepository.saveAndFlush(capture(record)) } answers { record.captured }
        var runs = 0

        assertTrue(processor.process(envelope) { runs++ })

        assertEquals(1, runs)
        assertEquals(envelope.messageId, record.captured.messageId)
        assertEquals(envelope.messageId, record.captured.id)
        assertTrue(record.captured.isNew)
        assertEquals("GenerateQuote", record.captured.type)
        assertEquals(Instant.parse("2026-11-12T15:04:05.123Z"), record.captured.processedAt)
    }

    @Test
    fun `repeated message id skips the effect`() {
        every { processedRepository.existsById(envelope.messageId) } returns true
        var runs = 0

        assertFalse(processor.process(envelope) { runs++ })

        assertEquals(0, runs)
        verify(exactly = 0) { processedRepository.saveAndFlush(any<ProcessedMessageEntity>()) }
    }
}
