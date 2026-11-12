package com.soat.tech.challenge.oficina.billing.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

/**
 * Signs HS256 tokens the same way the issuer does: key = SHA-256(raw secret).
 */
object TestJwt {

    /** Committed sentinel accepted only under the local/test profiles. */
    const val RAW_SECRET = "test-jwt-secret-adequado-para-hs256-bytes"
    const val SUBJECT = "8f647d7f-da76-418b-abcd-5cffbc62e391"

    fun token(
        subject: String = SUBJECT,
        issuer: String = "oficina",
        audience: String? = "oficina-api",
        environment: String? = "hml",
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant = Instant.now().plusSeconds(300),
        scope: List<String> = listOf("CUSTOMER"),
        rawSecret: String = RAW_SECRET,
    ): String {
        val builder = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .claim("scope", scope)
        audience?.let(builder::audience)
        environment?.let { builder.claim("env", it) }
        val signingKey = MessageDigest.getInstance("SHA-256").digest(rawSecret.toByteArray(Charsets.UTF_8))
        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), builder.build())
            .also { it.sign(MACSigner(signingKey)) }
            .serialize()
    }
}
