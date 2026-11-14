package com.soat.tech.challenge.oficina.billing.infrastructure.observability

import com.newrelic.api.agent.ConcurrentHashMapHeaders
import com.newrelic.api.agent.HeaderType
import com.newrelic.api.agent.Headers
import com.newrelic.api.agent.NewRelic
import com.newrelic.api.agent.Trace
import com.newrelic.api.agent.TransportType
import com.soat.tech.challenge.oficina.billing.application.observability.Tracing
import org.springframework.stereotype.Component

/**
 * [Tracing] on the New Relic Java agent. `@Trace(dispatcher = true)` starts a transaction when none
 * is running (a message consumer or a scheduled task are not transactions for the agent); messages
 * published inside it get the `traceparent`, `tracestate` and `newrelic` headers from the agent.
 * Without the agent (tests, local runs) the API is a no-op and the work just runs.
 */
@Component
class NewRelicTracing : Tracing {

    @Trace(dispatcher = true)
    override fun <T> inMessageTransaction(
        queue: String,
        type: String,
        traceHeaders: Map<String, String>,
        attributes: Map<String, String>,
        work: () -> T,
    ): T {
        begin(MESSAGE_CATEGORY, "$queue/$type", traceHeaders, attributes)
        return work()
    }

    @Trace(dispatcher = true)
    override fun <T> inJobTransaction(
        name: String,
        traceHeaders: Map<String, String>,
        attributes: Map<String, String>,
        work: () -> T,
    ): T {
        begin(JOB_CATEGORY, name, traceHeaders, attributes)
        return work()
    }

    private fun begin(
        category: String,
        name: String,
        traceHeaders: Map<String, String>,
        attributes: Map<String, String>,
    ) {
        // First thing in the transaction: the agent ignores inbound headers once the transaction
        // has created its own outbound trace context (an HTTP call or a publish).
        if (traceHeaders.isNotEmpty()) {
            NewRelic.getAgent().transaction.acceptDistributedTraceHeaders(TransportType.AMQP, messageHeaders(traceHeaders))
        }
        NewRelic.setTransactionName(category, name)
        attributes.forEach { (key, value) -> NewRelic.addCustomParameter(key, value) }
    }

    companion object {
        const val MESSAGE_CATEGORY = "Message"
        const val JOB_CATEGORY = "Job"

        internal fun messageHeaders(values: Map<String, String>): Headers =
            ConcurrentHashMapHeaders.build(HeaderType.MESSAGE).apply {
                values.forEach { (name, value) -> addHeader(name, value) }
            }
    }
}
