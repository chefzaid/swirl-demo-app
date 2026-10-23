#!/usr/bin/env bash
set -euo pipefail
umask 077

APP_NAME="${APP_NAME:-swirl-demo-app}"
APP_DISPLAY_NAME="${APP_DISPLAY_NAME:-Swirl Demo App}"
PROJECT_DESCRIPTION="${PROJECT_DESCRIPTION:-Swirl Demo App is a production-shaped Java and Angular service template for teams whose services must pass security, architecture and supplier reviews.}"
PROJECT_TOPICS="${PROJECT_TOPICS:-angular,gitops,java,microservices,spring-boot,template}"
GITLAB_PROJECT_PATH="${GITLAB_PROJECT_PATH:-swirlit/swirl-demo-app}"
GITLAB_NAMESPACE="${GITLAB_NAMESPACE:-${GITLAB_PROJECT_PATH%/*}}"
GITLAB_URL="${GITLAB_URL:-}"
GITLAB_PUBLIC_URL="${GITLAB_PUBLIC_URL:-https://gitlab.swirlit.dev}"
SONAR_URL="${SONAR_URL:-}"
SONAR_PUBLIC_URL="${SONAR_PUBLIC_URL:-https://sonarqube.swirlit.dev}"
SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-swirlit:swirl-demo-app}"
SONAR_ALM_KEY="${SONAR_ALM_KEY:-swirlit-gitlab}"
INFRA_NAMESPACE="${INFRA_NAMESPACE:-infra}"
VAULT_POD="${VAULT_POD:-vault-0}"
VAULT_TOKEN_FILE="${VAULT_BOOTSTRAP_TOKEN_FILE:-/var/lib/swirl-cloud/vault-bootstrap-token}"

info() { printf '[INFO] %s\n' "$*"; }
fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }

for command_name in curl git jq kubectl sudo; do
  command -v "$command_name" >/dev/null || fail "$command_name is required"
done
[[ -n "${GITLAB_ADMIN_TOKEN:-}" ]] || fail 'GITLAB_ADMIN_TOKEN is required'
sudo test -s "$VAULT_TOKEN_FILE" || fail "Vault bootstrap token is missing from $VAULT_TOKEN_FILE"

if [[ -z "$GITLAB_URL" ]]; then
  gitlab_service_ip="$(kubectl get service gitlab -n "$INFRA_NAMESPACE" -o jsonpath='{.spec.clusterIP}')"
  GITLAB_URL="http://$gitlab_service_ip"
fi
if [[ -z "$SONAR_URL" ]]; then
  sonar_service_ip="$(kubectl get service sonarqube -n "$INFRA_NAMESPACE" -o jsonpath='{.spec.clusterIP}')"
  SONAR_URL="http://$sonar_service_ip:9000"
fi

work_dir="$(mktemp -d "/tmp/$APP_NAME-quality.XXXXXX")"
trap 'rm -r -- "$work_dir"; unset GITLAB_ADMIN_TOKEN vault_token sonar_admin_token sonar_gitlab_token sonar_project_token' EXIT
gitlab_config="$work_dir/gitlab-curl.conf"
printf 'silent\nshow-error\nheader = "PRIVATE-TOKEN: %s"\n' "$GITLAB_ADMIN_TOKEN" > "$gitlab_config"

gitlab_api() {
  local method="$1" path="$2"
  shift 2
  curl --config "$gitlab_config" --fail-with-body --request "$method" \
    "$GITLAB_URL/api/v4/$path" "$@"
}

vault_token="$(sudo cat "$VAULT_TOKEN_FILE")"
vault_field() {
  local path="$1" field="$2"
  # The variables in this quoted program expand inside the Vault pod.
  # shellcheck disable=SC2016
  { printf '%s\n%s\n%s\n' "$vault_token" "$path" "$field"; } | \
    kubectl exec -i -n "$INFRA_NAMESPACE" "$VAULT_POD" -- sh -ceu '
      IFS= read -r VAULT_TOKEN
      IFS= read -r secret_path
      IFS= read -r field
      export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200
      vault kv get -field="$field" "secret/$secret_path" 2>/dev/null || true
    '
}

