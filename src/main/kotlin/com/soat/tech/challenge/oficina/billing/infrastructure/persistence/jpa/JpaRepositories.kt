package com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa

import com.soat.tech.challenge.oficina.billing.domain.model.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CatalogServiceJpaRepository : JpaRepository<CatalogServiceEntity, String> {
    fun findAllByOrderByNameAsc(): List<CatalogServiceEntity>
}

interface CatalogPartJpaRepository : JpaRepository<CatalogPartEntity, String> {
    fun findAllByOrderByNameAsc(): List<CatalogPartEntity>
}

interface QuoteJpaRepository : JpaRepository<QuoteEntity, UUID> {
    fun findByWorkOrderId(workOrderId: UUID): QuoteEntity?
}

interface PaymentJpaRepository : JpaRepository<PaymentEntity, UUID> {
    fun findByWorkOrderId(workOrderId: UUID): PaymentEntity?
    fun findAllByStatusOrderByCreatedAtAsc(status: PaymentStatus): List<PaymentEntity>
}

interface ProcessedMessageJpaRepository : JpaRepository<ProcessedMessageEntity, UUID>
