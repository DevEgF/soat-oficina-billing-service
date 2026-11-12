# soat-oficina-billing-service

Serviço de **Cobrança (Billing)** da oficina mecânica — Tech Challenge SOAT, Fase 4.
É um dos três microsserviços que substituem o monólito `soat-oficina-app`; os outros são
`soat-oficina-os-service` e `soat-oficina-execution-service`. É dono do catálogo com preços,
do orçamento de cada OS, da decisão do cliente e do pagamento, e participa da saga orquestrada
pelo OS Service por RabbitMQ. Hoje está o caminho feliz com pagamento simulado; as compensações
(`CancelQuote`, `RefundPayment`) e o adaptador do Mercado Pago entram na próxima entrega.

## Stack

- Kotlin 2.4.10, Spring Boot 4.1.1, Java 17, Gradle 9.4.0 (wrapper)
- Spring Web, Security (OAuth2 Resource Server, JWT HS256), Actuator, springdoc 3.1.0
- PostgreSQL 16 (JPA + Flyway) e RabbitMQ (Spring AMQP)
- Testes: JUnit 5, MockK, Testcontainers (PostgreSQL e RabbitMQ), JaCoCo (mínimo de 80% de linhas)
- Observabilidade: agente Java do New Relic na imagem, logs estruturados (logstash) no cluster

## Rodar local

Pré-requisitos: JDK 17, Docker. As dependências (PostgreSQL `billing` na porta 5434 e RabbitMQ
na 5672) vêm do `docker-compose.yml` da plataforma, em `soat-oficina-infra-k8s/local/`:

```bash
docker compose --env-file ../.env.local -f ../soat-oficina-infra-k8s/local/docker-compose.yml up -d rabbitmq postgres-billing
./gradlew bootRun
```

O `bootRun` ativa o perfil `local` (ambiente `hml` e um segredo JWT de desenvolvimento);
variáveis de ambiente continuam valendo por cima. A API sobe em `http://localhost:8082`:

- `GET /actuator/health` (público; `/actuator/health/liveness` e `/actuator/health/readiness`)
- `GET /api/info` (exige `Authorization: Bearer <jwt>`)
- `GET /swagger-ui.html` e `GET /v3/api-docs` (públicos)

O token é HS256 assinado com SHA-256 do `APP_JWT_SECRET`, com `iss=oficina`,
`aud=oficina-api` e `env` igual ao `APP_ENVIRONMENT`. Rotas fora de `/api/**` e das públicas
são negadas.

## API

Todas as rotas `/api/**` exigem `Authorization: Bearer <jwt>`. As rotas de orçamento e pagamento
usam o **id da OS**. Erros: 404 (não encontrado), 409 (transição inválida ou atualização
concorrente), 400 (validação).

| Rota | O que faz |
|---|---|
| `GET /api/catalog/services` | serviços do catálogo: `code`, `name`, `priceCents`, `estimatedMinutes` |
| `GET /api/catalog/parts` | peças do catálogo: `code`, `name`, `priceCents` |
| `GET /api/work-orders/{workOrderId}/quote` | orçamento com linhas e totais (404 enquanto não existir) |
| `POST /api/work-orders/{workOrderId}/quote/decision` | corpo `{ "approved": true }` (ou `false` com `reason` opcional); só a partir de `PENDING_APPROVAL` |
| `GET /api/work-orders/{workOrderId}/payment` | pagamento da OS (404 enquanto não existir) |

O catálogo entra pela migration `V1__init.sql` (cinco serviços e cinco peças, códigos e preços
fixados no contrato entre os serviços). Orçamento: `PENDING_APPROVAL`, `APPROVED`, `REJECTED`,
`CANCELLED`; os totais são a soma de quantidade × preço unitário por tipo de linha, como no
`WorkOrder.recalculateQuote` do monólito. Pagamento: `PENDING`, `APPROVED`, `REJECTED`, `REFUNDED`.

## Mensagens

Exchange `oficina` (topic) e `oficina.dlx` (direct). Na subida o serviço declara os dois
exchanges, a fila que consome (`billing.commands`, binding `command.billing.#`) e a fila para
onde publica (`os.events`, binding `event.#`), cada uma com DLQ `<fila>.dlq` ligada ao
`oficina.dlx`. Envelope JSON: `messageId`, `sagaId` (id da OS), `type`, `occurredAt`,
`correlationId`, `payload`.

| Consome (`billing.commands`) | Efeito | Publica |
|---|---|---|
| `GenerateQuote` | calcula pelo catálogo e grava o orçamento `PENDING_APPROVAL` | `QuoteGenerated`; item fora do catálogo: `QuoteGenerationFailed`, sem gravar |
| `RequestPayment` | cria o pagamento pela porta `PaymentGateway` | `PaymentApproved` ou `PaymentRejected`; `PENDING` só grava |
| `CancelQuote`, `RefundPayment` | ainda não tratados: log WARN e ack | — |

A decisão por HTTP publica `QuoteApproved` ou `QuoteRejected`. Regras:

- **Idempotência:** o `messageId` vai para `processed_message` na mesma transação do efeito;
  repetido é ignorado com ack.
- **Publicação depois do commit**, com o `correlationId` recebido no `GenerateQuote` (guardado
  no orçamento e no pagamento, inclusive nos eventos disparados por HTTP).