encoded_project_path="$(jq -rn --arg value "$GITLAB_PROJECT_PATH" '$value|@uri')"
gitlab_api GET "projects/$encoded_project_path" > "$work_dir/project.json"
project_id="$(jq -er '.id' "$work_dir/project.json")"
default_branch="$(jq -er '.default_branch | select(type == "string" and length > 0)' "$work_dir/project.json")"
git check-ref-format "refs/heads/$default_branch" >/dev/null || fail 'GitLab returned an invalid default branch'
encoded_default_branch="$(jq -rn --arg value "$default_branch" '$value|@uri')"

jq -n --arg description "$PROJECT_DESCRIPTION" --arg topics "$PROJECT_TOPICS" \
  --arg current_visibility "$(jq -er '.visibility' "$work_dir/project.json")" '
  {
    description:$description,
    topics:($topics|split(",")),
    repository_access_level:"enabled",
    issues_access_level:"enabled",
    merge_requests_access_level:"enabled",
    builds_access_level:"enabled",
    analytics_access_level:"enabled",
    releases_access_level:"enabled",
    environments_access_level:"enabled",
    monitor_access_level:"enabled",
    container_registry_access_level:"private",
    package_registry_access_level:"private",
    security_and_compliance_access_level:"private",
    wiki_access_level:"disabled",
    snippets_access_level:"disabled",
    feature_flags_access_level:"disabled",
    infrastructure_access_level:"disabled",
    pages_access_level:"disabled",
    auto_devops_enabled:false,
    shared_runners_enabled:true,
    only_allow_merge_if_pipeline_succeeds:true,
    only_allow_merge_if_all_discussions_are_resolved:true,
    remove_source_branch_after_merge:true,
    squash_option:"default_on",
    merge_method:"merge",
    keep_latest_artifact:true,
    build_timeout:7200,
    ci_default_git_depth:20,
    ci_pipeline_variables_minimum_override_role:"maintainer",
    ci_push_repository_for_job_token_allowed:true,
    container_expiration_policy_attributes:{cadence:"1d",enabled:true,keep_n:10,older_than:"1095d",name_regex:".*",name_regex_keep:"^$"}
  } + if $current_visibility == "public" then {} else {visibility:"public"} end' > "$work_dir/project-settings.json"
gitlab_api PUT "projects/$project_id" --header 'Content-Type: application/json' \
  --data-binary "@$work_dir/project-settings.json" >/dev/null
info 'GitLab project metadata and feature visibility reconciled'

while IFS='|' read -r name color description; do
  encoded_name="$(jq -rn --arg value "$name" '$value|@uri')"
  if curl --config "$gitlab_config" --fail --silent \
    "$GITLAB_URL/api/v4/projects/$project_id/labels/$encoded_name" > "$work_dir/label.json" 2>/dev/null; then
    gitlab_api PUT "projects/$project_id/labels/$encoded_name" \
      --form-string "new_name=$name" --form-string "color=$color" \
      --form-string "description=$description" >/dev/null
  else
    gitlab_api POST "projects/$project_id/labels" --form-string "name=$name" \
      --form-string "color=$color" --form-string "description=$description" >/dev/null
  fi
done <<'LABELS'
type::bug|#D73A4A|A defect or regression.
type::feature|#1D76DB|New product functionality.
type::security|#B60205|Security hardening or remediation.
type::maintenance|#6F42C1|Dependencies, tooling, or operational upkeep.
type::documentation|#0075CA|Documentation-only work.
priority::critical|#B60205|Immediate production or security impact.
priority::high|#D93F0B|High-priority work for the next delivery window.
priority::normal|#FBCA04|Normal planned work.
status::blocked|#000000|Waiting on an explicit dependency or decision.
status::ready|#0E8A16|Defined and ready for implementation.
LABELS
info 'GitLab labels reconciled'

