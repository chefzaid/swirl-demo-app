#!/usr/bin/env bash
set -euo pipefail
umask 077

APP_NAME=swirl-demo-app
GITLAB_PROJECT_PATH="${GITLAB_PROJECT_PATH:-swirl/swirl-demo-app}"
GITLAB_NAMESPACE="${GITLAB_NAMESPACE:-${GITLAB_PROJECT_PATH%/*}}"
GITLAB_URL="${GITLAB_URL:-}"
GITLAB_REGISTRY_HOST="${GITLAB_REGISTRY_HOST:-registry.swirlit.dev}"
REGISTRY_DEPLOY_TOKEN_NAME="${REGISTRY_DEPLOY_TOKEN_NAME:-$APP_NAME-cluster-pull}"
INFRA_NAMESPACE="${INFRA_NAMESPACE:-infra}"
APP_NAMESPACE="${APP_NAMESPACE:-apps}"
VAULT_POD="${VAULT_POD:-vault-0}"
VAULT_TOKEN_FILE="${VAULT_BOOTSTRAP_TOKEN_FILE:-/var/lib/swirl-cloud/vault-bootstrap-token}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

info() { printf '[INFO] %s\n' "$*"; }
fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }

for command_name in curl git jq kubectl sudo; do
  command -v "$command_name" >/dev/null || fail "$command_name is required"
done

[[ -n "${GITLAB_ADMIN_TOKEN:-}" ]] || \
  fail "Set GITLAB_ADMIN_TOKEN to an API token that can manage $GITLAB_PROJECT_PATH"
sudo test -s "$VAULT_TOKEN_FILE" || fail "Vault bootstrap token is missing from $VAULT_TOKEN_FILE"

if [[ -z "$GITLAB_URL" ]]; then
  gitlab_service_ip="$(kubectl get service gitlab -n "$INFRA_NAMESPACE" -o jsonpath='{.spec.clusterIP}')"
  [[ -n "$gitlab_service_ip" ]] || fail "Unable to resolve the internal GitLab service"
  GITLAB_URL="http://$gitlab_service_ip"
fi

work_dir="$(mktemp -d /tmp/$APP_NAME-gitlab.XXXXXX)"
trap 'rm -r -- "$work_dir"; unset GITLAB_ADMIN_TOKEN vault_token registry_password' EXIT
api_config="$work_dir/curl-api.conf"
printf 'silent\nshow-error\nheader = "PRIVATE-TOKEN: %s"\n' "$GITLAB_ADMIN_TOKEN" > "$api_config"

api_json() {
  local method="$1" path="$2"
  shift 2
  curl --config "$api_config" --fail-with-body --request "$method" \
    "$GITLAB_URL/api/v4/$path" "$@"
}

encoded_project_path="$(jq -rn --arg value "$GITLAB_PROJECT_PATH" '$value|@uri')"
project_status="$(curl --config "$api_config" --request GET \
  --output "$work_dir/project.json" --write-out '%{http_code}' \
  "$GITLAB_URL/api/v4/projects/$encoded_project_path")"
case "$project_status" in
  200)
    info "Using existing GitLab project $GITLAB_PROJECT_PATH"
    ;;
  404)
    api_json GET "namespaces?search=$GITLAB_NAMESPACE&per_page=100" > "$work_dir/namespaces.json"
    namespace_id="$(jq -er --arg path "$GITLAB_NAMESPACE" \
      '.[] | select(.full_path==$path) | .id' "$work_dir/namespaces.json" | head -n 1)"
    info "Creating GitLab project $GITLAB_PROJECT_PATH"
    jq -n --arg name "$APP_NAME" --arg path "$APP_NAME" --argjson namespace_id "$namespace_id" \
      '{name:$name,path:$path,namespace_id:$namespace_id,visibility:"public",container_registry_access_level:"private",package_registry_access_level:"private",builds_access_level:"enabled",ci_push_repository_for_job_token_allowed:true}' \
      > "$work_dir/create-project.json"
    api_json POST projects --header 'Content-Type: application/json' \
      --data-binary "@$work_dir/create-project.json" > "$work_dir/project.json"
    ;;
  *)
    cat "$work_dir/project.json" >&2
    fail "GitLab returned HTTP $project_status while looking up $GITLAB_PROJECT_PATH"
    ;;
