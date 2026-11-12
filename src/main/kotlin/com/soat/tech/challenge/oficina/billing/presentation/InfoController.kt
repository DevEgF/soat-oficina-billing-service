package com.soat.tech.challenge.oficina.billing.presentation

import com.soat.tech.challenge.oficina.billing.infrastructure.AppEnvironment
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ServiceInfoResponse(val service: String, val environment: String, val version: String)

/**
 * Minimal authenticated endpoint: proves JWT validation end to end.
 */
@RestController
@RequestMapping("/api/info")
class InfoController(
    @Value("\${spring.application.name}") private val service: String,
    appEnvironment: AppEnvironment,
    buildProperties: ObjectProvider<BuildProperties>,
) {
    private val info = ServiceInfoResponse(
        service = service,
        environment = appEnvironment.value,
        version = buildProperties.ifAvailable?.version ?: UNKNOWN_VERSION,
    )

    @GetMapping
    fun info(): ServiceInfoResponse = info

    private companion object {
        const val UNKNOWN_VERSION = "unknown"
    }
}