protected_status="$(curl --config "$gitlab_config" --silent --output "$work_dir/protected.json" \
  --write-out '%{http_code}' "$GITLAB_URL/api/v4/projects/$project_id/protected_branches/$encoded_default_branch")"
if [[ "$protected_status" == 200 ]]; then
  gitlab_api PATCH "projects/$project_id/protected_branches/$encoded_default_branch" \
    --form-string 'allow_force_push=false' >/dev/null
elif [[ "$protected_status" == 404 ]]; then
  gitlab_api POST "projects/$project_id/protected_branches" --form-string "name=$default_branch" \
    --form-string 'push_access_level=40' --form-string 'merge_access_level=30' \
    --form-string 'allow_force_push=false' >/dev/null
else
  cat "$work_dir/protected.json" >&2
  fail "Unable to inspect the protected default branch (HTTP $protected_status)"
fi
info 'GitLab default-branch protection reconciled'

sonar_admin_token="${SONAR_ADMIN_TOKEN:-$(vault_field infra/sonarqube admin_token)}"
[[ -n "$sonar_admin_token" ]] || fail 'SONAR_ADMIN_TOKEN is required or must exist at Vault infra/sonarqube:admin_token'
sonar_config="$work_dir/sonar-curl.conf"
printf 'silent\nshow-error\nheader = "Authorization: Bearer %s"\n' "$sonar_admin_token" > "$sonar_config"
sonar_api() {
  local method="$1" path="$2"
  shift 2
  curl --config "$sonar_config" --fail-with-body --request "$method" "$SONAR_URL/$path" "$@"
}

sonar_gitlab_token="$(vault_field infra/gitlab sonar_api_token)"
if [[ -z "$sonar_gitlab_token" ]]; then
  encoded_namespace="$(jq -rn --arg value "$GITLAB_NAMESPACE" '$value|@uri')"
  gitlab_api GET "groups/$encoded_namespace" > "$work_dir/group.json"
  group_id="$(jq -er '.id' "$work_dir/group.json")"
  expires_at="$(date -u -d '+364 days' +%F)"
  gitlab_api POST "groups/$group_id/access_tokens" \
    --form-string "name=sonarqube-integration-$(date -u +%Y%m%d)" \
    --form-string 'scopes[]=api' --form-string 'access_level=20' \
    --form-string "expires_at=$expires_at" > "$work_dir/sonar-gitlab-token.json"
  sonar_gitlab_token="$(jq -er '.token' "$work_dir/sonar-gitlab-token.json")"
  # The variables in this quoted program expand inside the Vault pod.
  # shellcheck disable=SC2016
  { printf '%s\n%s\n' "$vault_token" "$sonar_gitlab_token"; } | \
    kubectl exec -i -n "$INFRA_NAMESPACE" "$VAULT_POD" -- sh -ceu '
      IFS= read -r VAULT_TOKEN
      IFS= read -r sonar_api_token
      export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200
      vault kv patch secret/infra/gitlab sonar_api_token="$sonar_api_token" >/dev/null
    '
fi
info 'GitLab token for SonarQube integration is available'

sonar_api POST 'api/settings/set' --form-string 'key=sonar.core.serverBaseURL' \
  --form-string "value=$SONAR_PUBLIC_URL" >/dev/null
sonar_api GET 'api/alm_settings/list' > "$work_dir/alm-settings.json"
existing_alm_key="$(jq -r --arg key "$SONAR_ALM_KEY" --arg url "$GITLAB_URL/api/v4" \
  '[.almSettings[]? | select(.key==$key or (.alm=="gitlab" and .url==$url))][0].key // empty' \
  "$work_dir/alm-settings.json")"
if [[ -n "$existing_alm_key" ]]; then
  sonar_api POST 'api/alm_settings/update_gitlab' \
    --form-string "key=$existing_alm_key" --form-string "newKey=$SONAR_ALM_KEY" \
    --form-string "personalAccessToken=$sonar_gitlab_token" \
    --form-string "url=$GITLAB_URL/api/v4" >/dev/null
