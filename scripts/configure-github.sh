#!/usr/bin/env bash
# Configura o repositório no GitHub (equivalente em bash do
# scripts/configure-github.ps1 do monólito):
#
#   1. ruleset "soat-protection-main": PR obrigatório, checks do CI
#      obrigatórios, sem force-push e sem deleção da main;
#   2. workflows de PRs de fork só rodam com aprovação (o repositório é
#      público e o deploy usa runner self-hosted);
#   3. secret SONAR_TOKEN, se a variável estiver no ambiente.
#
# Uso: scripts/configure-github.sh [dono/repositorio]
# Requer o GitHub CLI (gh) autenticado com administração no repositório.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_REPO="DevEgF/$(basename "$(cd "$SCRIPT_DIR/.." && pwd)")"
REPO="${1:-$DEFAULT_REPO}"
RULESET_NAME="soat-protection-main"

command -v gh >/dev/null 2>&1 || { echo "erro: 'gh' não encontrado no PATH" >&2; exit 1; }

ruleset_body() {
  cat <<JSON
{
  "name": "$RULESET_NAME",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [],
  "conditions": { "ref_name": { "include": ["refs/heads/main"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "allowed_merge_methods": ["merge"],
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_approving_review_count": 0,
        "required_review_thread_resolution": true
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "do_not_enforce_on_create": false,
        "strict_required_status_checks_policy": true,
        "required_status_checks": [
          { "context": "ci / build" },
          { "context": "ci / image" }
        ]
      }
    }
  ]
}
JSON
}

# O ruleset exige PR para tocar a main; em repositório vazio ele impediria o
# primeiro push, então só é aplicado depois que a main existe.
existing_id="$(gh api "repos/$REPO/rulesets" --jq ".[] | select(.name == \"$RULESET_NAME\") | .id" | head -n 1)"
if ! gh api "repos/$REPO/branches/main" --jq .name >/dev/null 2>&1; then
  echo "a branch main ainda não existe em $REPO; rode de novo depois do primeiro push para criar o ruleset"
elif [ -n "$existing_id" ]; then
  ruleset_body | gh api -X PUT "repos/$REPO/rulesets/$existing_id" --input - >/dev/null
  echo "ruleset $RULESET_NAME atualizado em $REPO"
else
  ruleset_body | gh api -X POST "repos/$REPO/rulesets" --input - >/dev/null
  echo "ruleset $RULESET_NAME criado em $REPO"
fi

gh api -X PUT "repos/$REPO/actions/permissions/fork-pr-contributor-approval" \
  -f approval_policy=all_external_contributors >/dev/null
echo "PRs de fork: workflows exigem aprovação para todo colaborador externo"

if [ -n "${SONAR_TOKEN:-}" ]; then
  printf '%s' "$SONAR_TOKEN" | gh secret set SONAR_TOKEN --repo "$REPO"
  echo "secret SONAR_TOKEN gravado em $REPO"
else
  echo "SONAR_TOKEN não está no ambiente; secret não alterado"
fi
