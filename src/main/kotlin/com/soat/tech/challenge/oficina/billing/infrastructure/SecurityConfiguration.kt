package com.soat.tech.challenge.oficina.billing.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Resource server only: tokens are validated here (HS256, shared secret) and issued elsewhere.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain {
        http.csrf { it.disable() }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.authorizeHttpRequests { authorize ->
            authorize.requestMatchers(
                "/actuator/health",
                "/actuator/health/**",
                "/v3/api-docs",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
            ).permitAll()
            authorize.requestMatchers("/api/**").authenticated()
            authorize.anyRequest().denyAll()
        }
        http.oauth2ResourceServer { oauth2 -> oauth2.jwt { it.decoder(jwtDecoder) } }
        return http.build()
    }
}
