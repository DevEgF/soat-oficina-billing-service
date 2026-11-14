package com.soat.tech.challenge.oficina.billing.support

import com.soat.tech.challenge.oficina.billing.application.observability.Tracing

/** Tracing that runs the work and records what each transaction received. */
class RecordingTracing : Tracing {

    data class Recorded(
        val category: String,
        val name: String,
        val traceHeaders: Map<String, String>,
        val attributes: Map<String, String>,
    )

    val transactions = mutableListOf<Recorded>()

    override fun <T> inMessageTransaction(
        queue: String,
        type: String,
        traceHeaders: Map<String, String>,
        attributes: Map<String, String>,
        work: () -> T,
    ): T {
        transactions += Recorded("Message", "$queue/$type", traceHeaders, attributes)
        return work()
    }

    override fun <T> inJobTransaction(
        name: String,
        traceHeaders: Map<String, String>,
        attributes: Map<String, String>,
        work: () -> T,
    ): T {
        transactions += Recorded("Job", name, traceHeaders, attributes)
        return work()
    }
}
