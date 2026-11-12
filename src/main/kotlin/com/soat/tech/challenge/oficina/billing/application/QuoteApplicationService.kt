package com.soat.tech.challenge.oficina.billing.application

import com.soat.tech.challenge.oficina.billing.application.messaging.EventPublisher
import com.soat.tech.challenge.oficina.billing.application.messaging.MessageType
import com.soat.tech.challenge.oficina.billing.application.messaging.OutgoingEvent
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteApprovedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteCancelledPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteGeneratedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteGenerationFailedPayload
import com.soat.tech.challenge.oficina.billing.application.messaging.QuoteRejectedPayload
import com.soat.tech.challenge.oficina.billing.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.billing.domain.model.PricingResult
import com.soat.tech.challenge.oficina.billing.domain.model.Quote
import com.soat.tech.challenge.oficina.billing.domain.model.QuotePricing
import com.soat.tech.challenge.oficina.billing.domain.model.RequestedItem
import com.soat.tech.challenge.oficina.billing.domain.port.CatalogRepository
import com.soat.tech.challenge.oficina.billing.domain.port.QuoteRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class GenerateQuoteCommand(
    val workOrderId: UUID,
    val trackingCode: String?,
    val customerName: String,
    val customerEmail: String?,
    val services: List<RequestedItem>,
    val parts: List<RequestedItem>,
    val correlationId: String,
)

data class CancelQuoteCommand(
    val workOrderId: UUID,
    /** Quote known by the orchestrator; when present it must match the quote of the work order. */
    val quoteId: UUID?,
    val reason: String?,
)

@Service
class QuoteApplicationService(
    private val quotes: QuoteRepository,
    private val catalog: CatalogRepository,
    private val events: EventPublisher,
    private val clock: Clock,
    private val paymentProperties: PaymentProperties,
) {

    /**
     * GenerateQuote: prices the items with the catalog, stores the quote PENDING_APPROVAL and
     * publishes QuoteGenerated. Any item outside the catalog publishes QuoteGenerationFailed and
     * stores nothing. A second GenerateQuote for the same work order is discarded (one quote per OS).
     */
    @Transactional
    fun generate(command: GenerateQuoteCommand) {
        quotes.findByWorkOrderId(command.workOrderId)?.let { existing ->
            log.warn(
                "quote.generate.ignored reason=quote-already-exists workOrderId={} quoteId={} status={}",
                command.workOrderId, existing.id, existing.status,
            )
            return
        }
        val catalogServices = catalog.findServicesByCodes(command.services.map { it.code }.toSet()).associateBy { it.code }
        val catalogParts = catalog.findPartsByCodes(command.parts.map { it.code }.toSet()).associateBy { it.code }

        when (val pricing = QuotePricing.price(command.services, command.parts, catalogServices, catalogParts)) {
            is PricingResult.Failed -> {
                log.warn("quote.generate.failed workOrderId={} reason={}", command.workOrderId, pricing.reason)
                events.publishAfterCommit(
                    OutgoingEvent(
                        MessageType.QUOTE_GENERATION_FAILED,
                        command.workOrderId,
                        command.correlationId,
                        QuoteGenerationFailedPayload(command.workOrderId, pricing.reason),
                    ),
                )
            }
            is PricingResult.Priced -> {
                val quote = quotes.save(
                    Quote.create(
                        workOrderId = command.workOrderId,
                        trackingCode = command.trackingCode,
                        customerName = command.customerName,
                        customerEmail = command.customerEmail,
                        correlationId = command.correlationId,
                        lines = pricing.lines,
                        now = now(),
                    ),
                )
                log.info("quote.generated workOrderId={} quoteId={} totalCents={}", quote.workOrderId, quote.id, quote.totalCents)
                events.publishAfterCommit(
                    OutgoingEvent(
                        MessageType.QUOTE_GENERATED,
                        quote.workOrderId,
                        quote.correlationId,
                        QuoteGeneratedPayload(
                            workOrderId = quote.workOrderId,
                            quoteId = quote.id,
                            servicesTotalCents = quote.servicesTotalCents,
                            partsTotalCents = quote.partsTotalCents,
                            totalCents = quote.totalCents,
                        ),
                    ),
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun get(workOrderId: UUID): Quote = load(workOrderId)

    /**
     * Customer decision, only from PENDING_APPROVAL (otherwise InvalidStatusTransitionException, 409).
     * An approval keeps the cardholder name (or the configured default) for the payment.
     */
    @Transactional
    fun decide(workOrderId: UUID, approved: Boolean, reason: String?, cardholderName: String? = null): Quote {
        val quote = load(workOrderId)
        val event = if (approved) {
            quote.approve(now(), cardholderName?.takeIf { it.isNotBlank() } ?: paymentProperties.defaultCardholderName)
            OutgoingEvent(
                MessageType.QUOTE_APPROVED,
                quote.workOrderId,
                quote.correlationId,
                QuoteApprovedPayload(quote.workOrderId, quote.id, quote.totalCents),
            )
        } else {
            quote.reject(now(), reason)
            OutgoingEvent(
                MessageType.QUOTE_REJECTED,
                quote.workOrderId,
                quote.correlationId,
                QuoteRejectedPayload(quote.workOrderId, quote.id, quote.decisionReason),
            )
        }
        val saved = quotes.save(quote)
        log.info("quote.decided workOrderId={} quoteId={} status={}", saved.workOrderId, saved.id, saved.status)
        events.publishAfterCommit(event)
        return saved
    }

    /**
     * CancelQuote (compensation): an open or approved quote goes to CANCELLED and QuoteCancelled is
     * published. A quote already cancelled or rejected is left as is and QuoteCancelled is published
     * again, because the orchestrator waits for that confirmation. An unknown quote is discarded.
     */
    @Transactional
    fun cancel(command: CancelQuoteCommand) {
        val quote = quotes.findByWorkOrderId(command.workOrderId)
        if (quote == null || (command.quoteId != null && command.quoteId != quote.id)) {
            log.warn(
                "quote.cancel.ignored reason=quote-not-found workOrderId={} quoteId={}",
                command.workOrderId, command.quoteId,
            )
            return
        }
        if (quote.isClosed) {
            log.info(
                "quote.cancel.confirmed-again workOrderId={} quoteId={} status={}",
                quote.workOrderId, quote.id, quote.status,
            )
        } else {
            quote.cancel(now(), command.reason)
            quotes.save(quote)
            log.info("quote.cancelled workOrderId={} quoteId={} reason={}", quote.workOrderId, quote.id, command.reason)
        }
        events.publishAfterCommit(
            OutgoingEvent(
                MessageType.QUOTE_CANCELLED,
                quote.workOrderId,
                quote.correlationId,
                QuoteCancelledPayload(quote.workOrderId, quote.id),
            ),
        )
    }

    private fun load(workOrderId: UUID): Quote =
        quotes.findByWorkOrderId(workOrderId) ?: throw NotFoundException("Quote not found for work order $workOrderId")

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    private companion object {
        val log = LoggerFactory.getLogger(QuoteApplicationService::class.java)
    }
}
