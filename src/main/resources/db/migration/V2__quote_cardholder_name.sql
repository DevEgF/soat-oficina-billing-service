-- Nome do titular do cartão informado na aprovação do orçamento e usado ao criar o pagamento.
-- No ambiente de teste do Mercado Pago o nome do titular define o resultado (APRO, CONT, OTHE...).
-- Orçamentos anteriores ficam sem valor e usam o padrão app.payment.default-cardholder-name.
ALTER TABLE quote ADD COLUMN cardholder_name VARCHAR(100);
