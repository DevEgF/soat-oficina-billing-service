package com.soat.tech.challenge.oficina.billing.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer

/** Real PostgreSQL and RabbitMQ, started once and shared by every integration test class. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:16.15-alpine")

    @Bean
    @ServiceConnection
    fun rabbit(): RabbitMQContainer = RabbitMQContainer("rabbitmq:4.2.9-alpine")
}

/**
 * Full context against the shared containers. Same annotations on every class keep a single cached
 * context. Properties are inlined so they win over APP_* variables exported in the developer shell.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = [
        "app.environment=hml",
        "app.jwt.secret=${TestJwt.RAW_SECRET}",
        "app.payment.gateway=simulated",
    ],
)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
annotation class IntegrationTest
