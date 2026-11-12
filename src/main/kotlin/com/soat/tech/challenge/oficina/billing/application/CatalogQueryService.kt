package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.domain.model.CatalogPart
import com.soat.tech.challenge.oficina.billing.domain.model.CatalogService
import com.soat.tech.challenge.oficina.billing.domain.port.CatalogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogQueryService(private val catalog: CatalogRepository) {

    @Transactional(readOnly = true)
    fun services(): List<CatalogService> = catalog.listServices()

    @Transactional(readOnly = true)
    fun parts(): List<CatalogPart> = catalog.listParts()
}
