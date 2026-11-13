package com.soat.tech.challenge.oficina.billing.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TraceContextTest {

    private val traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

    @Test
    fun `picks the three trace headers out of the message headers`() {
        val context = TraceContext.fromHeaders(
            mapOf(
                "TraceParent" to traceparent,
                "tracestate" to "1@nr=0-0-1-2-3",
                "newrelic" to "eyJ2IjpbMCwxXX0=",
                "x-correlation-id" to "corr-1",
            ),
        )

        assertEquals(TraceContext(traceparent, "1@nr=0-0-1-2-3", "eyJ2IjpbMCwxXX0="), context)
        assertEquals(
            mapOf("traceparent" to traceparent, "tracestate" to "1@nr=0-0-1-2-3", "newrelic" to "eyJ2IjpbMCwxXX0="),
            context!!.toHeaders(),
        )
    }

    @Test
    fun `blank or oversized values are dropped and no header gives no context`() {
        val oversized = "x".repeat(TraceContext.MAX_VALUE_LENGTH + 1)

        assertEquals(
            TraceContext(traceparent = traceparent),
            TraceContext.fromHeaders(mapOf("traceparent" to traceparent, "tracestate" to " ", "newrelic" to oversized)),
        )
        assertNull(TraceContext.fromHeaders(mapOf("x-correlation-id" to "corr-1")))
        assertNull(TraceContext.fromHeaders(mapOf("traceparent" to "")))
        assertEquals(emptyMap<String, String>(), TraceContext().toHeaders())
    }
}
