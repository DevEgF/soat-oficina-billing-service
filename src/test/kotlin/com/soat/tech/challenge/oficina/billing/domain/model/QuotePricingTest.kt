package com.soat.tech.challenge.oficina.billing.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

class QuotePricingTest {

    private val services = listOf(
        CatalogService("TROCA_OLEO", "Troca de óleo", 12000, 30),
        CatalogService("REVISAO_FREIOS", "Revisão de freios", 25000, 90),
    ).associateBy { it.code }
    private val parts = listOf(
        CatalogPart("FILTRO_OLEO", "Filtro de óleo", 4500),
        CatalogPart("PASTILHA_FREIO", "Jogo de pastilhas de freio", 16000),
    ).associateBy { it.code }

    @Test
    fun `prices services first and parts after with catalog name and price`() {
        val result = QuotePricing.price(
            listOf(RequestedItem("TROCA_OLEO", 1)),
            listOf(RequestedItem("FILTRO_OLEO", 2), RequestedItem("PASTILHA_FREIO", 1)),
            services,
            parts,
        )

        val lines = assertIs<PricingResult.Priced>(result).lines
        assertEquals(
            listOf(
                QuoteLine(LineKind.SERVICE, "TROCA_OLEO", "Troca de óleo", 1, 12000),
                QuoteLine(LineKind.PART, "FILTRO_OLEO", "Filtro de óleo", 2, 4500),
                QuoteLine(LineKind.PART, "PASTILHA_FREIO", "Jogo de pastilhas de freio", 1, 16000),
            ),
            lines,
        )
    }

    @Test
    fun `parts only is a valid request`() {
        assertIs<PricingResult.Priced>(QuotePricing.price(emptyList(), listOf(RequestedItem("FILTRO_OLEO", 1)), services, parts))
    }

    @Test
    fun `unknown codes fail listing services and parts`() {
        val result = QuotePricing.price(
            listOf(RequestedItem("NAO_EXISTE", 1), RequestedItem("NAO_EXISTE", 2)),
            listOf(RequestedItem("PECA_X", 1)),
            services,
            parts,
        )

        assertEquals(
            "Unknown catalog codes: services=NAO_EXISTE; parts=PECA_X",
            assertIs<PricingResult.Failed>(result).reason,
        )
    }

    @Test
    fun `a part code is not accepted as a service`() {
        val result = QuotePricing.price(listOf(RequestedItem("FILTRO_OLEO", 1)), emptyList(), services, parts)
        assertEquals("Unknown catalog codes: services=FILTRO_OLEO", assertIs<PricingResult.Failed>(result).reason)
    }

    @Test
    fun `empty request and non positive quantity fail`() {
        assertEquals(
            "No services or parts requested",
            assertIs<PricingResult.Failed>(QuotePricing.price(emptyList(), emptyList(), services, parts)).reason,
        )
        assertEquals(
            "Quantity must be positive for: TROCA_OLEO",
            assertIs<PricingResult.Failed>(
                QuotePricing.price(listOf(RequestedItem("TROCA_OLEO", 0)), emptyList(), services, parts),
            ).reason,
        )
    }
}
