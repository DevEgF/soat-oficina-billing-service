package com.soat.tech.challenge.oficina.billing.infrastructure.payment.mercadopago

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

/**
 * Real provider, selected by app.payment.gateway=mercadopago. Missing credentials or test card
 * data stop the startup with the names of the variables to set.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.payment.gateway"], havingValue = "mercadopago")
class MercadoPagoConfiguration {

    @Bean
    fun mercadoPagoPaymentGateway(properties: MercadoPagoProperties): MercadoPagoPaymentGateway {
        val missing = properties.missingSettings()
        check(missing.isEmpty()) {
            "app.payment.gateway=mercadopago requires these environment variables (missing or invalid): " +
                missing.joinToString()
        }
        val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
        val client = RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(requestFactory)
            .build()
        return MercadoPagoPaymentGateway(client, properties)
    }
}