esac

project_id="$(jq -er '.id' "$work_dir/project.json")"
current_visibility="$(jq -er '.visibility' "$work_dir/project.json")"
jq -n --arg current_visibility "$current_visibility" \
  '{container_registry_access_level:"private",package_registry_access_level:"private",builds_access_level:"enabled",ci_push_repository_for_job_token_allowed:true,shared_runners_enabled:true}
   + if $current_visibility == "public" then {} else {visibility:"public"} end' \
  > "$work_dir/update-project.json"
api_json PUT "projects/$project_id" --header 'Content-Type: application/json' \
  --data-binary "@$work_dir/update-project.json" >/dev/null

api_json GET "projects/$project_id/runners?type=instance_type&per_page=100" > "$work_dir/runners.json"
jq -e '.[] | select(.description == "swirl-cloud-kubernetes")' \
  "$work_dir/runners.json" >/dev/null || \
  fail "The swirl-cloud instance runner is not enabled for $GITLAB_PROJECT_PATH"

vault_token="$(sudo cat "$VAULT_TOKEN_FILE")"
registry_username="$({ printf '%s\n' "$vault_token"; } | \
  kubectl exec -i -n "$INFRA_NAMESPACE" "$VAULT_POD" -- sh -ceu "
    IFS= read -r VAULT_TOKEN
    export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200
    vault kv get -field=username secret/apps/$APP_NAME/registry 2>/dev/null || true
  ")"
registry_password="$({ printf '%s\n' "$vault_token"; } | \
  kubectl exec -i -n "$INFRA_NAMESPACE" "$VAULT_POD" -- sh -ceu "
    IFS= read -r VAULT_TOKEN
    export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200
    vault kv get -field=password secret/apps/$APP_NAME/registry 2>/dev/null || true
  ")"

api_json GET "projects/$project_id/deploy_tokens" > "$work_dir/deploy-tokens.json"
deploy_token_id="$(jq -r --arg name "$REGISTRY_DEPLOY_TOKEN_NAME" \
  '.[] | select(.name==$name and .revoked==false) | .id' "$work_dir/deploy-tokens.json" | head -n 1)"
if [[ -z "$deploy_token_id" || -z "$registry_username" || -z "$registry_password" ]]; then
  if [[ -n "$deploy_token_id" ]]; then
    info "Rotating the managed registry pull token"
    api_json DELETE "projects/$project_id/deploy_tokens/$deploy_token_id" >/dev/null
  else
    info "Creating the managed registry pull token"
  fi
  api_json POST "projects/$project_id/deploy_tokens" \
    --form-string "name=$REGISTRY_DEPLOY_TOKEN_NAME" \
    --form-string "username=$APP_NAME-cluster-pull" \
    --form-string 'scopes[]=read_registry' > "$work_dir/deploy-token.json"
  registry_username="$(jq -er '.username' "$work_dir/deploy-token.json")"
  registry_password="$(jq -er '.token' "$work_dir/deploy-token.json")"
fi

{ printf '%s\n%s\n%s\n%s\n' \
    "$vault_token" "$GITLAB_REGISTRY_HOST" "$registry_username" "$registry_password"; } | \
  kubectl exec -i -n "$INFRA_NAMESPACE" "$VAULT_POD" -- sh -ceu "
    IFS= read -r VAULT_TOKEN
    IFS= read -r registry
    IFS= read -r username
    IFS= read -r password
    export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200
    vault kv put secret/apps/$APP_NAME/registry \\
      registry=\"\$registry\" username=\"\$username\" password=\"\$password\" >/dev/null
  "
unset registry_password

git -C "$REPOSITORY_ROOT" show HEAD:.gitlab-ci.yml >/dev/null
GITLAB_URL="$GITLAB_URL" "$SCRIPT_DIR/configure-code-quality.sh"
GITLAB_URL="$GITLAB_URL" "$SCRIPT_DIR/configure-repository-sync.sh"
info "$APP_NAME shared GitLab, registry and repository settings are configured. Use swirl-cloud/add-repos.sh for environment provisioning and select the deployment target in GitLab CI."
