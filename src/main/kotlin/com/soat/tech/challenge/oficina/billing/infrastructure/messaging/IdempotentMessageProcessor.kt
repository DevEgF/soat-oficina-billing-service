package com.soat.tech.challenge.oficina.billing.infrastructure.messaging

import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.ProcessedMessageEntity
import com.soat.tech.challenge.oficina.billing.infrastructure.persistence.jpa.ProcessedMessageJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Runs the effect of a message and records its messageId in processed_message in the same
 * transaction. A repeated messageId is skipped; two concurrent copies collide on the primary key,
 * the loser is retried and then finds the record.
 */
@Component
class IdempotentMessageProcessor(
    private val processed: ProcessedMessageJpaRepository,
    private val clock: Clock,
) {

    /** Returns false when the message had already been processed. */
    @Transactional
    fun process(envelope: MessageEnvelope, effect: () -> Unit): Boolean {
        if (processed.existsById(envelope.messageId)) {
            return false
        }
        effect()
        processed.saveAndFlush(
            ProcessedMessageEntity(
                messageId = envelope.messageId,
                type = envelope.type,
                processedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS),
            ),
        )
        return true
    }
}
