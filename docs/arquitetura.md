# Arquitetura do Billing Service

Visão do serviço em três diagramas: componentes por camada, participação na saga e modelo de
dados. O contexto geral (papel no sistema, mensagens, configuração) está no [README](../README.md).

## Componentes por camada

As setas cheias são chamadas; as tracejadas ligam um adaptador à porta que ele implementa. O domínio
não depende de framework: aplicação e infraestrutura dependem dele, nunca o contrário.

```mermaid
flowchart TB
    cliente(["Cliente ou atendente<br/>HTTP com JWT"])
    os(["OS Service<br/>orquestrador da saga"])

    subgraph presentation["Apresentação (presentation)"]
        catalogController["CatalogController<br/>/api/catalog/*"]
        workOrderController["WorkOrderBillingController<br/>/api/work-orders/{id}/quote, /payment"]
        infoController["InfoController<br/>/api/info"]
        errorHandler["RestExceptionHandler"]
    end

    subgraph application["Aplicação (application)"]
        catalogQuery["CatalogQueryService"]
        quoteService["QuoteApplicationService<br/>generate, decide, cancel"]
        paymentService["PaymentApplicationService<br/>requestPayment, refund"]
        statusCheck["PaymentStatusCheckService<br/>checkPending"]
        eventPort[["EventPublisher"]]
        tracingPort[["Tracing"]]
    end

    subgraph domain["Domínio (domain)"]
        quote["Quote, QuoteLine, QuotePricing"]
        payment["Payment, TraceContext"]
        catalog["CatalogService, CatalogPart"]
        repoPorts[["QuoteRepository<br/>PaymentRepository<br/>CatalogRepository"]]
        gatewayPort[["PaymentGateway"]]
    end

    subgraph infrastructure["Infraestrutura e adaptadores (infrastructure)"]
        web["CorrelationIdFilter<br/>SecurityConfiguration + JwtConfiguration"]
        listener["BillingCommandListener<br/>billing.commands"]
        handlers["GenerateQuote, RequestPayment,<br/>CancelQuote, RefundPayment handlers"]
        idempotency["IdempotentMessageProcessor<br/>processed_message"]
        codec["EnvelopeCodec"]
        publisher["RabbitEventPublisher<br/>publicação depois do commit"]
        topology["RabbitTopologyConfiguration"]
        scheduler["PaymentStatusScheduler<br/>@Scheduled"]
        jpa["Adaptadores JPA<br/>(RepositoryAdapters)"]
        mercadoPago["MercadoPagoPaymentGateway"]
        simulated["SimulatedPaymentGateway"]
        newRelic["NewRelicTracing<br/>@Trace(dispatcher = true)"]
    end

    postgres[("PostgreSQL billing")]
    rabbit[("RabbitMQ<br/>exchange oficina")]
    mpApi[("Mercado Pago<br/>API de Orders")]
    nrAgent[("Agente Java do New Relic")]

    cliente --> web --> workOrderController
    web --> catalogController
    web --> infoController
    catalogController --> catalogQuery
    workOrderController --> quoteService
    workOrderController --> paymentService

    os -->|"comandos"| rabbit
    rabbit -->|"billing.commands"| listener
    listener --> codec
    listener --> tracingPort
    listener --> idempotency --> handlers
    handlers --> quoteService
    handlers --> paymentService
    scheduler --> statusCheck
    statusCheck --> tracingPort

    catalogQuery --> repoPorts
    quoteService --> quote
    quoteService --> repoPorts
    quoteService --> eventPort
    paymentService --> payment
    paymentService --> repoPorts
    paymentService --> gatewayPort
    paymentService --> eventPort
    statusCheck --> payment
    statusCheck --> gatewayPort
    statusCheck --> eventPort
    quote --> catalog

    jpa -.-> repoPorts
    mercadoPago -.-> gatewayPort
    simulated -.-> gatewayPort
    publisher -.-> eventPort
    newRelic -.-> tracingPort

    jpa --> postgres
    idempotency --> postgres
    mercadoPago --> mpApi
    publisher --> codec
    publisher -->|"event.billing.*"| rabbit
    topology --> rabbit
    newRelic --> nrAgent
    rabbit -->|"os.events"| os
```

O adaptador de pagamento é escolhido por `APP_PAYMENT_GATEWAY` (`mercadopago` no kind,
`simulated` fora do cluster e nos testes).

## Participação na saga

O OS Service orquestra; o Billing responde a comandos com eventos e não conhece os outros
participantes. O diagrama mostra o caminho com aprovação e pagamento pelo Mercado Pago, a recusa
do orçamento e as compensações que o orquestrador pode pedir.

