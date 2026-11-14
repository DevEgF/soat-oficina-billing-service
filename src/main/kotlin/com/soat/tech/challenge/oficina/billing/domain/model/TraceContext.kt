package com.soat.tech.challenge.oficina.billing.domain.model

/**
 * Distributed trace context of the message that started an operation: the W3C `traceparent` and
 * `tracestate` headers and the New Relic `newrelic` header. The payment keeps the context of its
 * RequestPayment, so the periodic status check, which may resolve the payment minutes later,
 * continues the same trace when it publishes the result.
 */
data class TraceContext(
    val traceparent: String? = null,
    val tracestate: String? = null,
    val newrelic: String? = null,
) {

    /** The headers present, under their transport names. */
    fun toHeaders(): Map<String, String> = buildMap {
        traceparent?.let { put(TRACEPARENT, it) }
        tracestate?.let { put(TRACESTATE, it) }
        newrelic?.let { put(NEWRELIC, it) }
    }

    companion object {
        const val TRACEPARENT = "traceparent"
        const val TRACESTATE = "tracestate"
        const val NEWRELIC = "newrelic"

        /** Longest value kept (size of the payment columns). A longer value is dropped, never cut. */
        const val MAX_VALUE_LENGTH = 1024

        /**
         * Picks the trace headers out of message headers (names compared ignoring case). Returns null
         * when none of them is present with a usable value.
         */
        fun fromHeaders(headers: Map<String, String>): TraceContext? {
            fun value(name: String): String? = headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() && it.length <= MAX_VALUE_LENGTH }

            return TraceContext(value(TRACEPARENT), value(TRACESTATE), value(NEWRELIC))
                .takeIf { it.toHeaders().isNotEmpty() }
        }
    }
}
