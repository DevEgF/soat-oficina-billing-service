package com.soat.tech.challenge.oficina.billing.infrastructure.payment.mercadopago

import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentCreation
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentProviderException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.io.IOException
import java.util.UUID
import kotlin.test.assertFailsWith

/** Adapter against a fake HTTP server; card data and credentials are obviously fictitious. */
class MercadoPagoPaymentGatewayTest {

    private val workOrderId = UUID.randomUUID()
    private val quoteId = UUID.randomUUID()

    private fun properties(payerEmail: String = "payer@testuser.com") = MercadoPagoProperties(
        baseUrl = BASE_URL,
        accessToken = ACCESS_TOKEN,
        publicKey = PUBLIC_KEY,
        payerEmail = payerEmail,
        testCard = MercadoPagoProperties.TestCard(
            number = CARD_NUMBER,
            securityCode = "000",
            expirationMonth = "1",
            expirationYear = "2099",
            holderDocument = "00000000000",
        ),
    )

    private val builder = RestClient.builder().baseUrl(BASE_URL)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private fun gateway(properties: MercadoPagoProperties = properties()) = MercadoPagoPaymentGateway(builder.build(), properties)

    private fun creation(payerEmail: String? = "maria@example.com") = PaymentCreation(
        paymentId = UUID.randomUUID(),
        workOrderId = workOrderId,
        quoteId = quoteId,
        amountCents = 21000,
        description = "Oficina - OS TRK",
        payerEmail = payerEmail,
        cardholderName = "APRO",
        idempotencyKey = "quote-$quoteId",
    )

