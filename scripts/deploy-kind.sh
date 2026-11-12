#!/usr/bin/env bash
# Deploy do serviço no cluster kind local. Uma fonte só para o deploy manual e o do pipeline
# (.github/workflows/deploy.yml). Compatível com o bash 3.2 do macOS e com Linux.
#
# Uso: scripts/deploy-kind.sh <build|load|apply|wait|smoke|rollback|diagnose|all>
#
# Variáveis (todas opcionais):
#   IMAGE_TAG        tag da imagem (padrão: SHA curto do git, ou "local" fora de um commit)
#   KIND_CLUSTER     nome do cluster kind (padrão: oficina)
#   KUBE_CONTEXT     contexto do kubectl (padrão: kind-oficina)
#   NAMESPACE        namespace (padrão: oficina)
#   ROLLOUT_TIMEOUT  timeout do rollout status (padrão: 300s)
#   SMOKE_ATTEMPTS   tentativas do smoke test (padrão: 30)
#   SMOKE_INTERVAL   segundos entre tentativas (padrão: 5)
set -euo pipefail

# --- Identidade do serviço (o que muda entre os três repositórios) ---
SERVICE_NAME="billing-service"
IMAGE_NAME="soat-oficina-billing-service"
NODE_PORT="30082"
DATABASE_WORKLOAD="statefulset/billing-postgres"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KIND_CLUSTER="${KIND_CLUSTER:-oficina}"
KUBE_CONTEXT="${KUBE_CONTEXT:-kind-oficina}"
NAMESPACE="${NAMESPACE:-oficina}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"
SMOKE_ATTEMPTS="${SMOKE_ATTEMPTS:-30}"
SMOKE_INTERVAL="${SMOKE_INTERVAL:-5}"
SMOKE_URL="${SMOKE_URL:-http://127.0.0.1:${NODE_PORT}/actuator/health}"

if [ -z "${IMAGE_TAG:-}" ]; then
  IMAGE_TAG="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo local)"
fi
IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"

log() { printf '[deploy-kind] %s\n' "$*"; }
fail() { printf '[deploy-kind] ERRO: %s\n' "$*" >&2; exit 1; }

kube() { kubectl --context "$KUBE_CONTEXT" --namespace "$NAMESPACE" "$@"; }

validate_tag() {
  case "$IMAGE_TAG" in
    latest) fail "a tag 'latest' não é permitida; use o SHA do commit" ;;
  esac
  if ! printf '%s' "$IMAGE_TAG" | grep -Eq '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'; then
    fail "IMAGE_TAG inválida: '$IMAGE_TAG'"
  fi
}

require_context() {
  kubectl config get-contexts "$KUBE_CONTEXT" >/dev/null 2>&1 \
    || fail "contexto '$KUBE_CONTEXT' não existe; suba a plataforma (soat-oficina-infra-k8s/local/up.sh)"
}

cmd_build() {
  validate_tag
  log "docker build $IMAGE"
  docker build -t "$IMAGE" "$ROOT_DIR"
}

cmd_load() {
  validate_tag
  kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER" \
    || fail "cluster kind '$KIND_CLUSTER' não encontrado"
  log "kind load docker-image $IMAGE --name $KIND_CLUSTER"
  kind load docker-image "$IMAGE" --name "$KIND_CLUSTER"
}

cmd_apply() {
  validate_tag
  require_context
  local overlay
  # Overlay temporário: só fixa a tag da imagem sobre a base k8s/ do repositório.
  # Fica dentro do repositório (ignorado pelo git) porque o kustomize não aceita base com
  # caminho absoluto, e calcular caminho relativo de forma portável sem realpath é frágil.
  overlay="$(mktemp -d "$ROOT_DIR/.deploy-overlay.XXXXXX")"
  cat > "$overlay/kustomization.yaml" <<EOF
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../k8s
images:
  - name: ${IMAGE_NAME}
    newTag: "${IMAGE_TAG}"
EOF
  log "kubectl apply -k (imagem $IMAGE)"
  local status=0
  kube apply -k "$overlay" || status=$?
  rm -rf "$overlay"
  return "$status"
}

cmd_wait() {
  require_context
  # "|| return 1" explícito: cmd_all chama esta função dentro de um if, onde o set -e não vale.
  log "aguardando $DATABASE_WORKLOAD"
  kube rollout status "$DATABASE_WORKLOAD" --timeout="$ROLLOUT_TIMEOUT" || return 1
  log "aguardando deployment/$SERVICE_NAME"
  kube rollout status "deployment/$SERVICE_NAME" --timeout="$ROLLOUT_TIMEOUT" || return 1
}

cmd_smoke() {
  local attempt=1 body=""
  log "smoke test em $SMOKE_URL"
  while [ "$attempt" -le "$SMOKE_ATTEMPTS" ]; do
    body="$(curl -fsS --max-time 5 "$SMOKE_URL" 2>/dev/null || true)"
    if printf '%s' "$body" | grep -q '"status":"UP"'; then
      log "health UP na tentativa $attempt: $body"
      return 0
    fi
    log "tentativa $attempt/$SMOKE_ATTEMPTS sem UP; nova tentativa em ${SMOKE_INTERVAL}s"
    attempt=$((attempt + 1))
    sleep "$SMOKE_INTERVAL"
  done
  printf '[deploy-kind] ERRO: health não respondeu UP em %s tentativas (última resposta: %s)\n' \
    "$SMOKE_ATTEMPTS" "${body:-vazia}" >&2
  return 1
}

cmd_rollback() {
  require_context
  if ! kube get "deployment/$SERVICE_NAME" >/dev/null 2>&1; then
    log "deployment/$SERVICE_NAME não existe; nada a desfazer"
    return 0
  fi
  local revisions
  revisions="$(kube rollout history "deployment/$SERVICE_NAME" | awk '$1 ~ /^[0-9]+$/ {n++} END {print n+0}')"
  if [ "$revisions" -lt 2 ]; then
    log "sem revisão anterior (primeiro deploy); nada a desfazer"
    return 0
  fi
  log "kubectl rollout undo deployment/$SERVICE_NAME ($revisions revisões no histórico)"
  kube rollout undo "deployment/$SERVICE_NAME"
  kube rollout status "deployment/$SERVICE_NAME" --timeout="$ROLLOUT_TIMEOUT"
  log "rollback concluído; imagem atual: $(kube get "deployment/$SERVICE_NAME" -o jsonpath='{.spec.template.spec.containers[0].image}')"
}

cmd_diagnose() {
  require_context
  kube get pods,events -o wide || true
  kube describe "deployment/$SERVICE_NAME" || true
  kube logs "deployment/$SERVICE_NAME" --tail=100 --all-containers || true
}

cmd_all() {
  cmd_build
  cmd_load
  cmd_apply
  if cmd_wait && cmd_smoke; then
    log "deploy de $IMAGE concluído"
    return 0
  fi
  log "deploy de $IMAGE falhou; iniciando rollback"
  cmd_rollback || true
  cmd_diagnose
  return 1
}

usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
}

main() {
  local command="${1:-}"
  case "$command" in
    build) cmd_build ;;
    load) cmd_load ;;
    apply) cmd_apply ;;
    wait) cmd_wait ;;
    smoke) cmd_smoke ;;
    rollback) cmd_rollback ;;
    diagnose) cmd_diagnose ;;
    all) cmd_all ;;
    -h | --help | help) usage ;;
    *) usage >&2; exit 2 ;;
  esac
}

main "$@"
