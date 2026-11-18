#!/usr/bin/env bash
# Promote the release running on uat to prod from a command line.
#
# Starts a default-branch pipeline through the GitLab API with
# DEPLOYMENT_ENVIRONMENT=prod, the same pipeline as Run pipeline in the GitLab UI:
# 00-delivery-policy and 02-deploy promote only the release uat runs, after the
# same checks, and nothing is built. Then it waits for the pipeline, prints each
# job's result and the version prod now runs.
#
# The token (GITLAB_TOKEN, or a file named by --token-file or GITLAB_TOKEN_FILE)
# needs the api scope and the project role that may run pipelines with variables.
# It is never printed or passed on a command line.
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
onboarding="$repository_root/infra/onboarding-values.json"

usage() {
  cat <<'EOF'
Usage: infra/scripts/promote-prod.sh [options]

Promote the release running on uat to prod through a GitLab pipeline.

Options:
  --no-wait            start the pipeline and print its URL without waiting
  --token-file PATH    read the GitLab token from PATH instead of GITLAB_TOKEN
  --gitlab-url URL     GitLab base URL (default: GITLAB_URL, else onboarding's GITLAB_PUBLIC_URL)
  --project PATH       GitLab project path (default: GITLAB_PROJECT_PATH, else onboarding's)
  --ref BRANCH         branch to run on (default: the project's default branch)
  --timeout SECONDS    how long to wait for the pipeline (default: 1800)
  -h, --help           show this help
EOF
}

fail() { printf 'promote-prod: %s\n' "$*" >&2; exit 1; }

onboarding_value() {
  [[ -f "$onboarding" ]] && jq -r --arg key "$1" '.context[$key] // empty' "$onboarding" || true
}

wait_for_pipeline=true
token_file="${GITLAB_TOKEN_FILE:-}"
gitlab_url="${GITLAB_URL:-}"
project_path="${GITLAB_PROJECT_PATH:-}"
ref="${GITLAB_REF:-}"
timeout_seconds=1800
poll_seconds="${PROMOTE_PROD_POLL_SECONDS:-10}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-wait) wait_for_pipeline=false ;;
    --token-file) token_file="${2:?--token-file needs a path}"; shift ;;
    --gitlab-url) gitlab_url="${2:?--gitlab-url needs a URL}"; shift ;;
    --project) project_path="${2:?--project needs a path}"; shift ;;
    --ref) ref="${2:?--ref needs a branch}"; shift ;;
    --timeout) timeout_seconds="${2:?--timeout needs seconds}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
  shift
done

for command_name in curl jq; do
  command -v "$command_name" >/dev/null || fail "$command_name is required"
