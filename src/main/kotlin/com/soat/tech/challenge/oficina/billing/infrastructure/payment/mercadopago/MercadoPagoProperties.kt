package com.soat.tech.challenge.oficina.billing.infrastructure.payment.mercadopago

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Mercado Pago settings (app.mercadopago.*). Credentials and the test card come from the Secret;
 * they are required only when app.payment.gateway=mercadopago. toString never shows them.
 */
@ConfigurationProperties("app.mercadopago")
class MercadoPagoProperties(
    val baseUrl: String = "https://api.mercadopago.com",
    val accessToken: String = "",
    val publicKey: String = "",
    /** Test user e-mail of the payer; when blank the customer e-mail is used. */
    val payerEmail: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(20),
    val testCard: TestCard = TestCard(),
) {

    /** Card of the provider test environment, tokenized on every charge. */
    class TestCard(
        val number: String = "",
        val securityCode: String = "",
        val expirationMonth: String = "",
        val expirationYear: String = "",
        val holderDocument: String = "",
    ) {
        override fun toString() = "TestCard(***)"
    }

    /** Environment variables that are missing or invalid, by name only. */
    fun missingSettings(): List<String> = buildList {
        if (baseUrl.isBlank()) add("MERCADOPAGO_BASE_URL")
        if (accessToken.isBlank()) add("MERCADOPAGO_ACCESS_TOKEN")
        if (publicKey.isBlank()) add("MERCADOPAGO_PUBLIC_KEY")
        if (testCard.number.isBlank()) add("MERCADOPAGO_TEST_CARD_NUMBER")
        if (testCard.securityCode.isBlank()) add("MERCADOPAGO_TEST_CARD_SECURITY_CODE")
        if (testCard.expirationMonth.trim().toIntOrNull() !in 1..12) add("MERCADOPAGO_TEST_CARD_EXPIRATION_MONTH")
        if (testCard.expirationYear.trim().toIntOrNull() == null) add("MERCADOPAGO_TEST_CARD_EXPIRATION_YEAR")
        if (testCard.holderDocument.isBlank()) add("MERCADOPAGO_TEST_CARD_HOLDER_DOCUMENT")
    }

    override fun toString() =
        "MercadoPagoProperties(baseUrl=$baseUrl, payerEmail=$payerEmail, connectTimeout=$connectTimeout, " +
            "readTimeout=$readTimeout, credentials=***)"
}
