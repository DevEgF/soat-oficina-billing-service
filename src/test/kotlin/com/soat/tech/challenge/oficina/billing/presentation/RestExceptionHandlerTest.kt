package com.soat.tech.challenge.oficina.billing.presentation

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.soat.tech.challenge.oficina.billing.domain.exception.BusinessRuleException
import com.soat.tech.challenge.oficina.billing.domain.exception.DomainException
import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.context.request.ServletWebRequest

class RestExceptionHandlerTest {

    private val handler = RestExceptionHandler()

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `unexpected exception returns generic response with request id and sanitized log`() {
        MDC.put("requestId", "req-500")
        val logger = LoggerFactory.getLogger(RestExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val response = handler.unexpected(RuntimeException("SQL select password from customer where cpf=12345678900"))

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
            assertEquals(ErrorBody("Internal server error", "req-500"), response.body)
            assertEquals(1, appender.list.size)
            val event = appender.list.single()
            assertEquals("request.failed exceptionClass=java.lang.RuntimeException", event.formattedMessage)
            assertNull(event.throwableProxy)
            assertFalse(event.formattedMessage.contains("password"))
            assertFalse(event.formattedMessage.contains("12345678900"))
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `domain errors map to not found bad request and conflict`() {
        MDC.put("requestId", "req-domain")

        assertResponse(handler.notFound(NotFoundException("missing")), HttpStatus.NOT_FOUND, "missing")
        assertResponse(handler.badRequest(IllegalArgumentException("bad input")), HttpStatus.BAD_REQUEST, "bad input")
        assertResponse(handler.conflictDomain(BusinessRuleException("rule")), HttpStatus.CONFLICT, "rule")
        assertResponse(handler.conflictDomain(DomainException("domain")), HttpStatus.CONFLICT, "domain")
        assertResponse(handler.conflictState(IllegalStateException("state")), HttpStatus.CONFLICT, "state")
        assertResponse(
            handler.dataIntegrityViolation(DataIntegrityViolationException("duplicate key value cpf=123")),
            HttpStatus.CONFLICT,
            "Data conflict: a record with this data already exists",
        )
    }

    @Test
    fun `security exceptions preserve unauthorized and forbidden statuses without details`() {
        MDC.put("requestId", "req-security")

        assertResponse(handler.unauthorized(BadCredentialsException("credential detail")), HttpStatus.UNAUTHORIZED, "Invalid credentials")
        assertResponse(
            handler.authenticationFailed(InsufficientAuthenticationException("token detail")),
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
        )
        assertResponse(handler.accessDenied(AccessDeniedException("subject detail")), HttpStatus.FORBIDDEN, "Forbidden")
    }

    @Test
    fun `framework errors keep status and use the reason phrase`() {
        val response = handler.handleException(
            HttpRequestMethodNotSupportedException("DELETE"),
            ServletWebRequest(MockHttpServletRequest("DELETE", "/api/info")),
        )

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response?.statusCode)
        assertEquals(ErrorBody("Method Not Allowed", "unknown"), response?.body)
    }

    @Test
    fun `missing messages fall back to fixed texts`() {
        assertEquals("Bad request", handler.badRequest(IllegalArgumentException()).body?.message)
        assertEquals("Invalid state", handler.conflictState(IllegalStateException()).body?.message)
    }

    @Test
    fun `internal response keeps headers from framework handling`() {
        val response = handler.handleException(
            HttpRequestMethodNotSupportedException("POST", listOf("GET")),
            ServletWebRequest(MockHttpServletRequest("POST", "/api/info")),
        )

        assertEquals("GET", response?.headers?.getFirst(HttpHeaders.ALLOW))
    }

    private fun assertResponse(
        response: org.springframework.http.ResponseEntity<ErrorBody>,
        status: HttpStatus,
        message: String,
    ) {
        assertEquals(status, response.statusCode)
        assertEquals(message, response.body?.message)
        assertEquals(MDC.get("requestId") ?: "unknown", response.body?.requestId)
    }
}
