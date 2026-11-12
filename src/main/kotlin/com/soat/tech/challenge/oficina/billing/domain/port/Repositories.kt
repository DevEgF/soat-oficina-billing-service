package com.soat.tech.challenge.oficina.billing.domain.port

import com.soat.tech.challenge.oficina.billing.domain.model.CatalogPart
import com.soat.tech.challenge.oficina.billing.domain.model.CatalogService
import com.soat.tech.challenge.oficina.billing.domain.model.Payment
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import java.util.UUID

interface CatalogRepository {
    fun listServices(): List<CatalogService>
    fun listParts(): List<CatalogPart>
    fun findServicesByCodes(codes: Collection<String>): List<CatalogService>
    fun findPartsByCodes(codes: Collection<String>): List<CatalogPart>
}

interface QuoteRepository {
    fun findByWorkOrderId(workOrderId: UUID): Quote?
    fun save(quote: Quote): Quote
}

interface PaymentRepository {
    fun findByWorkOrderId(workOrderId: UUID): Payment?
    fun save(payment: Payment): Payment
}
