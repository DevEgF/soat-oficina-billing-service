package com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa

import com.soat.tech.challenge.oficina.billing.domain.model.LineKind
import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteStatus
import com.soat.tech.challenge.oficina.billing.domain.model.TraceContext
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

// Defaults on every property give Hibernate the no-arg constructor it needs.

@Entity
@Table(name = "catalog_service")
class CatalogServiceEntity(
    @Id
    @Column(length = 64)
    var code: String = "",
    @Column(nullable = false)
    var name: String = "",
    @Column(name = "price_cents", nullable = false)
    var priceCents: Long = 0,
    @Column(name = "estimated_minutes", nullable = false)
    var estimatedMinutes: Int = 0,
)

@Entity
@Table(name = "catalog_part")
class CatalogPartEntity(
    @Id
    @Column(length = 64)
    var code: String = "",
    @Column(nullable = false)
    var name: String = "",
    @Column(name = "price_cents", nullable = false)
    var priceCents: Long = 0,
)

@Embeddable
class QuoteLineEmbeddable(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: LineKind = LineKind.SERVICE,
    @Column(nullable = false, length = 64)
    var code: String = "",
    @Column(nullable = false)
    var name: String = "",
    @Column(nullable = false)
    var quantity: Int = 0,
    @Column(name = "unit_price_cents", nullable = false)
    var unitPriceCents: Long = 0,
    @Column(name = "total_cents", nullable = false)
    var totalCents: Long = 0,
)

@Entity
@Table(name = "quote")
class QuoteEntity(
    @Id
    var id: UUID = UUID(0, 0),
    @Column(name = "work_order_id", nullable = false, unique = true)
    var workOrderId: UUID = UUID(0, 0),
    @Column(name = "tracking_code", length = 64)
    var trackingCode: String? = null,
    @Column(name = "customer_name", nullable = false)
    var customerName: String = "",
    @Column(name = "customer_email")
    var customerEmail: String? = null,
    @Column(name = "correlation_id", nullable = false, length = 128)
    var correlationId: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: QuoteStatus = QuoteStatus.PENDING_APPROVAL,
    @Column(name = "services_total_cents", nullable = false)
    var servicesTotalCents: Long = 0,
    @Column(name = "parts_total_cents", nullable = false)
    var partsTotalCents: Long = 0,
    @Column(name = "total_cents", nullable = false)
    var totalCents: Long = 0,
    @Column(name = "decision_reason", length = 500)
    var decisionReason: String? = null,
    @Column(name = "cardholder_name", length = 100)
    var cardholderName: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "quote_line", joinColumns = [JoinColumn(name = "quote_id")])
    @OrderColumn(name = "line_number")
    var lines: MutableList<QuoteLineEmbeddable> = mutableListOf(),
    // Optimistic lock: two concurrent decisions on the same quote cannot both win.
    @Version
    var version: Long? = null,
)

@Entity
@Table(name = "payment")
class PaymentEntity(
    @Id
    var id: UUID = UUID(0, 0),
    @Column(name = "work_order_id", nullable = false, unique = true)
    var workOrderId: UUID = UUID(0, 0),
    @Column(name = "quote_id", nullable = false)
    var quoteId: UUID = UUID(0, 0),
    @Column(name = "amount_cents", nullable = false)
    var amountCents: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: PaymentStatus = PaymentStatus.PENDING,
    @Column(nullable = false, length = 32)
    var provider: String = "",
    @Column(name = "provider_payment_id", length = 128)
    var providerPaymentId: String? = null,
    @Column(name = "status_detail")
    var statusDetail: String? = null,
    @Column(name = "correlation_id", nullable = false, length = 128)
    var correlationId: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
    // Trace headers (traceparent, tracestate, newrelic) of the RequestPayment message.
    @Column(name = "trace_parent", length = TraceContext.MAX_VALUE_LENGTH)
    var traceParent: String? = null,
    @Column(name = "trace_state", length = TraceContext.MAX_VALUE_LENGTH)
    var traceState: String? = null,
    @Column(name = "trace_newrelic", length = TraceContext.MAX_VALUE_LENGTH)
    var traceNewrelic: String? = null,
    @Version
    var version: Long? = null,
)

/** Always inserted (never merged): a repeated messageId fails on the primary key. */
@Entity
@Table(name = "processed_message")
class ProcessedMessageEntity(
    @Id
    @Column(name = "message_id")
    var messageId: UUID = UUID(0, 0),
    @Column(nullable = false, length = 64)
    var type: String = "",
    @Column(name = "processed_at", nullable = false)
    var processedAt: Instant = Instant.EPOCH,
) : Persistable<UUID> {
    override fun getId(): UUID = messageId

    override fun isNew(): Boolean = true
}
