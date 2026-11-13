package com.soat.tech.challenge.oficina.billing.infrastructure.observability

import com.newrelic.api.agent.HeaderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/** Without the agent the New Relic API is a no-op: the adapter must just run the work. */
class NewRelicTracingTest {

    private val tracing = NewRelicTracing()
    private val traceHeaders = mapOf(
        "traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        "tracestate" to "1@nr=0-0-1-2-3",
    )

    @Test
    fun `message transaction runs the work and returns its result`() {
        val result = tracing.inMessageTransaction(
            queue = "billing.commands",
            type = "GenerateQuote",
            traceHeaders = traceHeaders,
            attributes = mapOf("sagaId" to "s-1", "messageType" to "GenerateQuote"),
        ) { 42 }

        assertEquals(42, result)
    }

    @Test
    fun `job transaction runs the work with or without trace headers`() {
        assertEquals("done", tracing.inJobTransaction("payment-status-check", traceHeaders, mapOf("sagaId" to "s-1")) { "done" })
        assertEquals("done", tracing.inJobTransaction("payment-status-check", emptyMap(), emptyMap()) { "done" })
    }

    @Test
    fun `failure of the work propagates`() {
        assertFailsWith<IllegalStateException> {
            tracing.inMessageTransaction("billing.commands", "RequestPayment", emptyMap(), emptyMap()) {
                error("provider down")
            }
        }
    }

    @Test
    fun `trace headers become message headers for the agent`() {
        val headers = NewRelicTracing.messageHeaders(traceHeaders)

        assertEquals(HeaderType.MESSAGE, headers.headerType)
        assertEquals(traceHeaders.keys, headers.headerNames.toSet())
        assertEquals("1@nr=0-0-1-2-3", headers.getHeader("tracestate"))
    }
}
