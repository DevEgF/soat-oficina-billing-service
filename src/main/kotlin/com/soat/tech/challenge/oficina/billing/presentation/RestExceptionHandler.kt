package com.soat.tech.challenge.oficina.billing.presentation

import com.soat.tech.challenge.oficina.billing.domain.exception.BusinessRuleException
import com.soat.tech.challenge.oficina.billing.domain.exception.DomainException
import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

data class ErrorBody(val message: String, val requestId: String)

@RestControllerAdvice
class RestExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) =
        response(HttpStatus.NOT_FOUND, e.message ?: "Not found")

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) =
        response(HttpStatus.BAD_REQUEST, e.message ?: "Bad request")

    @ExceptionHandler(BusinessRuleException::class, DomainException::class)
    fun conflictDomain(e: DomainException) =
        response(HttpStatus.CONFLICT, e.message ?: "Business rule violation")

    @ExceptionHandler(IllegalStateException::class)
    fun conflictState(e: IllegalStateException) =
        response(HttpStatus.CONFLICT, e.message ?: "Invalid state")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun dataIntegrityViolation(e: DataIntegrityViolationException) =
        response(HttpStatus.CONFLICT, "Data conflict: a record with this data already exists")

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun concurrentUpdate(e: OptimisticLockingFailureException) =
        response(HttpStatus.CONFLICT, "Concurrent update: reload and try again")

    @ExceptionHandler(BadCredentialsException::class)
    fun unauthorized(e: BadCredentialsException) =
        response(HttpStatus.UNAUTHORIZED, "Invalid credentials")

    @ExceptionHandler(AuthenticationException::class)
    fun authenticationFailed(e: AuthenticationException) =
        response(HttpStatus.UNAUTHORIZED, "Unauthorized")

    @ExceptionHandler(AccessDeniedException::class)
    fun accessDenied(e: AccessDeniedException) =
        response(HttpStatus.FORBIDDEN, "Forbidden")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ErrorBody> {
        // Only the class name is logged: messages may carry SQL, documents or credentials.
        log.error("request.failed exceptionClass={}", e.javaClass.name)
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error")
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val message = HttpStatus.resolve(statusCode.value())?.reasonPhrase ?: "Request failed"
        return ResponseEntity(ErrorBody(message, requestId()), headers, statusCode)
    }

    private fun response(status: HttpStatus, message: String) =
        ResponseEntity.status(status).body(ErrorBody(message, requestId()))

    private fun requestId() = MDC.get("requestId") ?: "unknown"

    private companion object {
        val log = LoggerFactory.getLogger(RestExceptionHandler::class.java)
    }
}
