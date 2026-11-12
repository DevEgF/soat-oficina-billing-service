package com.soat.tech.challenge.oficina.billing.domain.model

import com.soat.tech.challenge.oficina.billing.domain.exception.InvalidStatusTransitionException
import java.time.Instant
import java.util.UUID

enum class QuoteStatus { PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED }

enum class LineKind { SERVICE, PART }

/** Quote line with the catalog name and unit price frozen at quote time. */
data class QuoteLine(
    val kind: LineKind,
    val code: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Long,
) {
    init {
        require(quantity > 0) { "Quantity must be positive" }
        require(unitPriceCents >= 0) { "Price cannot be negative" }
    }

    val totalCents: Long get() = quantity.toLong() * unitPriceCents
}

/**
 * Quote of one work order (aggregate). Totals follow WorkOrder.recalculateQuote of the monolith:
 * sum of quantity x unit price per line kind. Status changes only through the methods below.
 */
class Quote(
    val id: UUID,
    val workOrderId: UUID,
    val trackingCode: String?,
    val customerName: String,
    val customerEmail: String?,
    /** Saga correlation id received with GenerateQuote; reused in every event about this quote. */
    val correlationId: String,
    status: QuoteStatus,
    val lines: List<QuoteLine>,
    val createdAt: Instant,
    decidedAt: Instant? = null,
    decisionReason: String? = null,
    updatedAt: Instant = createdAt,
) {
    var status: QuoteStatus = status
        private set
    var decidedAt: Instant? = decidedAt
        private set
    var decisionReason: String? = decisionReason
        private set
    var updatedAt: Instant = updatedAt
        private set

    var servicesTotalCents: Long = 0
        private set
    var partsTotalCents: Long = 0
        private set
    var totalCents: Long = 0
        private set

    init {
        require(lines.isNotEmpty()) { "A quote needs at least one line" }
        recalculateQuote()
    }

    private fun recalculateQuote() {
        servicesTotalCents = lines.filter { it.kind == LineKind.SERVICE }.sumOf { it.quantity.toLong() * it.unitPriceCents }
        partsTotalCents = lines.filter { it.kind == LineKind.PART }.sumOf { it.quantity.toLong() * it.unitPriceCents }
        totalCents = servicesTotalCents + partsTotalCents
    }

    private fun requireStatus(action: String, vararg expected: QuoteStatus) {
        if (status !in expected) {
            throw InvalidStatusTransitionException(status.name, action)
        }
    }

    /** Customer approves the quote. */
    fun approve(now: Instant) {
        requireStatus("approve", QuoteStatus.PENDING_APPROVAL)
        status = QuoteStatus.APPROVED
        decidedAt = now
        updatedAt = now
    }

    /** Customer rejects the quote. */
    fun reject(now: Instant, reason: String?) {
        requireStatus("reject", QuoteStatus.PENDING_APPROVAL)
        status = QuoteStatus.REJECTED
        decidedAt = now
        decisionReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        updatedAt = now
    }

    /** Saga compensation (CancelQuote): an open or approved quote is cancelled. */
    fun cancel(now: Instant, reason: String?) {
        requireStatus("cancel", QuoteStatus.PENDING_APPROVAL, QuoteStatus.APPROVED)
        status = QuoteStatus.CANCELLED
        decisionReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        updatedAt = now
    }

    companion object {
        fun create(
            workOrderId: UUID,
            trackingCode: String?,
            customerName: String,
            customerEmail: String?,
            correlationId: String,
            lines: List<QuoteLine>,
            now: Instant,
            id: UUID = UUID.randomUUID(),
        ) = Quote(
            id = id,
            workOrderId = workOrderId,
            trackingCode = trackingCode,
            customerName = customerName,
            customerEmail = customerEmail,
            correlationId = correlationId,
            status = QuoteStatus.PENDING_APPROVAL,
            lines = lines,
            createdAt = now,
        )
    }
}
