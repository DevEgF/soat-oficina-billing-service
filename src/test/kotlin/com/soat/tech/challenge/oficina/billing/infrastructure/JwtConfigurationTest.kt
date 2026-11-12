package com.soat.tech.challenge.oficina.billing.infrastructure

import com.soat.tech.challenge.oficina.billing.support.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidationException
import java.time.Instant
import java.util.Base64

class JwtConfigurationTest {

    private val configuration = JwtConfiguration(
        signingSecret = JwtSigningSecret(
            MockEnvironment()
                .withProperty("app.jwt.secret", TestJwt.RAW_SECRET)
                .apply { setActiveProfiles("test") },
        ),
        appEnvironment = AppEnvironment("hml"),
    )
    private val decoder = configuration.jwtDecoder()

    @Test
    fun `environment accepts only hml and prod`() {
        assertEquals("hml", AppEnvironment(" hml ").value)
        assertEquals("prod", AppEnvironment("prod").value)
        assertThrows(IllegalArgumentException::class.java) { AppEnvironment("local") }
        assertThrows(IllegalArgumentException::class.java) { AppEnvironment("") }
    }

    @Test
    fun `signing key is the sha256 of the raw secret for HmacSHA256`() {
        val key = configuration.jwtSigningKey()
        assertEquals("HmacSHA256", key.algorithm)
        assertEquals(32, key.encoded.size)
    }

    @Test
    fun `decoder accepts customer and staff subjects in active environment`() {
        assertEquals(TestJwt.SUBJECT, decoder.decode(TestJwt.token()).subject)
        assertEquals("admin", decoder.decode(TestJwt.token(subject = "admin", scope = listOf("ADMIN"))).subject)
    }

    @Test
    fun `decoder rejects token from another environment`() {
        assertThrows(JwtValidationException::class.java) { decoder.decode(TestJwt.token(environment = "prod")) }
    }

    @Test
    fun `decoder rejects missing environment`() {
        assertThrows(JwtValidationException::class.java) { decoder.decode(TestJwt.token(environment = null)) }
    }

    @Test
    fun `decoder rejects missing audience`() {
        assertThrows(JwtValidationException::class.java) { decoder.decode(TestJwt.token(audience = null)) }
    }

    @Test
    fun `decoder rejects wrong issuer`() {
        assertThrows(JwtValidationException::class.java) { decoder.decode(TestJwt.token(issuer = "other")) }
    }

    @Test
    fun `decoder rejects expired token`() {
        assertThrows(JwtValidationException::class.java) {
            decoder.decode(
                TestJwt.token(
                    issuedAt = Instant.now().minusSeconds(120),
                    expiresAt = Instant.now().minusSeconds(60),
                ),
            )
        }
    }

    @Test
    fun `decoder rejects token signed with another secret`() {
        assertThrows(JwtException::class.java) {
            decoder.decode(TestJwt.token(rawSecret = "another-secret-with-at-least-thirty-two-bytes"))
        }
    }

    @Test
    fun `decoder rejects corrupted signature`() {
        val valid = TestJwt.token()
        // Alter signature bytes: changing trailing Base64 padding bits can leave
        // the decoded signature intact and make this test fail intermittently.
        val signature = Base64.getUrlDecoder().decode(valid.substringAfterLast('.'))
        signature[0] = (signature[0].toInt() xor 1).toByte()
        val corrupted = valid.substringBeforeLast('.') + "." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        assertThrows(JwtException::class.java) { decoder.decode(corrupted) }
    }
}
