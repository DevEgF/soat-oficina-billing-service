package com.soat.tech.challenge.oficina.billing.presentation

import com.soat.tech.challenge.oficina.billing.domain.model.CatalogPart
import com.soat.tech.challenge.oficina.billing.domain.model.CatalogService
import com.soat.tech.challenge.oficina.billing.domain.model.LineKind
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteStatus
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CatalogServiceResponse(val code: String, val name: String, val priceCents: Long, val estimatedMinutes: Int)

data class CatalogPartResponse(val code: String, val name: String, val priceCents: Long)

data class QuoteLineResponse(
    val kind: LineKind,
    val code: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val totalCents: Long,
)

data class QuoteResponse(
    val id: UUID,
    val workOrderId: UUID,
    val status: QuoteStatus,
    val lines: List<QuoteLineResponse>,
    val servicesTotalCents: Long,
    val partsTotalCents: Long,
    val totalCents: Long,
    val createdAt: Instant,
    val decidedAt: Instant?,
)

data class QuoteDecisionRequest(
    @field:NotNull
    val approved: Boolean?,
    /** Optional rejection reason, sent in QuoteRejected. */
    @field:Size(max = 500)
    val reason: String? = null,
)

data class PaymentResponse(
    val id: UUID,
    val workOrderId: UUID,
    val quoteId: UUID,
    val amountCents: Long,
    val status: PaymentStatus,
    val provider: String,
    val providerPaymentId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun CatalogService.toResponse() = CatalogServiceResponse(code, name, priceCents, estimatedMinutes)

fun CatalogPart.toResponse() = CatalogPartResponse(code, name, priceCents)

fun Quote.toResponse() = QuoteResponse(
    id = id,
    workOrderId = workOrderId,
    status = status,
    lines = lines.map { QuoteLineResponse(it.kind, it.code, it.name, it.quantity, it.unitPriceCents, it.totalCents) },
    servicesTotalCents = servicesTotalCents,
    partsTotalCents = partsTotalCents,
    totalCents = totalCents,
    createdAt = createdAt,
    decidedAt = decidedAt,
)

fun Payment.toResponse() = PaymentResponse(
    id = id,
    workOrderId = workOrderId,
    quoteId = quoteId,
    amountCents = amountCents,
    status = status,
    provider = provider,
    providerPaymentId = providerPaymentId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