- **Erros:** 3 tentativas (500 ms, depois 1 s) e rejeição sem requeue, que leva à DLQ. JSON
  inválido, campo obrigatório ausente ou `type` fora do contrato vão direto para a DLQ.
- **Fora de ordem:** comando que não se aplica ao estado atual (pagamento de orçamento não
  aprovado, segundo `GenerateQuote` da mesma OS) é registrado em WARN e descartado com ack.
- **Pagamento:** a porta `PaymentGateway` (`create`, `getStatus`, `refund`) segue a API de
  Orders do Mercado Pago, em que a criação pode voltar pendente. A chave de idempotência é
  `quote-<quoteId>`, para uma reentrega não cobrar duas vezes. Valor diferente do total do
  orçamento é recusado sem chamar o provedor.

## Testes

```bash
./gradlew check
```

Roda os testes unitários (domínio, serviços de aplicação e handlers com mocks, envelope) e os de
integração (contexto completo contra PostgreSQL e RabbitMQ reais, via Testcontainers; precisa do
Docker). O teste da saga faz o papel do OS Service: publica os comandos no exchange `oficina`,
lê os eventos de uma fila de teste, decide o orçamento por HTTP e cobre mensagem repetida,
retry e DLQ. Gera o JaCoCo em
`build/reports/jacoco/test/` (XML e HTML) e falha se a cobertura de linhas do serviço inteiro
ficar abaixo de 80%. Só a classe principal fica fora da conta (a mesma exclusão vai para o Sonar).

## Deploy no kind

O cluster `oficina` (contexto `kind-oficina`), o namespace `oficina`, o RabbitMQ e o Secret
`billing-service-secrets` vêm de `soat-oficina-infra-k8s/local/` (`up.sh` e `secrets.sh`).

```bash
scripts/deploy-kind.sh all
curl http://localhost:30082/actuator/health
```

Subcomandos de `scripts/deploy-kind.sh`:

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
`KUBE_CONTEXT=kind-oficina`, `NAMESPACE=oficina`, `ROLLOUT_TIMEOUT=300s`.

No GitHub, o workflow `Deploy` roda o mesmo script no runner self-hosted (`oficina-kind`)
depois de um `CI` verde de push na `main`.

## Configuração

| Variável | Origem no cluster | Padrão fora do cluster |
|---|---|---|
| `SERVER_PORT` | ConfigMap (`8080`) | `8082` |
| `APP_ENVIRONMENT` | ConfigMap (`hml`) | obrigatório (perfil `local`: `hml`) |
| `APP_JWT_SECRET` | Secret | obrigatório (perfil `local`: segredo de desenvolvimento) |
| `SPRING_DATASOURCE_URL` | ConfigMap (`jdbc:postgresql://billing-postgres:5432/billing`) | `jdbc:postgresql://localhost:5434/billing` |
| `SPRING_DATASOURCE_USERNAME` | ConfigMap (`billing`) | `billing` |
| `SPRING_DATASOURCE_PASSWORD` | Secret | `oficina` |
| `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` | ConfigMap (`rabbitmq` / `5672` / `oficina`) | `localhost` / `5672` / `oficina` |
| `SPRING_RABBITMQ_PASSWORD` | Secret | `oficina` |
| `NEW_RELIC_APP_NAME` e demais `NEW_RELIC_*` | ConfigMap (`oficina-billing-service`) | — |
| `NEW_RELIC_LICENSE_KEY` | Secret (vazio desliga o agente) | — |
| `APP_PAYMENT_GATEWAY` | ConfigMap (`simulated`) | `simulated`; outro valor impede a subida (só existe o simulado) |
| `MERCADOPAGO_ACCESS_TOKEN` | Secret (opcional) | vazio; propriedade `app.mercadopago.access-token` |

### Contrato do Secret `billing-service-secrets`

| Chave | Uso |
|---|---|
| `APP_JWT_SECRET` | segredo HS256 compartilhado entre os serviços (mínimo 32 bytes) |
| `SPRING_DATASOURCE_PASSWORD` | senha da aplicação **e** do PostgreSQL `billing-postgres` |
| `SPRING_RABBITMQ_PASSWORD` | senha do usuário `oficina` no RabbitMQ |
| `NEW_RELIC_LICENSE_KEY` | license key do New Relic; vazio desliga o agente |
| `MERCADOPAGO_ACCESS_TOKEN` | access token do Mercado Pago (opcional; ainda sem uso no código, a integração vem em outra entrega) |

Modelo só com placeholders em `k8s/secret.example.yaml` (fora do `kustomization.yaml`).

## Qualidade

O job `ci / build` roda `./gradlew check` e, quando o secret `SONAR_TOKEN` existe, a análise
no SonarCloud (`DevEgF_soat-oficina-billing-service`, organização `devegf-soat-oficina`) com
`-Dsonar.qualitygate.wait=true`. O job `ci / image` faz o build da imagem, sem push.

## Proteção da `main`

`scripts/configure-github.sh` aplica, via GitHub CLI, o ruleset `soat-protection-main`
(pull request obrigatório, checks `ci / build` e `ci / image` obrigatórios, sem force-push e
sem deleção), exige aprovação para workflows de PRs de fork e grava o secret `SONAR_TOKEN`
quando a variável está no ambiente. O deploy (`deploy / kind`) só roda depois de um CI verde
de push na `main`, em runner self-hosted registrado por `local/runners.sh` do repositório
`soat-oficina-infra-k8s`.