else
  sonar_api POST 'api/alm_settings/create_gitlab' --form-string "key=$SONAR_ALM_KEY" \
    --form-string "personalAccessToken=$sonar_gitlab_token" \
    --form-string "url=$GITLAB_URL/api/v4" >/dev/null
fi
info 'SonarQube GitLab ALM setting reconciled'
sonar_api POST 'api/alm_integrations/set_pat' --form-string "almSetting=$SONAR_ALM_KEY" \
  --form-string "pat=$sonar_gitlab_token" --form-string 'username=admin' >/dev/null
info 'SonarQube user-level GitLab credential reconciled'
sonar_api GET 'api/alm_integrations/search_gitlab_repos' --get \
  --data-urlencode "almSetting=$SONAR_ALM_KEY" --data-urlencode "projectName=$APP_NAME" \
  --data-urlencode 'ps=1' >/dev/null
info 'SonarQube GitLab integration validated'

sonar_api GET 'api/v2/dop-translation/dop-settings' > "$work_dir/dop-settings.json"
dop_setting_id="$(jq -er --arg key "$SONAR_ALM_KEY" \
  '.dopSettings[] | select(.key==$key) | .id' "$work_dir/dop-settings.json")"
jq -n --arg key "$SONAR_PROJECT_KEY" --arg name "$APP_DISPLAY_NAME" \
  --arg setting "$dop_setting_id" --arg repository "$project_id" \
  '{projectKey:$key,projectName:$name,devOpsPlatformSettingId:$setting,repositoryIdentifier:$repository,newCodeDefinitionType:"PREVIOUS_VERSION",newCodeDefinitionValue:null,monorepo:false}' \
  > "$work_dir/bound-project.json"
sonar_api PUT 'api/v2/dop-translation/bound-projects' --header 'Content-Type: application/json' \
  --data-binary "@$work_dir/bound-project.json" >/dev/null
info 'SonarQube project binding reconciled'
sonar_api POST 'api/projects/update_visibility' --form-string "project=$SONAR_PROJECT_KEY" \
  --form-string 'visibility=public' >/dev/null
sonar_api POST 'api/qualitygates/select' --form-string 'gateName=Sonar way' \
  --form-string "projectKey=$SONAR_PROJECT_KEY" >/dev/null
sonar_api POST 'api/project_tags/set' --form-string "project=$SONAR_PROJECT_KEY" \
  --form-string "tags=$PROJECT_TOPICS" >/dev/null

sonar_api GET "api/project_links/search?projectKey=$(jq -rn --arg value "$SONAR_PROJECT_KEY" '$value|@uri')" \
  > "$work_dir/project-links.json"
jq -r '.links[] | select(.name|IN("GitLab repository","GitLab pipelines","Issue tracker")) | .id' \
  "$work_dir/project-links.json" | while IFS= read -r link_id; do
    sonar_api POST 'api/project_links/delete' --form-string "id=$link_id" >/dev/null
  done
sonar_api POST 'api/project_links/create' --form-string "projectKey=$SONAR_PROJECT_KEY" \
  --form-string 'name=GitLab repository' --form-string "url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH" >/dev/null
sonar_api POST 'api/project_links/create' --form-string "projectKey=$SONAR_PROJECT_KEY" \
  --form-string 'name=GitLab pipelines' --form-string "url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/pipelines" >/dev/null
sonar_api POST 'api/project_links/create' --form-string "projectKey=$SONAR_PROJECT_KEY" \
  --form-string 'name=Issue tracker' --form-string "url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/issues" >/dev/null
info 'SonarQube project metadata and links reconciled'

