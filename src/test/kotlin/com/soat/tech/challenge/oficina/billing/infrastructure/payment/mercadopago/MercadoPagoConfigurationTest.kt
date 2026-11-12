package com.soat.tech.challenge.oficina.billing.infrastructure.payment.mercadopago

import com.soat.tech.challenge.oficina.billing.infrastructure.payment.PaymentConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MercadoPagoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(PaymentConfiguration::class.java, MercadoPagoConfiguration::class.java)

    private val complete = arrayOf(
        "app.payment.gateway=mercadopago",
        "app.mercadopago.access-token=fake-access-token",
        "app.mercadopago.public-key=fake-public-key",
        "app.mercadopago.test-card.number=0000000000000000",
        "app.mercadopago.test-card.security-code=000",
        "app.mercadopago.test-card.expiration-month=1",
        "app.mercadopago.test-card.expiration-year=2099",
        "app.mercadopago.test-card.holder-document=00000000000",
    )

    @Test
    fun `selected without credentials the service does not start and names what is missing`() {
        runner.withPropertyValues("app.payment.gateway=mercadopago", "app.mercadopago.test-card.expiration-month=13")
            .run { context ->
                assertTrue(context.startupFailure != null)
                val message = generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                listOf(
                    "MERCADOPAGO_ACCESS_TOKEN",
                    "MERCADOPAGO_PUBLIC_KEY",
                    "MERCADOPAGO_TEST_CARD_NUMBER",
                    "MERCADOPAGO_TEST_CARD_SECURITY_CODE",
                    "MERCADOPAGO_TEST_CARD_EXPIRATION_MONTH",
                    "MERCADOPAGO_TEST_CARD_EXPIRATION_YEAR",
                    "MERCADOPAGO_TEST_CARD_HOLDER_DOCUMENT",
                ).forEach { assertTrue(message.contains(it), "missing $it in: $message") }
                assertFalse(message.contains("MERCADOPAGO_BASE_URL"))
            }
    }

    @Test
    fun `selected with every setting the adapter is created`() {
        runner.withPropertyValues(*complete).run { context ->
            assertEquals(null, context.startupFailure)
            assertEquals("MERCADO_PAGO", context.getBean(MercadoPagoPaymentGateway::class.java).provider)
            val properties = context.getBean(MercadoPagoProperties::class.java)
            assertEquals("https://api.mercadopago.com", properties.baseUrl)
            assertEquals(5, properties.connectTimeout.seconds)
        }
    }

    @Test
    fun `simulated gateway needs no Mercado Pago setting`() {
        runner.withPropertyValues("app.payment.gateway=simulated").run { context ->
            assertEquals(null, context.startupFailure)
            assertTrue(context.getBeansOfType(MercadoPagoPaymentGateway::class.java).isEmpty())
        }
    }
}
