# soat-oficina-billing-service

[![CI](https://github.com/DevEgF/soat-oficina-billing-service/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/DevEgF/soat-oficina-billing-service/actions/workflows/ci.yml?query=branch%3Adevelop)
[![Cobertura](https://sonarcloud.io/api/project_badges/measure?project=DevEgF_soat-oficina-billing-service&metric=coverage&branch=develop)](https://sonarcloud.io/summary/new_code?id=DevEgF_soat-oficina-billing-service&branch=develop)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=DevEgF_soat-oficina-billing-service&metric=alert_status&branch=develop)](https://sonarcloud.io/summary/new_code?id=DevEgF_soat-oficina-billing-service&branch=develop)

Serviço de **Cobrança (Billing)** da oficina mecânica — Tech Challenge SOAT, Fase 4.

## O que é

Um dos três microsserviços que substituem o monólito `soat-oficina-app`; os outros são
`soat-oficina-os-service` e `soat-oficina-execution-service`. É dono do catálogo com preços,
do orçamento de cada OS, da decisão do cliente e do pagamento.

Na saga orquestrada pelo OS Service por RabbitMQ é um **participante**: recebe comandos na fila
`billing.commands` e responde com eventos. Gera o orçamento, registra a decisão do cliente, cobra
pelo Mercado Pago (ou pelo adaptador simulado) e executa as compensações `CancelQuote` e
`RefundPayment` pedidas pelo orquestrador. Não conhece os outros serviços, só o contrato das
mensagens (veja [Saga](#saga)).

## Arquitetura

Camadas com portas e adaptadores; o domínio não depende de framework:

- **Apresentação** (`presentation`): controllers REST, DTOs e tratamento de erros.
- **Aplicação** (`application`): casos de uso (`QuoteApplicationService`,
  `PaymentApplicationService`, `PaymentStatusCheckService`, `CatalogQueryService`) e as portas de
  publicação (`EventPublisher`) e de observabilidade (`Tracing`).
- **Domínio** (`domain`): agregados `Quote` e `Payment`, precificação (`QuotePricing`), contexto de
  trace (`TraceContext`) e as portas `QuoteRepository`, `PaymentRepository`, `CatalogRepository` e
  `PaymentGateway`.
- **Infraestrutura** (`infrastructure`): adaptadores JPA, consumidor e publicador RabbitMQ
  (idempotência por `messageId`), Mercado Pago e simulado (`PaymentGateway`), agendamento da
  verificação de pagamentos, New Relic (`NewRelicTracing`), segurança JWT e filtro de correlação.

Diagramas (componentes por camada, participação na saga e modelo de dados) em
[docs/arquitetura.md](docs/arquitetura.md).

Stack: Kotlin 2.4.10, Spring Boot 4.1.2, Java 17, Gradle 9.4.0 (wrapper); Spring Web, Security
(OAuth2 Resource Server, JWT HS256), Actuator, springdoc 3.1.0; PostgreSQL 16 (JPA + Flyway) e
RabbitMQ (Spring AMQP); agente Java e API do New Relic 9.4.0; testes com JUnit 5, MockK,
Testcontainers e JaCoCo.

## Como rodar

### Fora do cluster (perfil `local`)

Pré-requisitos: JDK 17, Docker. As dependências (PostgreSQL `billing` na porta 5434 e RabbitMQ
na 5672) vêm do `docker-compose.yml` da plataforma, em `soat-oficina-infra-k8s/local/`:

```bash
docker compose --env-file ../.env.local -f ../soat-oficina-infra-k8s/local/docker-compose.yml up -d rabbitmq postgres-billing
./gradlew bootRun
```

O `bootRun` ativa o perfil `local` (ambiente `hml`, segredo JWT de desenvolvimento e as senhas do
`docker-compose.yml`); variáveis de ambiente continuam valendo por cima. A API sobe em
`http://localhost:8082`, com o adaptador de pagamento simulado.

### No kind

O cluster `oficina` (contexto `kind-oficina`), o namespace `oficina`, o RabbitMQ e o Secret
`billing-service-secrets` vêm de `soat-oficina-infra-k8s/local/` (`up.sh` e `secrets.sh`).

```bash
scripts/deploy-kind.sh all
curl http://localhost:30082/actuator/health/readiness
```

| Subcomando | O que faz |
|---|---|
| `build` | `docker build` de `soat-oficina-billing-service:$IMAGE_TAG` |
| `load` | `kind load docker-image` no cluster `oficina` |
| `apply` | overlay kustomize temporário com a tag e `kubectl apply -k` |
| `wait` | `kubectl rollout status` do banco e do serviço |
| `smoke` | `GET /actuator/health` no NodePort 30082 até `UP` |
| `rollback` | `kubectl rollout undo` + espera (no primeiro deploy só avisa) |
| `diagnose` | pods, eventos, describe e logs |
| `all` | build, load, apply, wait e smoke; se falhar, rollback e diagnóstico |

Variáveis: `IMAGE_TAG` (padrão: SHA curto do git ou `local`), `KIND_CLUSTER=oficina`,
`KUBE_CONTEXT=kind-oficina`, `NAMESPACE=oficina`, `ROLLOUT_TIMEOUT=300s`. A tag padrão só muda
com um commit novo: para reimplantar alterações ainda não commitadas, use outra tag
(`IMAGE_TAG=dev-1 scripts/deploy-kind.sh all`).

No GitHub, o workflow `Deploy` roda o mesmo script no runner self-hosted (`oficina-kind`)
depois de um `CI` verde de push na `main`.

## API

Todas as rotas `/api/**` exigem `Authorization: Bearer <jwt>`: HS256 assinado com SHA-256 do
`APP_JWT_SECRET`, `iss=oficina`, `aud=oficina-api` e `env` igual ao `APP_ENVIRONMENT`. Sem token
ou com token inválido, 401. As rotas de orçamento e pagamento usam o **id da OS**. Erros: 404 (não
encontrado), 409 (transição inválida ou atualização concorrente), 400 (validação). Rotas fora de
`/api/**` e das públicas são negadas.

| Rota | O que faz |
|---|---|
| `GET /api/catalog/services` | serviços do catálogo: `code`, `name`, `priceCents`, `estimatedMinutes` |
| `GET /api/catalog/parts` | peças do catálogo: `code`, `name`, `priceCents` |
| `GET /api/work-orders/{workOrderId}/quote` | orçamento com linhas e totais (404 enquanto não existir) |
| `POST /api/work-orders/{workOrderId}/quote/decision` | corpo `{ "approved": true, "payment": { "cardholderName": "APRO" } }` (`payment` opcional) ou `{ "approved": false, "reason": "..." }`; só a partir de `PENDING_APPROVAL`; publica `QuoteApproved` ou `QuoteRejected` |
| `GET /api/work-orders/{workOrderId}/payment` | pagamento da OS com `status`, `provider`, `providerPaymentId` e `statusDetail` (404 enquanto não existir) |
| `GET /api/info` | nome, ambiente e versão do serviço (prova a validação do JWT) |
| `GET /actuator/health` | público; `/actuator/health/liveness` e `/actuator/health/readiness` (banco e RabbitMQ) |

Swagger em `/swagger-ui.html` e OpenAPI em `/v3/api-docs` (públicos). A coleção Postman da
plataforma fica em
[soat-oficina-infra-k8s/postman](https://github.com/DevEgF/soat-oficina-infra-k8s/tree/develop/postman).

Na aprovação, `payment.cardholderName` fica guardado com o orçamento e é usado quando o
`RequestPayment` chegar; sem `payment`, vale `app.payment.default-cardholder-name` (`APRO`). É o
mecanismo do ambiente de teste do Mercado Pago, em que o nome do titular do cartão de teste define
o resultado (veja [Mercado Pago](#mercado-pago)). Em produção o frontend enviaria o token do cartão
criado pelo SDK do provedor, e o serviço não receberia nem guardaria dado de cartão.

## Banco

PostgreSQL exclusivo do serviço (`billing`; no kind, o StatefulSet `billing-postgres`). O esquema é
do Flyway (`src/main/resources/db/migration`) e o Hibernate só o valida; migration aplicada não
muda, alteração de esquema entra em uma migration nova. Dinheiro sempre em centavos.

| Tabela | O que guarda |
|---|---|
| `catalog_service` | serviços com preço e tempo estimado; entram só por migration (`V1__init.sql`) |
| `catalog_part` | peças com preço; entram só por migration |
| `quote` | um orçamento por OS (`work_order_id` único): cliente, `correlation_id` da saga, status, totais de serviços, peças e geral, motivo da decisão ou do cancelamento, titular informado na aprovação (`V2`) e versão para lock otimista |
| `quote_line` | linhas do orçamento com nome e preço do catálogo congelados no momento do orçamento |
| `payment` | um pagamento por OS: valor, status, provedor, id no provedor (order do Mercado Pago), detalhe do provedor, `correlation_id`, cabeçalhos de trace do `RequestPayment` (`trace_parent`, `trace_state`, `trace_newrelic`, `V3`) e versão |
| `processed_message` | `messageId` e tipo de cada mensagem já processada, gravados na transação do efeito (idempotência) |

O catálogo tem cinco serviços e cinco peças, com códigos e preços fixados no contrato entre os
serviços. Orçamento: `PENDING_APPROVAL`, `APPROVED`, `REJECTED`, `CANCELLED`; os totais são a soma
de quantidade × preço unitário por tipo de linha, como no `WorkOrder.recalculateQuote` do
monólito. Pagamento: `PENDING`, `APPROVED`, `REJECTED`, `REFUNDED`.

## Mensagens

Exchange `oficina` (topic) e `oficina.dlx` (direct). Na subida o serviço declara os dois
exchanges, a fila que consome (`billing.commands`, binding `command.billing.#`) e a fila para
onde publica (`os.events`, binding `event.#`), cada uma com DLQ `<fila>.dlq` ligada ao
`oficina.dlx`. Envelope JSON: `messageId`, `sagaId` (id da OS), `type`, `occurredAt`,
`correlationId`, `payload`. Propriedades AMQP: `type` e `message_id` iguais aos do envelope,
cabeçalho `x-correlation-id`, JSON persistente; os cabeçalhos de trace (`traceparent`,
`tracestate`, `newrelic`) vêm do agente do New Relic (veja [Observabilidade](#observabilidade)).

| Consome (`billing.commands`) | Routing key | Efeito | Publica |
|---|---|---|---|
| `GenerateQuote` | `command.billing.generate-quote` | calcula pelo catálogo e grava o orçamento `PENDING_APPROVAL` | `QuoteGenerated`; item fora do catálogo: `QuoteGenerationFailed`, sem gravar |
| `RequestPayment` | `command.billing.request-payment` | cria o pagamento pela porta `PaymentGateway` com o titular guardado na aprovação e guarda os cabeçalhos de trace da mensagem | `PaymentApproved` ou `PaymentRejected`; `PENDING` só grava |
| `CancelQuote` | `command.billing.cancel-quote` | orçamento `PENDING_APPROVAL` ou `APPROVED` vai a `CANCELLED` | `QuoteCancelled`; já `CANCELLED` ou `REJECTED`: publica de novo sem mudar estado; sem orçamento: WARN e ack |
| `RefundPayment` | `command.billing.refund-payment` | pagamento `APPROVED` é estornado no provedor e vai a `REFUNDED` | `PaymentRefunded`; já `REFUNDED`: publica de novo; outro status ou sem pagamento: WARN e ack |

| Publica (exchange `oficina`, lido pelo OS Service em `os.events`) | Routing key | Quando |
|---|---|---|
| `QuoteGenerated` | `event.billing.quote-generated` | orçamento gravado |
| `QuoteGenerationFailed` | `event.billing.quote-generation-failed` | item fora do catálogo |
| `QuoteApproved` | `event.billing.quote-approved` | decisão por HTTP aprovando |
| `QuoteRejected` | `event.billing.quote-rejected` | decisão por HTTP recusando |
| `QuoteCancelled` | `event.billing.quote-cancelled` | `CancelQuote` |
| `PaymentApproved` | `event.billing.payment-approved` | pagamento aprovado na criação ou pela verificação periódica |
| `PaymentRejected` | `event.billing.payment-rejected` | pagamento recusado (valor diferente do orçamento, na criação ou pela verificação periódica) |
| `PaymentRefunded` | `event.billing.payment-refunded` | `RefundPayment` |

Regras:

- **Idempotência:** o `messageId` vai para `processed_message` na mesma transação do efeito;
  repetido é ignorado com ack.
- **Publicação depois do commit**, com o `correlationId` recebido no `GenerateQuote` (guardado
  no orçamento e no pagamento, inclusive nos eventos disparados por HTTP).
- **Erros:** 3 tentativas (500 ms, depois 1 s) e rejeição sem requeue, que leva à DLQ. JSON
  inválido, campo obrigatório ausente ou `type` fora do contrato vão direto para a DLQ.
- **Fora de ordem:** comando que não se aplica ao estado atual (pagamento de orçamento não
  aprovado, segundo `GenerateQuote` da mesma OS, estorno de pagamento não aprovado) é registrado
  em WARN e descartado com ack.
- **Confirmação repetida:** `CancelQuote` e `RefundPayment` repetidos (outro `messageId`)
  publicam a confirmação de novo, porque o orquestrador espera por ela para concluir a
  compensação. Falha do provedor no estorno lança exceção: retry e DLQ, e a saga fica visível em
  `COMPENSATING`.
- **Pagamento:** a porta `PaymentGateway` (`create`, `getStatus`, `refund`) segue a API de
  Orders do Mercado Pago, em que a criação volta pendente. A chave de idempotência é
  `quote-<quoteId>`, para uma reentrega não cobrar duas vezes. Valor diferente do total do
  orçamento é recusado sem chamar o provedor.
- **Verificação periódica:** a cada `app.payment.status-check-interval` (padrão `PT10S`) o
  serviço consulta no provedor os pagamentos `PENDING`; resolvido, grava `APPROVED` ou `REJECTED`
  e publica o evento com o `correlationId` da saga. Erro em um pagamento não impede os demais.
- **Fronteira de transação:** nos comandos (`RequestPayment`, `RefundPayment`) a chamada ao
  provedor fica dentro da transação da mensagem, para o efeito e o `processed_message` gravarem
  juntos; o listener consome uma mensagem por vez e o cliente HTTP tem timeouts de conexão (5 s)
  e leitura (20 s), o que limita o tempo com a conexão do banco presa. A verificação periódica
  consulta o provedor fora de transação e abre uma transação curta só para gravar o pagamento
  resolvido (com lock otimista).

## Mercado Pago

Adaptador `mercadopago` da porta `PaymentGateway`, ligado por `APP_PAYMENT_GATEWAY=mercadopago`
(é o valor do ConfigMap do kind). Usa o Checkout Transparente pela **API de Orders**, em **modo de
teste**, com a conta de teste do projeto:

1. `POST /v1/card_tokens?public_key=...` gera o token (uso único) do cartão de teste configurado,
   com o titular guardado na aprovação;
2. `POST /v1/orders` (cabeçalhos `Authorization` e `X-Idempotency-Key: quote-<quoteId>`) cria a
   order; o pagamento fica sempre `PENDING`, mesmo quando a order já volta resolvida (inclusive
   recusada, que vem como HTTP 402 com a order `failed` no campo `data`), e quem publica o resultado é a
   verificação periódica;
3. `GET /v1/orders/{id}` é a consulta da verificação periódica;
4. `POST /v1/orders/{id}/refund` estorna o valor total (só para order `processed`).

A criação é assíncrona: nas medições a order voltou `processing` e levou em média **75 segundos**
para se resolver; em outras execuções ela já voltou resolvida na criação, e a primeira consulta
(até 10 s depois) publica o resultado. O resultado vem pela **consulta de status, e não por
webhook**, porque o ambiente local (kind) não tem URL pública para o Mercado Pago chamar.

| Titular (`cardholderName`) | Resultado no Mercado Pago | Pagamento no Billing |
|---|---|---|
| `APRO` | order `processed`, pagamento `accredited` | `APPROVED` → `PaymentApproved` |
| `OTHE` | order `failed`, pagamento `rejected_by_issuer` | `REJECTED` → `PaymentRejected` (motivo `rejected_by_issuer`) |
| `CONT` | order continua `processing` | continua `PENDING` |

Mapeamento do `status` da order: `processed` → `APPROVED`; `failed`, `canceled` ou `expired` →
`REJECTED` (motivo: `status_detail` do pagamento); `refunded` → `REFUNDED`; qualquer outro →
`PENDING`. Repetir a criação com a mesma chave e outro token responde 409
`idempotency_key_already_used` sem devolver a order original: é tratado como erro (retry e DLQ),
nunca como sucesso. O pagador é `MERCADOPAGO_PAYER_EMAIL` (usuário de teste `...@testuser.com`)
ou, sem ele, o e-mail do cliente.

Com `mercadopago`, credencial ou dado de cartão ausente impede a subida, com a lista das variáveis
que faltam. Token, credenciais e dados do cartão não aparecem em log nem em mensagem de erro. Os
testes automatizados usam um servidor HTTP falso (`MockRestServiceServer`) com dados evidentemente
fictícios; nenhum teste chama a API real.

O adaptador **simulado** (`APP_PAYMENT_GATEWAY=simulated`, padrão fora do cluster e nos testes)
segue a mesma convenção de titular: `APRO` aprova na hora, `CONT` fica `PENDING` e qualquer outro
nome é recusado com o nome como motivo.

## Configuração

| Variável | Origem no cluster | Padrão fora do cluster |
|---|---|---|
| `SERVER_PORT` | ConfigMap (`8080`) | `8082` |
| `APP_ENVIRONMENT` | ConfigMap (`hml`) | obrigatório (perfil `local`: `hml`) |
| `APP_JWT_SECRET` | Secret | obrigatório (perfil `local`: segredo de desenvolvimento) |
| `SPRING_DATASOURCE_URL` | ConfigMap (`jdbc:postgresql://billing-postgres:5432/billing`) | `jdbc:postgresql://localhost:5434/billing` |
| `SPRING_DATASOURCE_USERNAME` | ConfigMap (`billing`) | `billing` |
| `SPRING_DATASOURCE_PASSWORD` | Secret | vazio (perfil `local`: a do `docker-compose.yml`) |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | ConfigMap (`4`) | `5` |
| `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` | ConfigMap (`rabbitmq` / `5672` / `oficina`) | `localhost` / `5672` / `oficina` |
| `SPRING_RABBITMQ_PASSWORD` | Secret | vazio (perfil `local`: a do `docker-compose.yml`) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | ConfigMap (`logstash`) | — (texto) |
| `NEW_RELIC_APP_NAME` e demais `NEW_RELIC_*` | ConfigMap (`oficina-billing-service`; trace distribuído, encaminhamento de logs com MDC, atributos excluídos) | — |
| `NEW_RELIC_LICENSE_KEY` | Secret (vazio desliga o agente) | — |
| `APP_PAYMENT_GATEWAY` | ConfigMap (`mercadopago`) | `simulated`; valor fora de `simulated`/`mercadopago` impede a subida |
| `APP_PAYMENT_STATUS_CHECK_INTERVAL` | ConfigMap (`PT10S`) | `PT10S` (duração ISO-8601) |
| `APP_PAYMENT_DEFAULT_CARDHOLDER_NAME` | — | `APRO` (titular quando a aprovação não envia `payment`) |
| `MERCADOPAGO_BASE_URL` | — | `https://api.mercadopago.com` |
| `MERCADOPAGO_PAYER_EMAIL` | ConfigMap (`test_user_1378758821@testuser.com`) | vazio (usa o e-mail do cliente) |
| `MERCADOPAGO_ACCESS_TOKEN`, `MERCADOPAGO_PUBLIC_KEY` | Secret | vazio; exigidas com `mercadopago` |
| `MERCADOPAGO_TEST_CARD_NUMBER`, `_SECURITY_CODE`, `_EXPIRATION_MONTH`, `_EXPIRATION_YEAR`, `_HOLDER_DOCUMENT` | Secret | vazio; exigidas com `mercadopago` |

### Contrato do Secret `billing-service-secrets`

| Chave | Uso |
|---|---|
| `APP_JWT_SECRET` | segredo HS256 compartilhado entre os serviços (mínimo 32 bytes) |
| `SPRING_DATASOURCE_PASSWORD` | senha da aplicação **e** do PostgreSQL `billing-postgres` |
| `SPRING_RABBITMQ_PASSWORD` | senha do usuário `oficina` no RabbitMQ |
| `NEW_RELIC_LICENSE_KEY` | license key do New Relic; vazio desliga o agente |
| `MERCADOPAGO_ACCESS_TOKEN` | access token da conta de teste do Mercado Pago |
| `MERCADOPAGO_PUBLIC_KEY` | public key da conta de teste (gera o token do cartão) |
| `MERCADOPAGO_TEST_CARD_NUMBER`, `MERCADOPAGO_TEST_CARD_SECURITY_CODE` | cartão de teste do provedor |
| `MERCADOPAGO_TEST_CARD_EXPIRATION_MONTH`, `MERCADOPAGO_TEST_CARD_EXPIRATION_YEAR` | validade do cartão de teste (números) |
| `MERCADOPAGO_TEST_CARD_HOLDER_DOCUMENT` | CPF do titular do cartão de teste |

No Deployment as chaves `MERCADOPAGO_*` são opcionais: se faltar alguma com `mercadopago`, o
serviço não sobe e o log nomeia a variável ausente. Modelo só com placeholders em
`k8s/secret.example.yaml` (fora do `kustomization.yaml`).

## Qualidade

**Testes e cobertura.** `./gradlew check` roda os testes unitários (domínio, serviços de
aplicação e handlers com mocks, envelope, adaptador do New Relic, adaptador do Mercado Pago contra
servidor HTTP falso) e os de integração (contexto completo contra PostgreSQL e RabbitMQ reais, via
Testcontainers; precisa do Docker). Os testes de integração fazem o papel do OS Service: publicam
os comandos no exchange `oficina`, leem os eventos de uma fila de teste, decidem o orçamento por
HTTP e cobrem mensagem repetida, retry, DLQ, as compensações, a verificação periódica (com
intervalo de 1 s) e a gravação dos cabeçalhos de trace do `RequestPayment`. O JaCoCo gera
`build/reports/jacoco/test/` (XML e HTML) e o `check` **falha se a cobertura de linhas do serviço
inteiro ficar abaixo de 80%**. Só a classe principal fica fora da conta (a mesma exclusão vai para
o Sonar).

**CI e Sonar.** O workflow `CI` roda em pull request e em push na `main` e na `develop`. O job
`ci / build` roda `./gradlew check` e, quando o secret `SONAR_TOKEN` existe, a análise no
SonarQube Cloud (`DevEgF_soat-oficina-billing-service`, organização `devegf-soat-oficina`) com
`-Dsonar.qualitygate.wait=true`, usando o relatório XML do JaCoCo para a cobertura; o job
`ci / image` faz o build da imagem, sem push. A análise automática do SonarQube Cloud não se
aplica: o projeto não está vinculado ao GitHub, e a análise que vale é a do CI. Os badges do topo
mostram o CI, a cobertura e o quality gate da `develop`.

**Verificação de dependências.** `gradle/verification-metadata.xml` tem o SHA-256 de todos os
artefatos que o build resolve (plugins, BOMs, Kotlin, JaCoCo, Testcontainers, API do New Relic e
demais dependências de `check`, `bootJar` e `sonarResolver`), sem verificação de assinatura. O
Gradle interrompe o build se um artefato faltar no arquivo ou vier com outro checksum, inclusive
no `docker build`. Quando uma dependência ou plugin mudar, atualize o arquivo, confira e versione
junto com a mudança:

```bash
./gradlew --no-daemon --refresh-dependencies --write-verification-metadata sha256 check bootJar sonarResolver
./gradlew --no-daemon --refresh-dependencies check bootJar sonarResolver
docker build -t soat-oficina-billing-service:verificacao .
```

O `--refresh-dependencies` na geração registra também os metadados (`.module` e `.pom`) que um
cache vazio baixa, como no CI. No diff do arquivo devem aparecer só os artefatos da mudança.

**Proteção da `main`.** `scripts/configure-github.sh` aplica, via GitHub CLI, o ruleset
`soat-protection-main` (pull request obrigatório, checks `ci / build` e `ci / image`
obrigatórios, sem force-push e sem deleção), exige aprovação para workflows de PRs de fork e grava
o secret `SONAR_TOKEN` quando a variável está no ambiente. O deploy (`deploy / kind`) só roda
depois de um CI verde de push na `main`, em runner self-hosted registrado por `local/runners.sh`
do repositório `soat-oficina-infra-k8s`.

## Observabilidade

**Aplicação no New Relic:** `oficina-billing-service` (rótulos `environment:hml` e
`service:billing-service`). O agente Java 9.4.0 vai na imagem e só liga com
`NEW_RELIC_LICENSE_KEY`; a configuração vem das variáveis `NEW_RELIC_*` do ConfigMap. O código usa
a API do New Relic (`newrelic-api`, mesma versão) só no adaptador
`infrastructure/observability/NewRelicTracing`, atrás da porta `application/observability/Tracing`;
sem o agente a API não faz nada.

**Trace distribuído pelo RabbitMQ.** O agente coloca `traceparent`, `tracestate` e `newrelic` nos
cabeçalhos AMQP das mensagens publicadas dentro de uma transação. As requisições HTTP já são
transações (a decisão do cliente publica `QuoteApproved` ou `QuoteRejected` com o trace dela).
Para o trace não se quebrar no consumo:

- cada comando de `billing.commands` é processado na transação `Message/billing.commands/<type>`
  (`@Trace(dispatcher = true)`), que antes de tudo aceita os cabeçalhos de trace da mensagem
  (`acceptDistributedTraceHeaders`, transporte AMQP) e recebe os atributos `sagaId`,
  `messageType`, `messageId` e `correlationId`; os eventos publicados ali (`QuoteGenerated`,
  `QuoteCancelled`, `PaymentRefunded`...) continuam o trace do orquestrador;
- o pagamento guarda os cabeçalhos de trace do `RequestPayment`. A verificação periódica verifica
  cada pagamento pendente na transação `Job/payment-status-check` (atributos `sagaId` e
  `paymentId`), que aceita os cabeçalhos guardados antes de consultar o provedor; assim o
  `PaymentApproved` ou `PaymentRejected`, publicado até minutos depois, segue no trace que começou
  na decisão do cliente e chega ao Execution.

**Logs.** No cluster os logs saem em JSON (logstash) no stdout, e o agente os encaminha ao New
Relic com o MDC como atributos (`NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED` e
`NEW_RELIC_APPLICATION_LOGGING_FORWARDING_CONTEXT_DATA_ENABLED`). Chaves do MDC: nas requisições
HTTP, `requestId` (cabeçalho `X-Correlation-Id`), `environment` e `service`; no processamento de
uma mensagem, `requestId` (o `correlationId` da saga), `sagaId`, `messageType` e `messageId`,
limpos ao terminar; na verificação periódica, `requestId` e `sagaId`. Cabeçalhos, parâmetros de
requisição e SQL não vão para o New Relic (`NEW_RELIC_ATTRIBUTES_EXCLUDE`,
`NEW_RELIC_TRANSACTION_TRACER_RECORD_SQL=off`), e token, credenciais e dados de cartão não
aparecem em log. Os eventos personalizados da saga (`SagaTransition`, `SagaFinished`) são do OS
Service.

## Saga

O Billing é participante da saga orquestrada pelo OS Service: gera o orçamento (`GenerateQuote` →
`QuoteGenerated` ou `QuoteGenerationFailed`), registra a decisão do cliente por HTTP
(`QuoteApproved` ou `QuoteRejected`) e cobra (`RequestPayment` → `PaymentApproved` ou
`PaymentRejected`, direto ou pela verificação periódica). As compensações que executa, a pedido do
orquestrador: `CancelQuote` (prazo de aprovação vencido, pagamento recusado ou falha na execução;
orçamento `CANCELLED` → `QuoteCancelled`) e `RefundPayment` (falha na execução; estorno no
Mercado Pago, pagamento `REFUNDED` → `PaymentRefunded`), ambas confirmadas de novo quando
repetidas. Escolha de orquestração, passos e compensações da saga inteira:
[README do OS Service](https://github.com/DevEgF/soat-oficina-os-service/tree/develop#saga).
