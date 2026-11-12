package com.soat.tech.challenge.oficina.billing.domain.model

/** Result of pricing the requested items against the catalog. */
sealed interface PricingResult {
    data class Priced(val lines: List<QuoteLine>) : PricingResult
    data class Failed(val reason: String) : PricingResult
}

/**
 * Prices requested services and parts with the catalog. Any unknown code, non-positive quantity
 * or empty request fails the whole quote: nothing is priced partially.
 */
object QuotePricing {

    fun price(
        services: List<RequestedItem>,
        parts: List<RequestedItem>,
        catalogServices: Map<String, CatalogService>,
        catalogParts: Map<String, CatalogPart>,
    ): PricingResult {
        if (services.isEmpty() && parts.isEmpty()) {
            return PricingResult.Failed("No services or parts requested")
        }
        val invalidQuantities = (services + parts).filter { it.quantity <= 0 }.map { it.code }
        if (invalidQuantities.isNotEmpty()) {
            return PricingResult.Failed("Quantity must be positive for: ${invalidQuantities.joinToString()}")
        }
        val unknownServices = services.map { it.code }.filterNot(catalogServices::containsKey).distinct()
        val unknownParts = parts.map { it.code }.filterNot(catalogParts::containsKey).distinct()
        if (unknownServices.isNotEmpty() || unknownParts.isNotEmpty()) {
            val details = buildList {
                if (unknownServices.isNotEmpty()) add("services=${unknownServices.joinToString()}")
                if (unknownParts.isNotEmpty()) add("parts=${unknownParts.joinToString()}")
            }
            return PricingResult.Failed("Unknown catalog codes: ${details.joinToString("; ")}")
        }
        val serviceLines = services.map { item ->
            val service = catalogServices.getValue(item.code)
            QuoteLine(LineKind.SERVICE, service.code, service.name, item.quantity, service.priceCents)
        }
        val partLines = parts.map { item ->
            val part = catalogParts.getValue(item.code)
            QuoteLine(LineKind.PART, part.code, part.name, item.quantity, part.priceCents)
        }
        return PricingResult.Priced(serviceLines + partLines)
    }
}
