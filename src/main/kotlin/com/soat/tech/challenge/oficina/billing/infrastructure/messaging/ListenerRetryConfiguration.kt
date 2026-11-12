package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The listener retry (spring.rabbitmq.listener.simple.retry.*) applies only to transient failures.
 * Messages rejected on purpose (InvalidMessageException and other AmqpRejectAndDontRequeueException)
 * skip the retries and go to the DLQ at once.
 */
@Configuration
class ListenerRetryConfiguration {

    @Bean
    fun skipRetryForRejectedMessages() = RabbitListenerRetrySettingsCustomizer { settings ->
        settings.exceptionPredicate = java.util.function.Predicate(::isRetryable)
    }

    companion object {
        fun isRetryable(failure: Throwable): Boolean =
            generateSequence(failure) { current -> current.cause?.takeIf { it !== current } }
                .none { it is AmqpRejectAndDontRequeueException }
    }
}
