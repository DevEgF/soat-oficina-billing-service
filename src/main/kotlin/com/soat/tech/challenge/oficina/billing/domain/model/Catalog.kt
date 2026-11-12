package com.soat.tech.challenge.oficina.billing.domain.model

/** Service offered by the shop, with price and estimated execution time. */
data class CatalogService(
    val code: String,
    val name: String,
    val priceCents: Long,
    val estimatedMinutes: Int,
)

/** Part or supply sold with the services. Stock control is out of scope of this service. */
data class CatalogPart(
    val code: String,
    val name: String,
    val priceCents: Long,
)

/** Item requested by the work order: catalog code and quantity, no price. */
data class RequestedItem(
    val code: String,
    val quantity: Int,
)
