-- Cabeçalhos de trace distribuído (traceparent, tracestate e newrelic) da mensagem RequestPayment
-- que criou o pagamento. A verificação periódica, que resolve o pagamento depois, continua o mesmo
-- trace ao publicar PaymentApproved ou PaymentRejected. Pagamentos anteriores ficam sem valor.
ALTER TABLE payment
	ADD COLUMN trace_parent   VARCHAR(1024),
	ADD COLUMN trace_state    VARCHAR(1024),
	ADD COLUMN trace_newrelic VARCHAR(1024);
