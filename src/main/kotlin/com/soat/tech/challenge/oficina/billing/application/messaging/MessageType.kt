package com.soat.tech.challenge.oficina.billing.application.messaging

/**
 * Message types of the saga contract that concern the Billing Service, with their routing keys
 * on the `oficina` exchange. Commands are consumed from `billing.commands`; events are published.
 */
enum class MessageType(val typeName: String, val routingKey: String, val kind: Kind) {
    GENERATE_QUOTE("GenerateQuote", "command.billing.generate-quote", Kind.COMMAND),
    REQUEST_PAYMENT("RequestPayment", "command.billing.request-payment", Kind.COMMAND),
    CANCEL_QUOTE("CancelQuote", "command.billing.cancel-quote", Kind.COMMAND),
    REFUND_PAYMENT("RefundPayment", "command.billing.refund-payment", Kind.COMMAND),

    QUOTE_GENERATED("QuoteGenerated", "event.billing.quote-generated", Kind.EVENT),
    QUOTE_GENERATION_FAILED("QuoteGenerationFailed", "event.billing.quote-generation-failed", Kind.EVENT),
    QUOTE_APPROVED("QuoteApproved", "event.billing.quote-approved", Kind.EVENT),
    QUOTE_REJECTED("QuoteRejected", "event.billing.quote-rejected", Kind.EVENT),
    QUOTE_CANCELLED("QuoteCancelled", "event.billing.quote-cancelled", Kind.EVENT),
    PAYMENT_APPROVED("PaymentApproved", "event.billing.payment-approved", Kind.EVENT),
    PAYMENT_REJECTED("PaymentRejected", "event.billing.payment-rejected", Kind.EVENT),
    PAYMENT_REFUNDED("PaymentRefunded", "event.billing.payment-refunded", Kind.EVENT),
    ;

    enum class Kind { COMMAND, EVENT }

    companion object {
        private val byTypeName = entries.associateBy { it.typeName }

        fun fromTypeName(typeName: String?): MessageType? = typeName?.let(byTypeName::get)
    }
}
