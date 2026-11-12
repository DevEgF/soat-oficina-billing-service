package com.soat.tech.challenge.oficina.billing.infrastructure

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {

    @Bean
    fun openAPI(@Value("\${spring.application.name}") service: String): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Oficina — $service")
                .description(
                    "Tech Challenge SOAT, Fase 4. Rotas /api/** exigem Bearer JWT HS256 " +
                        "(issuer oficina, audience oficina-api, claim env igual ao ambiente do serviço).",
                )
                .version("1.0.0"),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_SCHEME,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

    private companion object {
        const val BEARER_SCHEME = "bearer-jwt"
    }
}