```mermaid
sequenceDiagram
    autonumber
    participant OS as OS Service
    participant MQ as RabbitMQ (oficina)
    participant B as Billing Service
    participant DB as PostgreSQL billing
    participant MP as Mercado Pago
    actor C as Cliente

    OS->>MQ: GenerateQuote (command.billing.generate-quote)
    MQ->>B: billing.commands — transação Message/billing.commands/GenerateQuote
    B->>DB: quote PENDING_APPROVAL + processed_message (mesma transação)
    B-->>MQ: QuoteGenerated, ou QuoteGenerationFailed se houver item fora do catálogo
    MQ-->>OS: os.events

    C->>B: POST /api/work-orders/{id}/quote/decision
    alt orçamento aprovado
        B->>DB: quote APPROVED, com o titular do cartão
        B-->>MQ: QuoteApproved
        OS->>MQ: RequestPayment
        MQ->>B: billing.commands
        B->>MP: POST /v1/card_tokens e POST /v1/orders (X-Idempotency-Key quote-{quoteId})
        B->>DB: payment PENDING + cabeçalhos de trace do RequestPayment
        loop a cada app.payment.status-check-interval
            B->>MP: GET /v1/orders/{id} — transação Job/payment-status-check com o trace guardado
        end
        B->>DB: payment APPROVED ou REJECTED (lock otimista)
        B-->>MQ: PaymentApproved ou PaymentRejected
        MQ-->>OS: os.events
    else orçamento recusado
        B->>DB: quote REJECTED
        B-->>MQ: QuoteRejected
        MQ-->>OS: os.events
    end

    opt falha na execução (estorno)
        OS->>MQ: RefundPayment
        MQ->>B: billing.commands
        B->>MP: POST /v1/orders/{id}/refund
        B->>DB: payment REFUNDED
        B-->>MQ: PaymentRefunded
    end
    opt pagamento recusado, prazo de aprovação vencido ou falha na execução
        OS->>MQ: CancelQuote
        MQ->>B: billing.commands
        B->>DB: quote CANCELLED (já CANCELLED ou REJECTED: sem mudança)
        B-->>MQ: QuoteCancelled
    end
```

Com o adaptador simulado, `APRO` aprova já na criação e o `PaymentApproved` sai no processamento
do `RequestPayment`; no Mercado Pago a criação volta pendente e quem publica o resultado é a
verificação periódica. Toda publicação acontece depois do commit da transação do banco.

## Modelo de dados

Esquema do Flyway (`V1__init.sql`, `V2__quote_cardholder_name.sql`, `V3__payment_trace_context.sql`).
As linhas do orçamento guardam código, nome e preço do catálogo no momento do orçamento, sem chave
estrangeira para o catálogo.

```mermaid
erDiagram
    quote ||--|{ quote_line : "tem"
    quote ||--o| payment : "é cobrado por"

    catalog_service {
        varchar code PK
        varchar name
        bigint price_cents
        integer estimated_minutes
    }
    catalog_part {
        varchar code PK
        varchar name
        bigint price_cents
    }
    quote {
        uuid id PK
        uuid work_order_id UK "id da OS e da saga"
        varchar tracking_code
        varchar customer_name
        varchar customer_email
        varchar correlation_id
        varchar status "PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED"
        bigint services_total_cents
        bigint parts_total_cents
        bigint total_cents
        varchar decision_reason
        varchar cardholder_name "titular informado na aprovação"
        timestamptz created_at
        timestamptz decided_at
        timestamptz updated_at
        bigint version "lock otimista"
    }
    quote_line {
        uuid quote_id PK, FK
        integer line_number PK
        varchar kind "SERVICE ou PART"
        varchar code
        varchar name
        integer quantity
        bigint unit_price_cents
        bigint total_cents
    }
    payment {
        uuid id PK
        uuid work_order_id UK
        uuid quote_id FK
        bigint amount_cents
        varchar status "PENDING, APPROVED, REJECTED, REFUNDED"
        varchar provider "MERCADO_PAGO ou SIMULATED"
        varchar provider_payment_id "id da order no provedor"
        varchar status_detail
        varchar correlation_id
        varchar trace_parent "cabeçalho traceparent do RequestPayment"
        varchar trace_state "cabeçalho tracestate"
        varchar trace_newrelic "cabeçalho newrelic"
        timestamptz created_at
        timestamptz updated_at
        bigint version "lock otimista"
    }
    processed_message {
        uuid message_id PK
        varchar type
        timestamptz processed_at
    }
```