done
gitlab_url="${gitlab_url:-$(onboarding_value GITLAB_PUBLIC_URL)}"
gitlab_url="${gitlab_url%/}"
project_path="${project_path:-$(onboarding_value GITLAB_PROJECT_PATH)}"
[[ "$gitlab_url" =~ ^https?://[^/[:space:]]+(/[^[:space:]]*)?$ ]] || fail 'Set the GitLab URL with --gitlab-url or GITLAB_URL.'
[[ "$project_path" =~ ^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+$ ]] || fail 'Set the project path with --project or GITLAB_PROJECT_PATH.'
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || fail '--timeout must be a number of seconds.'
[[ "$poll_seconds" =~ ^[0-9]+$ ]] || fail 'PROMOTE_PROD_POLL_SECONDS must be a number of seconds.'

if [[ -n "$token_file" ]]; then
  [[ -r "$token_file" ]] || fail "Cannot read the token file $token_file."
  token="$(tr -d '[:space:]' < "$token_file")"
else
  token="${GITLAB_TOKEN:-}"
fi
[[ -n "$token" ]] || fail 'Set GITLAB_TOKEN or pass --token-file.'
[[ "$token" =~ ^[[:graph:]]+$ && "$token" != *'"'* && "$token" != *'\'* ]] || fail 'The GitLab token has unexpected characters.'

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
# curl reads the token from a private config file, not from its command line.
(umask 077 && printf 'header = "PRIVATE-TOKEN: %s"\n' "$token" > "$work_dir/curl.conf")
unset token GITLAB_TOKEN

api_url="$gitlab_url/api/v4"
project_id="$(jq -rn --arg path "$project_path" '$path | @uri')"
api() {
  local path="$1"; shift
  curl --silent --show-error --fail-with-body --config "$work_dir/curl.conf" \
    --connect-timeout 10 --max-time 60 "$@" "$api_url/projects/$project_id$path"
}

project="$(api '')" || fail "Cannot read project $project_path at $gitlab_url: $(jq -r '.message // .error // empty' <<<"$project" 2>/dev/null)"
ref="${ref:-$(jq -r '.default_branch // empty' <<<"$project")}"
[[ -n "$ref" ]] || fail 'The project has no default branch; pass --ref.'

request="$(jq -n --arg ref "$ref" \
  '{ref: $ref, variables: [{key: "DEPLOYMENT_ENVIRONMENT", value: "prod", variable_type: "env_var"}]}')"
if ! pipeline="$(api /pipeline --request POST --header 'Content-Type: application/json' --data "$request")"; then
  fail "GitLab refused the prod pipeline: $(jq -c '.message // .error // .' <<<"$pipeline" 2>/dev/null || printf '%s' "$pipeline")"
fi
pipeline_id="$(jq -r '.id' <<<"$pipeline")"
pipeline_url="$(jq -r '.web_url' <<<"$pipeline")"
[[ "$pipeline_id" =~ ^[0-9]+$ ]] || fail 'GitLab did not return a pipeline.'
printf 'Started prod promotion pipeline %s on %s: %s\n' "$pipeline_id" "$ref" "$pipeline_url"
[[ "$wait_for_pipeline" == true ]] || exit 0

# Report each job as its status changes, until the pipeline finishes.
reported='{}'
jobs_json='[]'
status=''
deadline=$(( $(date +%s) + timeout_seconds ))
while true; do
  jobs_json="$(api "/pipelines/$pipeline_id/jobs?per_page=100")" || fail 'Cannot read the pipeline jobs.'
  jq -r --argjson reported "$reported" \
    'sort_by(.id)[] | select($reported[.name] != .status) | "  \(.name): \(.status)"' <<<"$jobs_json"
  reported="$(jq -c 'map({(.name): .status}) | add // {}' <<<"$jobs_json")"
  status="$(api "/pipelines/$pipeline_id" | jq -r '.status')" || fail 'Cannot read the pipeline status.'
  case "$status" in
    success|failed|canceled|skipped|manual) break ;;
  esac
  [[ "$(date +%s)" -lt "$deadline" ]] || fail "Pipeline $pipeline_id is still $status after ${timeout_seconds}s: $pipeline_url"
  sleep "$poll_seconds"
done
# The job list read before the final status can predate the last job's end.
jobs_json="$(api "/pipelines/$pipeline_id/jobs?per_page=100")" || fail 'Cannot read the pipeline jobs.'

printf '\nPipeline %s: %s\n' "$pipeline_id" "$status"
jq -r 'sort_by(.id)[] | "  \(.name)\t\(.status)\t\(if .duration then "\(.duration | floor)s" else "-" end)"' <<<"$jobs_json"

if [[ "$status" != success ]]; then
  failed="$(jq -r '[.[] | select(.status == "failed" or .status == "canceled")] | sort_by(.id)[] | "\(.name): \(.web_url)"' <<<"$jobs_json")"
  printf '\nProd was not promoted.\n%s\n' "${failed:-$pipeline_url}" >&2
  exit 1
fi

# 02-deploy records the promoted release in its release.env artifact.
deploy_job="$(jq -r '[.[] | select(.name == "02-deploy" and .status == "success")][0].id // empty' <<<"$jobs_json")"
[[ -n "$deploy_job" ]] || fail "The pipeline succeeded without a prod deployment: $pipeline_url"
release_env="$(api "/jobs/$deploy_job/artifacts/release.env")" || fail "Cannot read the deployed version from 02-deploy: $pipeline_url"
version="$(sed -n 's/^APP_VERSION=//p' <<<"$release_env" | head -n 1)"
deployment_url="$(sed -n 's/^DEPLOYMENT_URL=//p' <<<"$release_env" | head -n 1)"
[[ -n "$version" ]] || fail "02-deploy did not record a version: $pipeline_url"
printf '\nprod now runs %s%s\n' "$version" "${deployment_url:+ at $deployment_url}"