token_name="gitlab-ci-$APP_NAME"
if sonar_api GET 'api/user_tokens/search?login=admin' | jq -e --arg name "$token_name" \
  '.userTokens[]? | select(.name==$name)' >/dev/null; then
  sonar_api POST 'api/user_tokens/revoke' --form-string 'login=admin' \
    --form-string "name=$token_name" >/dev/null
fi
sonar_api POST 'api/user_tokens/generate' --form-string 'login=admin' \
  --form-string "name=$token_name" --form-string 'type=PROJECT_ANALYSIS_TOKEN' \
  --form-string "projectKey=$SONAR_PROJECT_KEY" > "$work_dir/analysis-token.json"
sonar_project_token="$(jq -er '.token' "$work_dir/analysis-token.json")"

variable_status="$(curl --config "$gitlab_config" --silent --output "$work_dir/variable.json" \
  --write-out '%{http_code}' "$GITLAB_URL/api/v4/projects/$project_id/variables/SONAR_TOKEN")"
if [[ "$variable_status" == 200 ]]; then
  gitlab_api PUT "projects/$project_id/variables/SONAR_TOKEN" \
    --form-string "value=$sonar_project_token" --form-string 'masked=true' \
    --form-string 'protected=false' --form-string 'raw=true' >/dev/null
elif [[ "$variable_status" == 404 ]]; then
  gitlab_api POST "projects/$project_id/variables" --form-string 'key=SONAR_TOKEN' \
    --form-string "value=$sonar_project_token" --form-string 'masked=true' \
    --form-string 'protected=false' --form-string 'raw=true' >/dev/null
else
  cat "$work_dir/variable.json" >&2
  fail "Unable to inspect SONAR_TOKEN (HTTP $variable_status)"
fi
info 'GitLab Sonar analysis variable reconciled'

gitlab_api GET "projects/$project_id/badges?per_page=100" > "$work_dir/badges.json"
jq -r '.[] | select(.name|IN("Pipeline","Run pipeline","Coverage","Release","Package Registry","Sonar quality gate")) | .id' \
  "$work_dir/badges.json" | while IFS= read -r badge_id; do
    gitlab_api DELETE "projects/$project_id/badges/$badge_id" >/dev/null
  done
sonar_key_encoded="$(jq -rn --arg value "$SONAR_PROJECT_KEY" '$value|@uri')"
gitlab_api POST "projects/$project_id/badges" --form-string 'name=Pipeline' \
  --form-string "link_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/pipelines" \
  --form-string "image_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/badges/$encoded_default_branch/pipeline.svg" >/dev/null
gitlab_api POST "projects/$project_id/badges" --form-string 'name=Coverage' \
  --form-string "link_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/graphs/$encoded_default_branch/charts" \
  --form-string "image_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/badges/$encoded_default_branch/coverage.svg" >/dev/null
gitlab_api POST "projects/$project_id/badges" --form-string 'name=Run pipeline' \
  --form-string "link_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/pipelines/new?ref=$encoded_default_branch" \
  --form-string "image_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/badges/$encoded_default_branch/pipeline.svg?key_text=run%20pipeline" >/dev/null
gitlab_api POST "projects/$project_id/badges" --form-string 'name=Release' \
  --form-string "link_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/releases" \
  --form-string "image_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/badges/release.svg" >/dev/null
gitlab_api POST "projects/$project_id/badges" --form-string 'name=Package Registry' \
  --form-string "link_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/packages" \
  --form-string "image_url=$GITLAB_PUBLIC_URL/$GITLAB_PROJECT_PATH/-/badges/release.svg?key_text=packages" >/dev/null
gitlab_api POST "projects/$project_id/badges" --form-string 'name=Sonar quality gate' \
  --form-string "link_url=$SONAR_PUBLIC_URL/dashboard?id=$sonar_key_encoded" \
  --form-string "image_url=$SONAR_PUBLIC_URL/api/project_badges/measure?project=$sonar_key_encoded&metric=alert_status" >/dev/null

info "$APP_DISPLAY_NAME project metadata, governance, SonarQube binding, quality token, labels, and badges are configured"
