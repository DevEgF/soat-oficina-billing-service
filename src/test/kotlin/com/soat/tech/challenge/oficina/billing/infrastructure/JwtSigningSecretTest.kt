package com.soat.tech.challenge.oficina.billing.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.BeansException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.mock.env.MockEnvironment
import java.util.stream.Stream

class JwtSigningSecretTest {

    @Test
    fun `startup rejects missing blank short and placeholder jwt secrets`() {
        assertContextFails(emptyMap())
        assertContextFails(mapOf(JWT_PROPERTY to "   "))
        assertContextFails(mapOf(JWT_PROPERTY to "short-secret"))
        assertThrows(IllegalArgumentException::class.java) {
            JwtSigningSecret(MockEnvironment().withProperty(JWT_PROPERTY, "\${APP_JWT_SECRET_NOT_RESOLVED_AT_ALL}"))
        }
    }

    @ParameterizedTest
    @MethodSource("committedJwtSecrets")
    fun `startup rejects committed jwt secrets outside local and test`(secret: String) {
        assertContextFails(mapOf(JWT_PROPERTY to secret))
        assertContextFails(mapOf(JWT_PROPERTY to " $secret "))
        assertContextFails(mapOf(JWT_PROPERTY to secret), "docker", "test")
        assertContextFails(mapOf(JWT_PROPERTY to secret), "docker", "local")
    }

    @ParameterizedTest
    @MethodSource("committedJwtSecrets")
    fun `committed jwt secrets are accepted under local or test only`(secret: String) {
        listOf("local", "test").forEach { profile ->
            val environment = MockEnvironment().withProperty(JWT_PROPERTY, secret).apply { setActiveProfiles(profile) }
            assertEquals(secret, JwtSigningSecret(environment).rawValue)
        }
    }

    @Test
    fun `signing secret preserves accepted raw utf8 bytes`() {
        val raw = " 01234567890123456789012345678901 "
        val secret = JwtSigningSecret(MockEnvironment().withProperty(JWT_PROPERTY, raw))
        assertEquals(raw, secret.rawValue)
    }

    @Test
    fun `startup treats jwt expression syntax as literal secret bytes`() {
        val raw = "synthetic-only-key-with-expression-#{not-evaluated"
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("test", mapOf(JWT_PROPERTY to raw)))
            context.register(JwtSigningSecret::class.java)
            context.refresh()
            assertEquals(raw, context.getBean(JwtSigningSecret::class.java).rawValue)
        }
    }

    private fun assertContextFails(properties: Map<String, String>, vararg profiles: String) {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.setActiveProfiles(*profiles)
            // Hermetic: APP_JWT_SECRET exported in the developer shell must not satisfy the property.
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.addFirst(MapPropertySource("test", properties))
            context.register(JwtSigningSecret::class.java)
            assertThrows(BeansException::class.java) { context.refresh() }
        }
    }

    companion object {
        private const val JWT_PROPERTY = "app.jwt.secret"

        @JvmStatic
        fun committedJwtSecrets(): Stream<String> = Stream.of(
            "dev-oficina-jwt-secret-troque-em-producao-32b",
            "local-oficina-jwt-secret-for-development-only",
            "test-jwt-secret-adequado-para-hs256-bytes",
        )
    }
}
