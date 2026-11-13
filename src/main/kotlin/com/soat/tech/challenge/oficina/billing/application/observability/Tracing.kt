package com.soat.tech.challenge.oficina.billing.application.observability

/**
 * Port to the APM agent. Each call runs one unit of work as a monitored transaction that, before
 * anything else, accepts the given distributed trace headers: whatever the work publishes then
 * continues that trace. Without an agent the work just runs.
 */
interface Tracing {

    /** A consumed message, as the transaction `Message/<queue>/<type>`. */
    fun <T> inMessageTransaction(
        queue: String,
        type: String,
        traceHeaders: Map<String, String>,
        attributes: Map<String, String>,
        work: () -> T,
    ): T

    /** A unit of work of a scheduled task, as the transaction `Job/<name>`. */
    fun <T> inJobTransaction(
        name: String,
        traceHeaders: Map<String, String>,
        attributes: Map<String, String>,
        work: () -> T,
    ): T
}
