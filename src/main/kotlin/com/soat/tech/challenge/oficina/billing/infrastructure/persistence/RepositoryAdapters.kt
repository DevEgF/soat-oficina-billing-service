package com.soat.tech.challenge.oficina.billing.infrastructure.persistence

import com.soat.tech.challenge.oficina.billing.domain.model.CatalogPart
import com.soat.tech.challenge.oficina.billing.domain.model.CatalogService
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import com.soat.tech.challenge.oficina.billing.domain.model.QuoteLine
import com.soat.tech.challenge.oficina.billing.domain.port.CatalogRepository
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentRepository
import com.soat.tech.challenge.oficina.billing.domain.port.QuoteRepository
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.CatalogPartEntity
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.CatalogPartJpaRepository
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.CatalogServiceEntity
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.CatalogServiceJpaRepository
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.PaymentEntity
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.PaymentJpaRepository
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.QuoteEntity
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.QuoteJpaRepository
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.QuoteLineEmbeddable
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class CatalogRepositoryAdapter(
    private val services: CatalogServiceJpaRepository,
    private val parts: CatalogPartJpaRepository,
) : CatalogRepository {

    override fun listServices(): List<CatalogService> = services.findAllByOrderByNameAsc().map { it.toDomain() }

    override fun listParts(): List<CatalogPart> = parts.findAllByOrderByNameAsc().map { it.toDomain() }

    override fun findServicesByCodes(codes: Collection<String>): List<CatalogService> =
        if (codes.isEmpty()) emptyList() else services.findAllById(codes).map { it.toDomain() }

    override fun findPartsByCodes(codes: Collection<String>): List<CatalogPart> =
        if (codes.isEmpty()) emptyList() else parts.findAllById(codes).map { it.toDomain() }

    private fun CatalogServiceEntity.toDomain() = CatalogService(code, name, priceCents, estimatedMinutes)

    private fun CatalogPartEntity.toDomain() = CatalogPart(code, name, priceCents)
}

@Repository
class QuoteRepositoryAdapter(private val jpa: QuoteJpaRepository) : QuoteRepository {

    override fun findByWorkOrderId(workOrderId: UUID): Quote? = jpa.findByWorkOrderId(workOrderId)?.toDomain()

    override fun save(quote: Quote): Quote {
        // Loaded entity keeps its version, so the optimistic lock is checked on update.
        val entity = jpa.findById(quote.id).orElseGet { QuoteEntity(id = quote.id) }
        entity.workOrderId = quote.workOrderId
        entity.trackingCode = quote.trackingCode
        entity.customerName = quote.customerName
        entity.customerEmail = quote.customerEmail
        entity.correlationId = quote.correlationId
        entity.status = quote.status
        entity.servicesTotalCents = quote.servicesTotalCents
        entity.partsTotalCents = quote.partsTotalCents
        entity.totalCents = quote.totalCents
        entity.decisionReason = quote.decisionReason
        entity.createdAt = quote.createdAt
        entity.decidedAt = quote.decidedAt
        entity.updatedAt = quote.updatedAt
        if (entity.lines.isEmpty()) {
            // Lines are immutable after creation.
            entity.lines.addAll(quote.lines.map { it.toEmbeddable() })
        }
        return jpa.saveAndFlush(entity).toDomain()
    }

    private fun QuoteLine.toEmbeddable() = QuoteLineEmbeddable(kind, code, name, quantity, unitPriceCents, totalCents)

    private fun QuoteEntity.toDomain() = Quote(
        id = id,
        workOrderId = workOrderId,
        trackingCode = trackingCode,
        customerName = customerName,
        customerEmail = customerEmail,
        correlationId = correlationId,
        status = status,
        lines = lines.map { QuoteLine(it.kind, it.code, it.name, it.quantity, it.unitPriceCents) },
        createdAt = createdAt,
        decidedAt = decidedAt,
        decisionReason = decisionReason,
        updatedAt = updatedAt,
    )
}

@Repository
class PaymentRepositoryAdapter(private val jpa: PaymentJpaRepository) : PaymentRepository {

    override fun findByWorkOrderId(workOrderId: UUID): Payment? = jpa.findByWorkOrderId(workOrderId)?.toDomain()

    override fun save(payment: Payment): Payment {
        val entity = jpa.findById(payment.id).orElseGet { PaymentEntity(id = payment.id) }
        entity.workOrderId = payment.workOrderId
        entity.quoteId = payment.quoteId
        entity.amountCents = payment.amountCents
        entity.status = payment.status
        entity.provider = payment.provider
        entity.providerPaymentId = payment.providerPaymentId
        entity.statusDetail = payment.statusDetail
        entity.correlationId = payment.correlationId
        entity.createdAt = payment.createdAt
        entity.updatedAt = payment.updatedAt
        return jpa.saveAndFlush(entity).toDomain()
    }

    private fun PaymentEntity.toDomain() = Payment(
        id = id,
        workOrderId = workOrderId,
        quoteId = quoteId,
        amountCents = amountCents,
        provider = provider,
        correlationId = correlationId,
        status = status,
        providerPaymentId = providerPaymentId,
        statusDetail = statusDetail,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
