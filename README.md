# soat-oficina-billing-service

Serviço de **Cobrança (Billing)** da oficina mecânica — Tech Challenge SOAT, Fase 4.
É um dos três microsserviços que substituem o monólito `soat-oficina-app`; os outros são
`soat-oficina-os-service` e `soat-oficina-execution-service`. Neste estágio é só o
esqueleto: health, validação de JWT, tratamento de erros, correlação de requisições e
OpenAPI. O domínio (orçamento, pagamento via Mercado Pago, saga) chega nas próximas entregas.

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

## Testes

```bash
./gradlew check
```

Roda os testes unitários e o de integração (contexto completo contra PostgreSQL e RabbitMQ
reais, via Testcontainers; precisa do Docker), gera o JaCoCo em
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
