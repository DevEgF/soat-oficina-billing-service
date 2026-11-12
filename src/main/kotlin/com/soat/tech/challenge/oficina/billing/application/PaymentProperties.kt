package com.soat.tech.challenge.oficina.billing.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Payment settings (app.payment.*). The adapter itself is chosen by app.payment.gateway. */
@ConfigurationProperties("app.payment")
data class PaymentProperties(
    /** PaymentGateway adapter: simulated (default) or mercadopago. */
    val gateway: String = "simulated",
    /** Cardholder name used when the approval does not send one. */
    val defaultCardholderName: String = "APRO",
    /** Interval of the periodic check of pending payments. */
    val statusCheckInterval: Duration = Duration.ofSeconds(10),
) {
    init {
        require(defaultCardholderName.isNotBlank()) { "app.payment.default-cardholder-name must not be blank" }
        require(!statusCheckInterval.isNegative && !statusCheckInterval.isZero) {
            "app.payment.status-check-interval must be positive"
        }
    }
}
