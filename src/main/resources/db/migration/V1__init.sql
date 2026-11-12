-- Billing Service: catálogo com preços, orçamento por OS, pagamento e idempotência de mensagens.
-- Dinheiro sempre em centavos (BIGINT). Ids de OS, orçamento e pagamento são UUID.

-- ---------- Catálogo (somente por migration; sem CRUD administrativo) ----------
CREATE TABLE catalog_service (
	code              VARCHAR(64)  NOT NULL PRIMARY KEY,
	name              VARCHAR(255) NOT NULL,
	price_cents       BIGINT       NOT NULL,
	estimated_minutes INTEGER      NOT NULL,
	CONSTRAINT ck_catalog_service_price CHECK (price_cents >= 0),
	CONSTRAINT ck_catalog_service_minutes CHECK (estimated_minutes > 0)
);

CREATE TABLE catalog_part (
	code        VARCHAR(64)  NOT NULL PRIMARY KEY,
	name        VARCHAR(255) NOT NULL,
	price_cents BIGINT       NOT NULL,
	CONSTRAINT ck_catalog_part_price CHECK (price_cents >= 0)
);

-- Códigos e preços fixados no contrato entre os serviços (a coleção Postman depende deles).
INSERT INTO catalog_service (code, name, price_cents, estimated_minutes) VALUES
	('TROCA_OLEO', 'Troca de óleo', 12000, 30),
	('ALINHAMENTO_BALANCEAMENTO', 'Alinhamento e balanceamento', 18000, 60),
	('REVISAO_FREIOS', 'Revisão de freios', 25000, 90),
	('DIAGNOSTICO_ELETRONICO', 'Diagnóstico eletrônico', 15000, 45),
	('TROCA_PNEUS', 'Troca de pneus', 8000, 40);

INSERT INTO catalog_part (code, name, price_cents) VALUES
	('FILTRO_OLEO', 'Filtro de óleo', 4500),
	('OLEO_5W30_1L', 'Óleo 5W30 (1 litro)', 5500),
	('PASTILHA_FREIO', 'Jogo de pastilhas de freio', 16000),
	('PNEU_195_55_R15', 'Pneu 195/55 R15', 42000),
	('BATERIA_60AH', 'Bateria 60 Ah', 52000);

-- ---------- Orçamento: um por OS ----------
CREATE TABLE quote (
	id                   UUID         NOT NULL PRIMARY KEY,
	work_order_id        UUID         NOT NULL,
	tracking_code        VARCHAR(64),
	customer_name        VARCHAR(255) NOT NULL,
	customer_email       VARCHAR(255),
	correlation_id       VARCHAR(128) NOT NULL,
	status               VARCHAR(32)  NOT NULL,
	services_total_cents BIGINT       NOT NULL,
	parts_total_cents    BIGINT       NOT NULL,
	total_cents          BIGINT       NOT NULL,
	decision_reason      VARCHAR(500),
	created_at           TIMESTAMPTZ  NOT NULL,
	decided_at           TIMESTAMPTZ,
	updated_at           TIMESTAMPTZ  NOT NULL,
	version              BIGINT       NOT NULL,
	CONSTRAINT uk_quote_work_order UNIQUE (work_order_id),
	CONSTRAINT ck_quote_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

-- Linhas com o nome e o preço do catálogo no momento do orçamento (snapshot).
CREATE TABLE quote_line (
	quote_id         UUID         NOT NULL REFERENCES quote (id) ON DELETE CASCADE,
	line_number      INTEGER      NOT NULL,
	kind             VARCHAR(16)  NOT NULL,
	code             VARCHAR(64)  NOT NULL,
	name             VARCHAR(255) NOT NULL,
	quantity         INTEGER      NOT NULL,
	unit_price_cents BIGINT       NOT NULL,
	total_cents      BIGINT       NOT NULL,
	PRIMARY KEY (quote_id, line_number),
	CONSTRAINT ck_quote_line_kind CHECK (kind IN ('SERVICE', 'PART')),
	CONSTRAINT ck_quote_line_quantity CHECK (quantity > 0)
);

-- ---------- Pagamento: um por OS ----------
CREATE TABLE payment (
	id                  UUID         NOT NULL PRIMARY KEY,
	work_order_id       UUID         NOT NULL,
	quote_id            UUID         NOT NULL REFERENCES quote (id),
	amount_cents        BIGINT       NOT NULL,
	status              VARCHAR(32)  NOT NULL,
	provider            VARCHAR(32)  NOT NULL,
	provider_payment_id VARCHAR(128),
	status_detail       VARCHAR(255),
	correlation_id      VARCHAR(128) NOT NULL,
	created_at          TIMESTAMPTZ  NOT NULL,
	updated_at          TIMESTAMPTZ  NOT NULL,
	version             BIGINT       NOT NULL,
	CONSTRAINT uk_payment_work_order UNIQUE (work_order_id),
	CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REFUNDED')),
	CONSTRAINT ck_payment_amount CHECK (amount_cents >= 0)
);

-- Consulta do verificador de pagamentos pendentes.
CREATE INDEX ix_payment_status ON payment (status);

-- ---------- Idempotência: messageId já processado, gravado na transação do efeito ----------
CREATE TABLE processed_message (
	message_id   UUID        NOT NULL PRIMARY KEY,
	type         VARCHAR(64) NOT NULL,
	processed_at TIMESTAMPTZ NOT NULL
);
