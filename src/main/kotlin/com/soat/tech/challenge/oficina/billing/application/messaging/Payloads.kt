package com.soat.tech.challenge.oficina.billing.application.messaging

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

// Payloads of the saga contract. Money in cents, ids as UUID strings,
// unknown fields ignored on read. Each service owns its copy: there is no shared library.

// ---------- Commands consumed ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class GenerateQuotePayload(
    val workOrderId: UUID,
    val trackingCode: String? = null,
    val customer: CustomerPayload,
    val services: List<ItemPayload> = emptyList(),
    val parts: List<ItemPayload> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomerPayload(val name: String, val email: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ItemPayload(val code: String, val quantity: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RequestPaymentPayload(val workOrderId: UUID, val quoteId: UUID, val amountCents: Long)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CancelQuotePayload(val workOrderId: UUID, val quoteId: UUID? = null, val reason: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RefundPaymentPayload(val workOrderId: UUID, val paymentId: UUID? = null, val reason: String? = null)

// ---------- Events published ----------

data class QuoteGeneratedPayload(
    val workOrderId: UUID,
    val quoteId: UUID,
    val servicesTotalCents: Long,
    val partsTotalCents: Long,
    val totalCents: Long,
)

data class QuoteGenerationFailedPayload(val workOrderId: UUID, val reason: String)

data class QuoteApprovedPayload(val workOrderId: UUID, val quoteId: UUID, val totalCents: Long)

data class QuoteRejectedPayload(val workOrderId: UUID, val quoteId: UUID, val reason: String?)

data class QuoteCancelledPayload(val workOrderId: UUID, val quoteId: UUID)

data class PaymentApprovedPayload(
    val workOrderId: UUID,
    val quoteId: UUID,
    val paymentId: UUID,
    val amountCents: Long,
    val provider: String,
    val providerPaymentId: String?,
)

data class PaymentRejectedPayload(
    val workOrderId: UUID,
    val quoteId: UUID,
    val paymentId: UUID,
    val reason: String,
)

data class PaymentRefundedPayload(val workOrderId: UUID, val paymentId: UUID)