    private fun json(status: HttpStatus, body: String) =
        withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body)

    private fun expectCardToken(holder: String = "APRO") {
        server.expect(requestTo("$BASE_URL/v1/card_tokens?public_key=$PUBLIC_KEY"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(queryParam("public_key", PUBLIC_KEY))
            .andExpect(headerDoesNotExist("Authorization"))
            .andExpect(jsonPath("$.card_number").value(CARD_NUMBER))
            .andExpect(jsonPath("$.security_code").value("000"))
            .andExpect(jsonPath("$.expiration_month").value(1))
            .andExpect(jsonPath("$.expiration_year").value(2099))
            .andExpect(jsonPath("$.cardholder.name").value(holder))
            .andExpect(jsonPath("$.cardholder.identification.type").value("CPF"))
            .andExpect(jsonPath("$.cardholder.identification.number").value("00000000000"))
            .andRespond(json(HttpStatus.CREATED, """{"id": "$CARD_TOKEN"}"""))
    }

    @Test
    fun `create tokenizes the test card and creates an order that stays pending`() {
        expectCardToken()
        server.expect(requestTo("$BASE_URL/v1/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer $ACCESS_TOKEN"))
            .andExpect(header("X-Idempotency-Key", "quote-$quoteId"))
            .andExpect(jsonPath("$.type").value("online"))
            .andExpect(jsonPath("$.processing_mode").value("automatic"))
            .andExpect(jsonPath("$.total_amount").value("210.00"))
            .andExpect(jsonPath("$.external_reference").value(workOrderId.toString()))
            .andExpect(jsonPath("$.payer.email").value("payer@testuser.com"))
            .andExpect(jsonPath("$.transactions.payments[0].amount").value("210.00"))
            .andExpect(jsonPath("$.transactions.payments[0].payment_method.id").value("master"))
            .andExpect(jsonPath("$.transactions.payments[0].payment_method.type").value("credit_card"))
            .andExpect(jsonPath("$.transactions.payments[0].payment_method.token").value(CARD_TOKEN))
            .andExpect(jsonPath("$.transactions.payments[0].payment_method.installments").value(1))
            .andRespond(
                json(
                    HttpStatus.CREATED,
                    """{"id": "ORDTST01", "status": "processing", "status_detail": "in_process",
                       "transactions": {"payments": [{"id": "PAY01", "status": "processing", "status_detail": "in_process"}]}}""",
                ),
            )

        val result = gateway().create(creation())

        assertEquals(GatewayPayment("ORDTST01", GatewayPaymentStatus.PENDING, "in_process"), result)
        assertEquals("MERCADO_PAGO", gateway().provider)
        server.verify()
    }

    @Test
    fun `order already resolved at creation is still reported pending for the status check`() {
        expectCardToken()
        server.expect(requestTo("$BASE_URL/v1/orders"))
            .andRespond(
                json(
                    HttpStatus.CREATED,
                    """{"id": "ORDTST03", "status": "processed", "status_detail": "accredited",
                       "transactions": {"payments": [{"status": "processed", "status_detail": "accredited"}]}}""",
                ),
            )

        assertEquals(GatewayPayment("ORDTST03", GatewayPaymentStatus.PENDING, "accredited"), gateway().create(creation()))
    }

    @Test
    fun `payment refused at creation answers 402 with the order under data, which is recorded`() {
        expectCardToken("OTHE")
        server.expect(requestTo("$BASE_URL/v1/orders"))
            .andRespond(
                json(
                    HttpStatus.PAYMENT_REQUIRED,
                    """{"errors": [{"code": "failed", "message": "The following transactions failed", "details": ["PAYTST04: rejected_by_issuer"]}],
                       "data": {"id": "ORDTST04", "status": "failed", "status_detail": "failed",
                         "transactions": {"payments": [{"status": "failed", "status_detail": "rejected_by_issuer"}]}}}""",
                ),
            )

        val result = gateway().create(creation().copy(cardholderName = "OTHE"))

        assertEquals(GatewayPayment("ORDTST04", GatewayPaymentStatus.PENDING, "rejected_by_issuer"), result)
        server.verify()
    }

    @Test
    fun `error answer that carries the order at the top level is recorded too`() {
        expectCardToken("OTHE")
        server.expect(requestTo("$BASE_URL/v1/orders"))
            .andRespond(
                json(
                    HttpStatus.PAYMENT_REQUIRED,
                    """{"id": "ORDTST05", "status": "failed", "status_detail": "failed",
                       "transactions": {"payments": [{"status": "failed", "status_detail": "rejected_by_issuer"}]}}""",
                ),
            )

        val result = gateway().create(creation().copy(cardholderName = "OTHE"))

        assertEquals(GatewayPayment("ORDTST05", GatewayPaymentStatus.PENDING, "rejected_by_issuer"), result)
        server.verify()
    }

    @Test
    fun `error answer without an order stays an error`() {
        expectCardToken()
        server.expect(requestTo("$BASE_URL/v1/orders"))
            .andRespond(json(HttpStatus.PAYMENT_REQUIRED, """{"errors": [{"code": "failed"}], "data": {"status": "failed"}}"""))

        assertFailsWith<PaymentProviderException> { gateway().create(creation()) }
    }

    @Test
    fun `payer is the customer when no test payer is configured`() {
        expectCardToken()
        server.expect(requestTo("$BASE_URL/v1/orders"))
            .andExpect(jsonPath("$.payer.email").value("maria@example.com"))
            .andRespond(json(HttpStatus.CREATED, """{"id": "ORDTST02", "status": "processing"}"""))

        assertEquals(GatewayPaymentStatus.PENDING, gateway(properties(payerEmail = "")).create(creation()).status)
        server.verify()
    }

    @Test
    fun `without any payer e-mail nothing is sent`() {
        server.expect(ExpectedCount.never(), requestTo("$BASE_URL/v1/card_tokens?public_key=$PUBLIC_KEY"))

        assertFailsWith<PaymentProviderException> { gateway(properties(payerEmail = " ")).create(creation(payerEmail = null)) }
        server.verify()
    }

    @Test
    fun `repeated idempotency key with another token is an error, never a success`() {
        expectCardToken()
        server.expect(requestTo("$BASE_URL/v1/orders"))
            .andRespond(
                json(
                    HttpStatus.CONFLICT,
                    """{"errors": [{"code": "idempotency_key_already_used", "message": "The idempotency key was already used", "details": []}]}""",
                ),
            )

        val failure = assertFailsWith<PaymentProviderException> { gateway().create(creation()) }

        assertEquals("create-order: HTTP 409 idempotency_key_already_used", failure.message)
        assertNoSecrets(failure)
    }

    @Test
    fun `card token refused stops before the order`() {
        server.expect(requestTo("$BASE_URL/v1/card_tokens?public_key=$PUBLIC_KEY"))
            .andRespond(
                json(
                    HttpStatus.BAD_REQUEST,
                    """{"message": "invalid parameters", "error": "bad_request", "status": 400, "cause": [{"code": "E301", "description": "invalid card number"}]}""",
                ),
            )

        val failure = assertFailsWith<PaymentProviderException> { gateway().create(creation()) }

        assertEquals("card-token: HTTP 400 E301", failure.message)
        server.verify()
    }

    @Test
    fun `network failure is reported without the URL`() {
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01"))
            .andRespond { throw IOException("Connection refused: $BASE_URL/v1/card_tokens?public_key=$PUBLIC_KEY") }

        val failure = assertFailsWith<PaymentProviderException> { gateway().getStatus("ORDTST01") }

        assertEquals("get-order: ResourceAccessException (IOException)", failure.message)
        assertNoSecrets(failure)
    }

    @Test
    fun `server error without JSON body is an error`() {
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>bad gateway</html>"))

        val failure = assertFailsWith<PaymentProviderException> { gateway().getStatus("ORDTST01") }

        assertEquals("get-order: HTTP 502", failure.message)
    }

    @Test
    fun `answer without order id is an error`() {
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01"))
            .andRespond(json(HttpStatus.OK, """{"status": "processed"}"""))

        assertFailsWith<PaymentProviderException> { gateway().getStatus("ORDTST01") }
    }

    @ParameterizedTest(name = "order {0} with payment detail {1} is {2}")
    @CsvSource(
        "processed, accredited, APPROVED, accredited",
        "failed, rejected_by_issuer, REJECTED, rejected_by_issuer",
        "canceled, , REJECTED, canceled",
        "expired, expired, REJECTED, expired",
        "refunded, refunded, REFUNDED, refunded",
        "processing, in_process, PENDING, in_process",
        "action_required, , PENDING, ",
        "created, , PENDING, ",
    )
    fun `status query maps the order status`(
        orderStatus: String,
        paymentDetail: String?,
        expected: GatewayPaymentStatus,
        expectedDetail: String?,
    ) {
        val payments = paymentDetail?.let { """, "transactions": {"payments": [{"status": "x", "status_detail": "$it"}]}""" } ?: ""
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer $ACCESS_TOKEN"))
            .andRespond(json(HttpStatus.OK, """{"id": "ORDTST01", "status": "$orderStatus"$payments}"""))

        assertEquals(GatewayPayment("ORDTST01", expected, expectedDetail), gateway().getStatus("ORDTST01"))
    }

    @Test
    fun `order detail is used when the payment has none`() {
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01"))
            .andRespond(json(HttpStatus.OK, """{"id": "ORDTST01", "status": "failed", "status_detail": "failed"}"""))

        assertEquals("failed", gateway().getStatus("ORDTST01").statusDetail)
    }

    @Test
    fun `refund posts without body and answers refunded`() {
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01/refund"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer $ACCESS_TOKEN"))
            .andExpect(header("X-Idempotency-Key", "refund-ORDTST01"))
            .andExpect(content().string(""))
            .andRespond(json(HttpStatus.CREATED, """{"id": "ORDTST01", "status": "refunded", "status_detail": "refunded"}"""))

        assertEquals(GatewayPaymentStatus.REFUNDED, gateway().refund("ORDTST01", 21000).status)
        server.verify()
    }

    @Test
    fun `refund of an order refunded before is confirmed`() {
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01/refund"))
            .andRespond(json(HttpStatus.CONFLICT, """{"errors": [{"code": "cannot_refund_order"}]}"""))
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(json(HttpStatus.OK, """{"id": "ORDTST01", "status": "refunded"}"""))

        assertEquals(GatewayPaymentStatus.REFUNDED, gateway().refund("ORDTST01", 21000).status)
        server.verify()
    }

    @Test
    fun `refund of an order that is not processed fails`() {
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01/refund"))
            .andRespond(json(HttpStatus.CONFLICT, """{"errors": [{"code": "cannot_refund_order"}]}"""))
        server.expect(requestTo("$BASE_URL/v1/orders/ORDTST01"))
            .andRespond(json(HttpStatus.OK, """{"id": "ORDTST01", "status": "processing"}"""))

        val failure = assertFailsWith<PaymentProviderException> { gateway().refund("ORDTST01", 21000) }

        assertEquals("refund-order: HTTP 409 cannot_refund_order", failure.message)
    }

    @Test
    fun `amounts are decimal strings with two places`() {
        assertEquals("210.00", MercadoPagoPaymentGateway.decimal(21000))
        assertEquals("0.05", MercadoPagoPaymentGateway.decimal(5))
        assertEquals("1.00", MercadoPagoPaymentGateway.decimal(100))
    }

    @Test
    fun `settings never show credentials or card data`() {
        val text = properties().toString() + properties().testCard.toString()
        listOf(ACCESS_TOKEN, PUBLIC_KEY, CARD_NUMBER).forEach { assertFalse(text.contains(it)) }
    }

    private fun assertNoSecrets(failure: Throwable) {
        val text = generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() }
        listOf(ACCESS_TOKEN, PUBLIC_KEY, CARD_NUMBER, CARD_TOKEN).forEach { assertFalse(text.contains(it), "leaked $it") }
    }

    private companion object {
        const val BASE_URL = "https://mercadopago.test"
        const val ACCESS_TOKEN = "fake-access-token"
        const val PUBLIC_KEY = "fake-public-key"
        const val CARD_NUMBER = "0000000000000000"
        const val CARD_TOKEN = "fake-card-token"
    }
}
