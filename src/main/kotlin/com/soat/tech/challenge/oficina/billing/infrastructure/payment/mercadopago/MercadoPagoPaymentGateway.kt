package com.soat.tech.challenge.oficina.billing.infrastructure.payment.mercadopago

import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPayment
import com.soat.tech.challenge.oficina.billing.domain.port.GatewayPaymentStatus
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentCreation
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentGateway
import com.soat.tech.challenge.oficina.billing.domain.port.PaymentProviderException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/**
 * Mercado Pago through the Orders API (transparent checkout), as used in the provider test
 * environment: the configured test card is tokenized with the cardholder name of the approval,
 * then an order is created with that token. The order usually comes back `processing` and is
 * resolved about a minute later, so creation always answers PENDING and the periodic status check
 * publishes the result.
 *
 * Tokens, card data and the access token never reach logs or exception messages: failures carry
 * only the operation, the HTTP status and the provider error code.
 */
class MercadoPagoPaymentGateway(
    private val client: RestClient,
    private val properties: MercadoPagoProperties,
) : PaymentGateway {

    override val provider: String = PROVIDER

    private val mapper: JsonMapper = JsonMapper.builder().build()

    override fun create(request: PaymentCreation): GatewayPayment {
        val payerEmail = properties.payerEmail.takeIf { it.isNotBlank() }
            ?: request.payerEmail?.takeIf { it.isNotBlank() }
            ?: throw PaymentProviderException("create-order: no payer e-mail (app.mercadopago.payer-email or customer e-mail)")
        val cardToken = cardToken(request.cardholderName)
        val amount = decimal(request.amountCents)
        val body = mapOf(
            "type" to "online",
            "processing_mode" to "automatic",
            "total_amount" to amount,
            "external_reference" to request.workOrderId.toString(),
            "payer" to mapOf("email" to payerEmail),
            "transactions" to mapOf(
                "payments" to listOf(
                    mapOf(
                        "amount" to amount,
                        "payment_method" to mapOf(
                            "id" to PAYMENT_METHOD_ID,
                            "type" to PAYMENT_METHOD_TYPE,
                            "token" to cardToken,
                            "installments" to 1,
                        ),
                    ),
                ),
            ),
        )
        // A repeated key with a different body (new card token) answers 409 idempotency_key_already_used
        // without the original order: it stays an error, so the message is retried and dead-lettered.
        val response = exchange(CREATE_ORDER) {
            client.post().uri("/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header(IDEMPOTENCY_KEY_HEADER, request.idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body))
        }
        // A payment refused right away comes back as HTTP 402 with the list of errors and the created
        // order under `data` (status failed): the order exists, so it is recorded and resolved like
        // any other.
        val order = response.takeIf { it.status !in 200..299 }?.orderInError()
            ?.also { log.warn("mercadopago.order.created-with-error httpStatus={} orderId={}", response.status, it.text("id")) }
            ?: response.requireSuccess(CREATE_ORDER)
        val created = toGatewayPayment(order, CREATE_ORDER)
        log.info(
            "mercadopago.order.created orderId={} providerStatus={} detail={}",
            created.providerPaymentId, created.status, created.statusDetail,
        )
        // Usually `processing`, sometimes already resolved: either way the result is published by
        // the periodic status check, so every charge follows the same path.
        return created.copy(status = GatewayPaymentStatus.PENDING)
    }

    override fun getStatus(providerPaymentId: String): GatewayPayment {
        val order = exchange(GET_ORDER) {
            client.get().uri("/v1/orders/{id}", providerPaymentId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
        }.requireSuccess(GET_ORDER)
        return toGatewayPayment(order, GET_ORDER)
    }

    /** Full refund: the Orders API refunds the whole order when the request has no body. */
    override fun refund(providerPaymentId: String, amountCents: Long): GatewayPayment {
        val response = exchange(REFUND_ORDER) {
            client.post().uri("/v1/orders/{id}/refund", providerPaymentId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header(IDEMPOTENCY_KEY_HEADER, "refund-$providerPaymentId")
        }
        if (response.status == HTTP_CONFLICT && response.errorCode == CANNOT_REFUND) {
            // Only a processed order can be refunded: one refunded before is confirmed as such.
            val current = getStatus(providerPaymentId)
            if (current.status == GatewayPaymentStatus.REFUNDED) {
                log.info("mercadopago.order.already-refunded orderId={}", providerPaymentId)
                return current
            }
        }
        return toGatewayPayment(response.requireSuccess(REFUND_ORDER), REFUND_ORDER).also {
            log.info("mercadopago.order.refunded orderId={} status={} amountCents={}", it.providerPaymentId, it.status, amountCents)
        }
    }

    private fun cardToken(cardholderName: String): String {
        val card = properties.testCard
        val body = mapOf(
            "card_number" to card.number.trim(),
            "expiration_month" to card.expirationMonth.trim().toInt(),
            "expiration_year" to card.expirationYear.trim().toInt(),
            "security_code" to card.securityCode.trim(),
            "cardholder" to mapOf(
                "name" to cardholderName,
                "identification" to mapOf("type" to DOCUMENT_TYPE, "number" to card.holderDocument.trim()),
            ),
        )
        val token = exchange(CARD_TOKEN) {
            client.post().uri { it.path("/v1/card_tokens").queryParam("public_key", properties.publicKey).build() }
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body))
        }.requireSuccess(CARD_TOKEN)
        return token.text("id") ?: throw PaymentProviderException("$CARD_TOKEN: answer without id")
    }

    /**
     * Order status mapping: processed approves; failed, canceled and expired reject (reason: the
     * payment status_detail); refunded is a refund; anything else (processing, action_required,
     * created...) is still pending.
     */
    internal fun toGatewayPayment(order: JsonNode, operation: String): GatewayPayment {
        val id = order.text("id") ?: throw PaymentProviderException("$operation: answer without order id")
        val status = order.text("status")
        val payment = order.path("transactions").path("payments").path(0)
        val detail = payment.text("status_detail") ?: order.text("status_detail")
        val mapped = when (status) {
            "processed" -> GatewayPaymentStatus.APPROVED
            "failed", "canceled", "expired" -> GatewayPaymentStatus.REJECTED
            "refunded" -> GatewayPaymentStatus.REFUNDED
            else -> GatewayPaymentStatus.PENDING
        }
        val reason = if (mapped == GatewayPaymentStatus.REJECTED) detail ?: status else detail
        return GatewayPayment(id, mapped, reason)
    }

    private fun bearer() = "Bearer ${properties.accessToken}"

    private fun exchange(operation: String, request: () -> RestClient.RequestHeadersSpec<*>): ProviderResponse = try {
        request().exchange { _, response ->
            val bytes = response.body.readAllBytes()
            ProviderResponse(response.statusCode.value(), parse(bytes))
        } ?: throw PaymentProviderException("$operation: empty answer")
    } catch (e: RestClientException) {
        // The cause is dropped on purpose: its message carries the URL (with the public key).
        val failure = "${e.javaClass.simpleName}${e.rootCause?.let { " (${it.javaClass.simpleName})" } ?: ""}"
        log.warn("mercadopago.call.failed operation={} failure={}", operation, failure)
        throw PaymentProviderException("$operation: $failure")
    }

    private fun parse(bytes: ByteArray): JsonNode? = if (bytes.isEmpty()) {
        null
    } else {
        try {
            mapper.readTree(bytes)
        } catch (e: JacksonException) {
            null
        }
    }

    private fun ProviderResponse.requireSuccess(operation: String): JsonNode {
        if (status !in 200..299) {
            // Logged here too: when a retry ends in another error, the first cause stays visible.
            log.warn("mercadopago.call.failed operation={} httpStatus={} errorCode={}", operation, status, errorCode)
            throw PaymentProviderException("$operation: HTTP $status${errorCode?.let { " $it" } ?: ""}")
        }
        return body ?: throw PaymentProviderException("$operation: HTTP $status without JSON body")
    }

    private data class ProviderResponse(val status: Int, val body: JsonNode?) {
        /** First error code of the answer (Orders: errors[].code; card tokens: error or cause[].code). */
        val errorCode: String?
            get() = sequenceOf(
                body?.path("errors")?.path(0)?.text("code"),
                body?.path("cause")?.path(0)?.text("code"),
                body?.text("error"),
            ).firstOrNull { it != null && SAFE_CODE.matches(it) }

        /** Order carried by an error answer: under `data`, or the answer itself. */
        fun orderInError(): JsonNode? =
            sequenceOf(body?.get("data"), body).firstOrNull { it != null && it.isOrder() }
    }

    companion object {
        const val PROVIDER = "MERCADO_PAGO"
        const val IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key"
        private const val PAYMENT_METHOD_ID = "master"
        private const val PAYMENT_METHOD_TYPE = "credit_card"
        private const val DOCUMENT_TYPE = "CPF"
        private const val CARD_TOKEN = "card-token"
        private const val CREATE_ORDER = "create-order"
        private const val GET_ORDER = "get-order"
        private const val REFUND_ORDER = "refund-order"
        private const val CANNOT_REFUND = "cannot_refund_order"
        private const val HTTP_CONFLICT = 409
        private val SAFE_CODE = Regex("[A-Za-z0-9_.-]{1,64}")
        private val log = LoggerFactory.getLogger(MercadoPagoPaymentGateway::class.java)

        /** Cents to the decimal string of the API, always with two places (21000 -> "210.00"). */
        fun decimal(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

        private fun JsonNode.isOrder(): Boolean = text("id") != null && text("status") != null

        private fun JsonNode.text(field: String): String? =
            get(field)?.takeIf { it.isValueNode && !it.isNull }?.asString()?.takeIf { it.isNotBlank() }
    }
}
