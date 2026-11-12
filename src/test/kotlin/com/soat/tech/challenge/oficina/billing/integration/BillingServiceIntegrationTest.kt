package com.soat.tech.challenge.oficina.billing.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.soat.tech.challenge.oficina.billing.support.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Full context against real PostgreSQL and RabbitMQ containers.
 * Properties are inlined so they win over APP_* variables exported in the developer shell.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = [
        "app.environment=hml",
        "app.jwt.secret=${TestJwt.RAW_SECRET}",
    ],
)
@ActiveProfiles("test")
@Testcontainers
class BillingServiceIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    @Test
    fun `health is UP with database and broker in readiness`() {
        val health = get("/actuator/health")
        assertEquals(200, health.statusCode())
        assertEquals("UP", body(health)["status"].asText())

        val readiness = get("/actuator/health/readiness")
        assertEquals(200, readiness.statusCode())
        val components = body(readiness)["components"]
        assertEquals("UP", components["db"]["status"].asText())
        assertEquals("UP", components["rabbit"]["status"].asText())
        assertEquals(200, get("/actuator/health/liveness").statusCode())
    }

    @Test
    fun `api info requires a bearer token`() {
        assertEquals(401, get("/api/info").statusCode())
        assertEquals(401, get("/api/info", TestJwt.token(environment = "prod")).statusCode())
    }

    @Test
    fun `api info answers with a valid HS256 token`() {
        val response = get("/api/info", TestJwt.token())

        assertEquals(200, response.statusCode())
        val info = body(response)
        assertEquals("billing-service", info["service"].asText())
        assertEquals("hml", info["environment"].asText())
        assertEquals("0.0.1", info["version"].asText())
        assertNotNull(response.headers().firstValue("X-Correlation-Id").orElse(null))
    }

    @Test
    fun `openapi document is public`() {
        val response = get("/v3/api-docs")

        assertEquals(200, response.statusCode())
        assertTrue(body(response)["paths"].has("/api/info"))
    }

    @Test
    fun `routes outside the public and api prefixes are denied`() {
        assertEquals(401, get("/internal").statusCode())
        assertEquals(403, get("/internal", TestJwt.token()).statusCode())
    }

    private fun get(path: String, token: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        token?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>): JsonNode = json.readTree(response.body())

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16.15-alpine")

        @Container
        @ServiceConnection
        @JvmStatic
        val rabbit = RabbitMQContainer("rabbitmq:4.2.9-alpine")
    }
}
