package com.soat.tech.challenge.oficina.billing.infrastructure.payment

import com.soat.tech.challenge.oficina.billing.application.PaymentProperties
import com.soat.tech.challenge.oficina.billing.application.PaymentStatusCheckService
import com.soat.tech.challenge.oficina.billing.infrastructure.payment.mercadopago.MercadoPagoProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentProperties::class, MercadoPagoProperties::class)
class PaymentConfiguration

/**
 * Periodic check of pending payments, every app.payment.status-check-interval (ISO-8601 duration,
 * PT10S by default). Fixed delay: a slow provider never makes two passes overlap.
 */
@Component
class PaymentStatusScheduler(private val statusCheck: PaymentStatusCheckService) {

    @Scheduled(
        fixedDelayString = "\${app.payment.status-check-interval:PT10S}",
        initialDelayString = "\${app.payment.status-check-interval:PT10S}",
    )
    fun checkPendingPayments() {
        statusCheck.checkPending()
    }
}
