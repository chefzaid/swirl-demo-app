#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository_root"

phase="${1:-all}"
fail() { printf 'Delivery refused: %s\n' "$*" >&2; exit 1; }

configure_git_identity() {
  git config user.name "Swirl Demo App GitLab CI"
  git config user.email "gitlab-ci@${CI_SERVER_HOST:-localhost}"
}

trailer() { git show -s --format="%(trailers:key=$2,valueonly)" "$1"; }

write_manifest() {
  local manifest_path="${1:-infra/releases/$APP_VERSION.json}" kind="${2:-release}" name digest images='{}'
  for name in swirlapp-user swirlapp-order swirlapp-web; do
    digest="$(cat "package-output/image-digests/$name")"
    [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'The image publisher did not return an immutable digest.'
    images="$(jq --arg name "$name" --arg repository "$CI_REGISTRY/$CI_PROJECT_PATH/$name" --arg digest "$digest" \
      '. + {($name): {repository:$repository,digest:$digest}}' <<<"$images")"
  done
  mkdir -p "$(dirname "$manifest_path")"
  jq -n --arg kind "$kind" --arg branch "${CI_COMMIT_BRANCH:-}" --arg version "$APP_VERSION" --arg source "$CI_COMMIT_SHA" --arg pipeline "$CI_PIPELINE_ID" \
    --argjson images "$images" '{version:1,kind:$kind,releaseVersion:$version,sourceRevision:$source,pipelineId:$pipeline,images:$images} +
    (if $kind == "snapshot" then {sourceBranch:$branch} else {} end)' \
    > "$manifest_path"
}

write_release_environment() {
  printf 'APP_VERSION=%s\nDEPLOY_REVISION=%s\nRELEASE_REVISION=%s\nRELEASE_TAG=%s\nNEXT_VERSION=%s\nPUBLICATION_COMMIT=%s\n' \
    "$APP_VERSION" "$deploy_revision" "$release_revision" "$release_tag" "$next_version" "$deploy_revision" > release.env
}

finalize_release() {
  local release_json existing_release
  release_json="$(jq -n --arg tag "$release_tag" --arg name "Swirl Demo App $APP_VERSION" \
    --arg description "Swirl Demo App is a production-shaped Java and Angular service template for teams whose services must pass security, architecture and supplier reviews. Release $APP_VERSION publishes immutable images for promotion through central Argo CD." \
    --arg package_url "${CI_SERVER_URL}/${CI_PROJECT_PATH}/-/packages" \
    '{tag_name:$tag,name:$name,description:$description,assets:{links:[{name:"Generic package artifacts and checksums",url:$package_url,link_type:"package"}]}}')"
  if curl --fail --show-error --silent --request POST --header "JOB-TOKEN: $CI_JOB_TOKEN" \
    --header 'Content-Type: application/json' --data "$release_json" \
    "$CI_API_V4_URL/projects/$CI_PROJECT_ID/releases" >/dev/null; then
    return
  fi
  # A lost POST response or retried job can leave an already-created release.
  existing_release="$(curl --fail --show-error --silent --header "JOB-TOKEN: $CI_JOB_TOKEN" \
    "$CI_API_V4_URL/projects/$CI_PROJECT_ID/releases/$release_tag")" || return 1
  jq -e --arg tag "$release_tag" --arg revision "$release_revision" \
    '.tag_name == $tag and .commit.id == $revision' <<<"$existing_release" >/dev/null
}

recover_publication() {
  local candidate published source manifest
  [[ "${CI_PIPELINE_ID:-}" =~ ^[0-9]+$ && -n "${CI_DEFAULT_BRANCH:-}" && -n "${CI_COMMIT_SHA:-}" ]] || return 1
  git fetch --quiet --no-tags origin "$CI_DEFAULT_BRANCH" || return 1
  candidate="$(git rev-parse "refs/remotes/origin/$CI_DEFAULT_BRANCH")" || return 1
  [[ "$candidate" != "$CI_COMMIT_SHA" &&
     "$(trailer "$candidate" Release-Pipeline)" == "$CI_PIPELINE_ID" &&
     "$(trailer "$candidate" Release-Source)" == "$CI_COMMIT_SHA" ]] || return 1
  if [[ "${APP_ONBOARDING:-false}" == true ]]; then
    [[ "${CI_PIPELINE_SOURCE:-}" == api && "${SONAR_SCAN_ONLY:-false}" != true &&
       "${ONBOARDING_EXPECTED_SHA:-}" == "$CI_COMMIT_SHA" && "${CI_COMMIT_BRANCH:-}" == "$CI_DEFAULT_BRANCH" ]] || return 1
  fi
  published="$(git rev-parse "$candidate^")" || return 1
  source="$(git rev-parse "$published^")" || return 1
  [[ "$source" == "$CI_COMMIT_SHA" &&
     "$(git rev-list --parents -n 1 "$candidate")" == "$candidate $published" &&
     "$(git rev-list --parents -n 1 "$published")" == "$published $source" &&
     "$(git show "$published:VERSION")" == "$APP_VERSION" &&
     "$(git show "$candidate:VERSION")" == "$next_version" &&
     "$(git rev-parse "$published:infra")" == "$(git rev-parse "$candidate:infra")" ]] || return 1
  git fetch --quiet --no-tags origin "refs/tags/$release_tag:refs/tags/$release_tag" || return 1
  [[ "$(git cat-file -t "refs/tags/$release_tag")" == tag &&
     "$(git rev-parse "refs/tags/$release_tag^{commit}")" == "$published" ]] || return 1
  manifest="$(git show "$published:infra/releases/$APP_VERSION.json")" || return 1
  jq -e --arg version "$APP_VERSION" --arg source "$CI_COMMIT_SHA" --arg pipeline "$CI_PIPELINE_ID" \
    --arg prefix "$CI_REGISTRY/$CI_PROJECT_PATH/" '
    .version == 1 and (.kind // "release") == "release" and .releaseVersion == $version and .sourceRevision == $source and .pipelineId == $pipeline and
    (.images | keys) == ["swirlapp-order", "swirlapp-user", "swirlapp-web"] and
    (.images | to_entries | all(.value.repository == ($prefix + .key) and (.value.digest | test("^sha256:[0-9a-f]{64}$"))))' \
    <<<"$manifest" >/dev/null || return 1
  release_revision="$published"
  deploy_revision="$candidate"
}

publish_release() {
  [[ -n "${CI_DEFAULT_BRANCH:-}" && "${CI_COMMIT_BRANCH:-}" == "$CI_DEFAULT_BRANCH" &&
     "${SONAR_SCAN_ONLY:-false}" != true ]] || fail 'Release publication requires the default branch and a delivery pipeline.'
  : "${APP_VERSION:?APP_VERSION is required}"
  [[ "$APP_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || return 1
  release_tag="v$APP_VERSION"
  major="${APP_VERSION%%.*}"
  remainder="${APP_VERSION#*.}"
  minor="${remainder%%.*}"
  next_version="$major.$((minor + 1)).0"
  if recover_publication; then
    echo "Resuming GitLab release finalization for this pipeline's verified publication."
    write_release_environment
    finalize_release
    return
  fi
  infra/scripts/check-onboarding-revision.sh publish
  git fetch --quiet --no-tags origin "$CI_DEFAULT_BRANCH"
  [[ "$(git rev-parse "origin/$CI_DEFAULT_BRANCH")" == "$CI_COMMIT_SHA" && "$(git rev-parse HEAD)" == "$CI_COMMIT_SHA" ]] ||
    fail 'The source branch advanced; start a new release pipeline.'
  [[ -z "$(git ls-remote --tags origin "refs/tags/$release_tag")" ]] || fail 'This release version already exists; promote it with RELEASE_VERSION.'
  : "${CI_REGISTRY:?CI_REGISTRY is required}"
  : "${CI_PIPELINE_ID:?CI_PIPELINE_ID is required}"
  infra/scripts/ci-container-build.sh publish
  write_manifest

  output_dir="$repository_root/package-output"
  package_url="$PACKAGE_REGISTRY_API_V4_URL/projects/$CI_PROJECT_ID/packages/generic/$CI_PROJECT_NAME/$APP_VERSION"
  mkdir -p "$output_dir"
  cp swirlapp-user/target/swirlapp-user.jar "$output_dir/swirlapp-user-$APP_VERSION.jar"
  cp swirlapp-order/target/swirlapp-order.jar "$output_dir/swirlapp-order-$APP_VERSION.jar"
  cp "swirlapp-web/swirlapp-web-$APP_VERSION.tar.gz" "$output_dir/"
  cp "infra/releases/$APP_VERSION.json" "$output_dir/release-manifest.json"
  (cd "$output_dir" && sha256sum ./*.jar ./*.tar.gz ./release-manifest.json > SHA256SUMS)
  for artifact in "$output_dir"/*; do
    [[ -f "$artifact" ]] || continue
    artifact_name="$(basename "$artifact")"
    curl --fail --show-error --silent --retry 3 --header "JOB-TOKEN: $CI_JOB_TOKEN" \
      --upload-file "$artifact" "$package_url/$artifact_name"
  done

  git fetch origin "$CI_DEFAULT_BRANCH"
  test "$CI_COMMIT_SHA" = "$(git rev-parse "origin/$CI_DEFAULT_BRANCH")"
  git checkout -B "$CI_DEFAULT_BRANCH" "origin/$CI_DEFAULT_BRANCH"
  infra/scripts/set-project-version.sh "$APP_VERSION"
  configure_git_identity
  git add VERSION pom.xml swirlapp-common/pom.xml swirlapp-order/pom.xml swirlapp-user/pom.xml \
    swirlapp-web/package.json swirlapp-web/package-lock.json "infra/releases/$APP_VERSION.json"
  git commit -m "release: $APP_VERSION [skip ci]"
  release_revision="$(git rev-parse HEAD)"
  git tag --annotate "$release_tag" --message "Swirl Demo App $APP_VERSION"

  infra/scripts/set-project-version.sh "$next_version"
  git add VERSION pom.xml swirlapp-common/pom.xml swirlapp-order/pom.xml swirlapp-user/pom.xml \
    swirlapp-web/package.json swirlapp-web/package-lock.json
  local commit_args=(--allow-empty -m "chore: prepare $next_version [skip ci]"
    --trailer "Release-Pipeline: $CI_PIPELINE_ID" --trailer "Release-Source: $CI_COMMIT_SHA")
  if [[ "${APP_ONBOARDING:-false}" == true ]]; then
    commit_args+=(--trailer "Onboarding-Pipeline: $CI_PIPELINE_ID" --trailer "Onboarding-Source: $CI_COMMIT_SHA")
  fi
  git commit "${commit_args[@]}"
  deploy_revision="$(git rev-parse HEAD)"
  git push --atomic origin "HEAD:$CI_DEFAULT_BRANCH" "refs/tags/$release_tag"
  write_release_environment
  finalize_release
}

snapshot_identity() {
  [[ "${DEPLOYMENT_ENVIRONMENT:-}" == int && "${APP_ONBOARDING:-false}" != true &&
     "${SONAR_SCAN_ONLY:-false}" != true && -z "${RELEASE_VERSION:-}" && -n "${CI_COMMIT_BRANCH:-}" &&
     -n "${CI_DEFAULT_BRANCH:-}" && "${CI_PIPELINE_ID:-}" =~ ^[0-9]+$ &&
     "${CI_COMMIT_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || fail 'Branch snapshots can only deploy to int with a source branch and pipeline identity.'
  git check-ref-format "refs/heads/$CI_COMMIT_BRANCH" >/dev/null || fail 'Invalid source branch.'
  export APP_VERSION="snapshot-$CI_COMMIT_SHA-$CI_PIPELINE_ID"
  snapshot_branch="gitops/int/$CI_PIPELINE_ID"
  snapshot_manifest="infra/snapshots/$CI_PIPELINE_ID.json"
}

verify_snapshot_source() {
  git fetch --quiet --no-tags origin "refs/heads/$CI_COMMIT_BRANCH:refs/remotes/origin/$CI_COMMIT_BRANCH"
  [[ "$(git rev-parse "refs/remotes/origin/$CI_COMMIT_BRANCH")" == "$CI_COMMIT_SHA" ]] ||
    fail 'The snapshot source branch advanced; start a pipeline for its latest revision.'
}

load_snapshot() {
  local tip publication manifest
  git fetch --quiet --no-tags origin "refs/heads/$snapshot_branch:refs/remotes/origin/$snapshot_branch"
  tip="$(git rev-parse "refs/remotes/origin/$snapshot_branch")"
  git merge-base --is-ancestor "$CI_COMMIT_SHA" "$tip" || fail 'The snapshot branch belongs to another source.'
  publication="$(git rev-list --reverse --first-parent "$CI_COMMIT_SHA..$tip" | sed -n '1p')"
  [[ -n "$publication" && "$(git rev-list --parents -n 1 "$publication")" == "$publication $CI_COMMIT_SHA" &&
     "$(trailer "$publication" Snapshot-Pipeline)" == "$CI_PIPELINE_ID" &&
     "$(trailer "$publication" Snapshot-Source)" == "$CI_COMMIT_SHA" &&
     "$(git rev-parse "$publication:$snapshot_manifest")" == "$(git rev-parse "$tip:$snapshot_manifest")" ]] ||
    fail 'The snapshot publication receipt is invalid.'
  manifest="$(git show "$publication:$snapshot_manifest")"
  jq -e --arg version "$APP_VERSION" --arg source "$CI_COMMIT_SHA" --arg pipeline "$CI_PIPELINE_ID" \
    --arg branch "$CI_COMMIT_BRANCH" --arg prefix "$CI_REGISTRY/$CI_PROJECT_PATH/" '
    .version == 1 and .kind == "snapshot" and .releaseVersion == $version and .sourceRevision == $source and
    .pipelineId == $pipeline and .sourceBranch == $branch and
    (.images | keys) == ["swirlapp-order", "swirlapp-user", "swirlapp-web"] and
    (.images | to_entries | all(.value.repository == ($prefix + .key) and (.value.digest | test("^sha256:[0-9a-f]{64}$"))))' \
    <<<"$manifest" >/dev/null || fail 'Invalid snapshot image or source provenance.'
  mkdir -p package-output
  printf '%s\n' "$manifest" > package-output/deployment-release.json
  SNAPSHOT_PUBLICATION="$publication"
}

publish_snapshot() {
  snapshot_identity
  verify_snapshot_source
  if [[ -n "$(git ls-remote --heads origin "refs/heads/$snapshot_branch")" ]]; then
    load_snapshot
    printf 'Resuming this pipeline snapshot without rebuilding its images.\n'
  else
    [[ "$(git rev-parse HEAD)" == "$CI_COMMIT_SHA" ]] || fail 'Snapshot publication must start from the selected source commit.'
    : "${CI_REGISTRY:?CI_REGISTRY is required}"
    git fetch --quiet --no-tags origin "refs/heads/$CI_DEFAULT_BRANCH:refs/remotes/origin/$CI_DEFAULT_BRANCH"
    # Keep branch code, but take current public target choices from onboarding on
    # the default branch. Never replace another environment's desired state.
    git restore --source "refs/remotes/origin/$CI_DEFAULT_BRANCH" -- \
      infra/deployment-environments.json infra/argocd/application.yaml infra/environments/int/settings.json
    infra/scripts/ci-container-build.sh publish
    write_manifest "$snapshot_manifest" snapshot
    verify_snapshot_source
    git checkout --quiet -B "$snapshot_branch" "$CI_COMMIT_SHA"
    configure_git_identity
    git add -- "$snapshot_manifest" infra/deployment-environments.json infra/argocd/application.yaml \
      infra/environments/int/settings.json
    git commit --quiet -m "snapshot: publish $APP_VERSION [skip ci]" \
      --trailer "Snapshot-Pipeline: $CI_PIPELINE_ID" --trailer "Snapshot-Source: $CI_COMMIT_SHA"
    git push origin "HEAD:refs/heads/$snapshot_branch"
    load_snapshot
  fi
  printf 'APP_VERSION=%s\nSNAPSHOT_VERSION=%s\nSNAPSHOT_BRANCH=%s\n' \
    "$APP_VERSION" "$APP_VERSION" "$snapshot_branch" > release.env
}

smoke_curl() {
  local url="$1" deadline response
  deadline=$(( $(date +%s) + 120 ))
  while true; do
    if response="$(curl --fail --silent --show-error --connect-timeout 5 --max-time 15 "$url")"; then
      printf '%s' "$response"
      return 0
    fi
    [[ "$(date +%s)" -lt "$deadline" ]] || return 1
    printf 'Waiting for smoke-check endpoint %s.\n' "$url" >&2
    sleep 5
  done
}

deployment_renderer() {
  python3 infra/scripts/render-deployment.py --inventory infra/deployment-environments.json \
    --environment "$DEPLOYMENT_ENVIRONMENT" --release package-output/deployment-release.json \
    --revision "$DEPLOY_REVISION" --output . --verify-live "$@"
}

load_deployment_release() {
  local tag manifest existing_release
  [[ "$APP_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail 'Select a canonical release version, for example 1.2.3.'
  tag="v$APP_VERSION"
  git fetch --quiet --no-tags origin "refs/tags/$tag:refs/tags/$tag"
  [[ "$(git cat-file -t "refs/tags/$tag")" == tag ]] || fail 'The selected release tag must be annotated.'
  RELEASE_REVISION="$(git rev-parse "refs/tags/$tag^{commit}")"
  manifest="$(git show "$RELEASE_REVISION:infra/releases/$APP_VERSION.json")"
  [[ "$(jq -r '.releaseVersion' <<<"$manifest")" == "$APP_VERSION" &&
     "$(jq -r '.kind // "release"' <<<"$manifest")" == release &&
     "$(git show "$RELEASE_REVISION:VERSION")" == "$APP_VERSION" ]] || fail 'The tag and release manifest disagree.'
  [[ "$(git rev-list --parents -n 1 "$RELEASE_REVISION")" == "$RELEASE_REVISION $(jq -r '.sourceRevision' <<<"$manifest")" ]] ||
    fail 'The immutable release must directly record its published source.'
  git fetch --quiet --no-tags origin "refs/heads/$CI_DEFAULT_BRANCH:refs/remotes/origin/$CI_DEFAULT_BRANCH"
  git merge-base --is-ancestor "$RELEASE_REVISION" "refs/remotes/origin/$CI_DEFAULT_BRANCH" ||
    fail 'The release was not published on the default branch.'
  git merge-base --is-ancestor "$(jq -r '.sourceRevision' <<<"$manifest")" "$RELEASE_REVISION" ||
    fail 'Release source is not an ancestor of its tag.'
  existing_release="$(curl --fail --show-error --silent --header "JOB-TOKEN: $CI_JOB_TOKEN" \
    "$CI_API_V4_URL/projects/$CI_PROJECT_ID/releases/$tag")"
  jq -e --arg tag "$tag" --arg revision "$RELEASE_REVISION" '.tag_name == $tag and .commit.id == $revision' \
    <<<"$existing_release" >/dev/null || fail 'GitLab has not finalized this exact immutable release.'
  mkdir -p package-output
  printf '%s\n' "$manifest" > package-output/deployment-release.json
}

verify_application_owner() {
  local current
  current="$(timeout 60 kubectl get application "$application_name" -n infra --ignore-not-found -o json)"
  [[ -n "$current" ]] || return 0
  local deployed_pipeline
  deployed_pipeline="$(jq -r '.metadata.annotations["swirl-demo-app.delivery/pipeline"] // "0"' <<<"$current")"
  [[ "$deployed_pipeline" =~ ^[0-9]+$ && "$deployed_pipeline" -le "$CI_PIPELINE_ID" ]] ||
    fail 'A newer pipeline already deployed this environment; start a new pipeline instead of overwriting it.'
  jq -e --slurpfile metadata package-output/deployment.json \
    --arg repository "$repository_url" --arg path "infra/environments/$DEPLOYMENT_ENVIRONMENT" \
    --arg project "applications-$DEPLOYMENT_ENVIRONMENT" '
    .spec.destination == $metadata[0].destination and .spec.source.repoURL == $repository and
    .spec.source.path == $path and .spec.project == $project' <<<"$current" >/dev/null ||
    fail 'The existing Application belongs to another target or repository; review its ownership.'
}

uat_release() {
  # prod never deploys directly: it promotes the release uat is verified to run.
  local pin version runtime application
  git fetch --quiet --no-tags origin "refs/heads/$CI_DEFAULT_BRANCH:refs/remotes/origin/$CI_DEFAULT_BRANCH"
  pin="$(git log -1 --format=%H --grep='^deploy: pin uat to ' "refs/remotes/origin/$CI_DEFAULT_BRANCH")"
  [[ -n "$pin" && "$(trailer "$pin" Deployment-Environment)" == uat ]] ||
    fail 'prod promotes only the release deployed to uat; deploy a release to uat first.'
  version="$(trailer "$pin" Deployment-Release)"
  runtime="$(trailer "$pin" Deployment-Runtime)"
  [[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ && "$runtime" =~ ^[0-9a-f]{40}$ ]] ||
    fail 'The uat deployment record is incomplete.'
  application="$(timeout 60 kubectl get application swirl-demo-app-uat -n infra -o json)" ||
    fail 'Cannot verify the uat deployment.'
  jq -e --arg runtime "$runtime" '.spec.source.targetRevision == $runtime and .status.sync.revision == $runtime and
    .status.sync.status == "Synced" and .status.health.status == "Healthy"' <<<"$application" >/dev/null ||
    fail "uat is not Synced/Healthy on release $version; prod promotes only a verified uat release."
  printf '%s\n' "$version"
}

deploy_release() {
  local selected_version="${RELEASE_VERSION:-}" original_revision tip application_name repository_url pointer_file
  local delivery_branch snapshot=false snapshot_branch snapshot_manifest SNAPSHOT_PUBLICATION
  [[ "${DEPLOYMENT_ENVIRONMENT:-}" =~ ^(int|uat|prod)$ ]] || fail 'Select int, uat or prod.'
  [[ "${SONAR_SCAN_ONLY:-false}" != true && -n "${CI_DEFAULT_BRANCH:-}" && -n "${CI_COMMIT_BRANCH:-}" ]] ||
    fail 'Deployment requires a branch delivery pipeline.'
  [[ "${CI_PIPELINE_ID:-}" =~ ^[0-9]+$ && "${CI_COMMIT_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || fail 'Missing pipeline identity.'
  if [[ "$DEPLOYMENT_ENVIRONMENT" == prod ]]; then
    [[ "$CI_COMMIT_BRANCH" == "$CI_DEFAULT_BRANCH" ]] || fail 'Release deployment requires the default branch.'
    local uat_version
    uat_version="$(uat_release)" || exit 1
    [[ -z "$selected_version" || "$selected_version" == "$uat_version" ]] ||
      fail "prod promotes only the release running on uat ($uat_version), not $selected_version."
    selected_version="$uat_version"
  fi
  if [[ -f release.env && -z "$selected_version" ]]; then
    # shellcheck disable=SC1091
    source release.env
  fi
  if [[ "$DEPLOYMENT_ENVIRONMENT" == int && "${APP_ONBOARDING:-false}" != true && -z "$selected_version" ]]; then
    snapshot=true
    snapshot_identity
    verify_snapshot_source
    load_snapshot
    delivery_branch="$snapshot_branch"
  else
    [[ "$CI_COMMIT_BRANCH" == "$CI_DEFAULT_BRANCH" ]] || fail 'Release deployment requires the default branch.'
    APP_VERSION="${selected_version:-${APP_VERSION:-}}"
    load_deployment_release
    delivery_branch="$CI_DEFAULT_BRANCH"
  fi
  git fetch --quiet --no-tags origin "refs/heads/$delivery_branch:refs/remotes/origin/$delivery_branch"
  tip="$(git rev-parse "refs/remotes/origin/$delivery_branch")"
  original_revision="$tip"
  pointer_file="infra/argocd/$DEPLOYMENT_ENVIRONMENT.yaml"
  if [[ "$(trailer "$tip" Deployment-Pipeline)" == "$CI_PIPELINE_ID" &&
        "$(trailer "$tip" Deployment-Source)" == "$CI_COMMIT_SHA" &&
        "$(trailer "$tip" Deployment-Environment)" == "$DEPLOYMENT_ENVIRONMENT" &&
        "$(trailer "$tip" Deployment-Release)" == "$APP_VERSION" ]]; then
    DEPLOY_REVISION="$(trailer "$tip" Deployment-Runtime)"
    [[ "$DEPLOY_REVISION" =~ ^[0-9a-f]{40}$ && "$(git rev-parse "$tip^")" == "$DEPLOY_REVISION" ]] ||
      fail 'This pipeline has an invalid deployment receipt.'
    git checkout --quiet -B "$delivery_branch" "$tip"
    deployment_renderer --check-only --verify-live > package-output/deployment.json
    # Re-render the receipt and compare it before a retry can touch the live Application.
    deployment_renderer > package-output/deployment.json
    git diff --quiet -- "infra/environments/$DEPLOYMENT_ENVIRONMENT" "$pointer_file" ||
      fail 'The recorded deployment no longer matches its release or environment.'
    DEPLOY_COMMIT="$tip"
  else
    if [[ "$snapshot" == true ]]; then
      [[ "$tip" == "$SNAPSHOT_PUBLICATION" ]] || fail 'The snapshot branch advanced without this pipeline deployment receipt.'
    elif [[ "$tip" != "$CI_COMMIT_SHA" ]]; then
      [[ -z "$selected_version" && "${PUBLICATION_COMMIT:-}" == "$tip" &&
         "$(trailer "$tip" Release-Pipeline)" == "$CI_PIPELINE_ID" &&
         "$(trailer "$tip" Release-Source)" == "$CI_COMMIT_SHA" ]] ||
        fail 'The branch advanced after this pipeline; start a new promotion pipeline against its latest state.'
    fi
    git checkout --quiet -B "$delivery_branch" "$tip"
    DEPLOY_REVISION="$tip"
    deployment_renderer --verify-live > package-output/deployment.json
    application_name="$(jq -r '.application' package-output/deployment.json)"
    repository_url="$(jq -r '.repository' package-output/deployment.json)"
    verify_application_owner
    configure_git_identity
    git add -- "infra/environments/$DEPLOYMENT_ENVIRONMENT"
    git commit --quiet --allow-empty -m "deploy: select $APP_VERSION for $DEPLOYMENT_ENVIRONMENT [skip ci]"
    DEPLOY_REVISION="$(git rev-parse HEAD)"
    deployment_renderer > package-output/deployment.json
    git add -- "$pointer_file"
    env APP_VERSION="$APP_VERSION" DEPLOYMENT_RUNTIME_REVISION="$DEPLOY_REVISION" \
      infra/scripts/commit-deployment.sh "deploy: pin $DEPLOYMENT_ENVIRONMENT to $APP_VERSION [skip ci]"
    DEPLOY_COMMIT="$(git rev-parse HEAD)"
    [[ "$(git ls-remote origin "refs/heads/$delivery_branch" | cut -f1)" == "$original_revision" ]] ||
      fail 'A concurrent delivery changed the branch; rerun from its new state.'
    git push origin "HEAD:refs/heads/$delivery_branch"
  fi
  DEPLOY_REVISION="$DEPLOY_REVISION" DEPLOY_COMMIT="$DEPLOY_COMMIT" infra/scripts/check-onboarding-revision.sh deploy
  deployment_renderer --check-only --verify-live > package-output/deployment.json
  application_name="$(jq -r '.application' package-output/deployment.json)"
  repository_url="$(jq -r '.repository' package-output/deployment.json)"
  verify_application_owner
  kubectl apply -f "$pointer_file"
  kubectl annotate application "$application_name" -n infra argocd.argoproj.io/refresh=hard --overwrite
  local deadline application success=false
  deadline=$(( $(date +%s) + 900 ))
  while [[ "$(date +%s)" -lt "$deadline" ]]; do
    application="$(kubectl get application "$application_name" -n infra -o json)"
    jq -e --slurpfile metadata package-output/deployment.json --arg repository "$repository_url" \
      --arg path "infra/environments/$DEPLOYMENT_ENVIRONMENT" --arg project "applications-$DEPLOYMENT_ENVIRONMENT" '
      .spec.destination == $metadata[0].destination and .spec.source.repoURL == $repository and
      .spec.source.path == $path and .spec.source.targetRevision == $metadata[0].revision and
      .spec.project == $project' <<<"$application" >/dev/null ||
      fail 'The Application target or pinned revision changed during deployment.'
    if jq -e --slurpfile metadata package-output/deployment.json '
      .status.sync.revision == $metadata[0].revision and .status.sync.status == "Synced" and
      .status.health.status == "Healthy" and (.status.summary.images as $actual |
      $metadata[0].images | all(. as $image | $actual | index($image))) and
      ([.status.resources[]? | select(.kind == "Deployment" and .status == "Synced") | .name] as $ready |
      ["swirlapp-user", "swirlapp-order", "swirlapp-web"] | all(. as $name | $ready | index($name)))' <<<"$application" >/dev/null; then
      success=true
      break
    fi
    printf 'Waiting for %s at %s to become Synced/Healthy with its selected image digests.\n' "$application_name" "$DEPLOY_REVISION"
    sleep 10
  done
  [[ "$success" == true ]] || fail 'Argo CD did not confirm the selected target, revision and image digests.'
  local deployment_url
  deployment_url="$(jq -r '.url' package-output/deployment.json)"
  smoke_curl "$deployment_url/" | grep -q '<app-root' || fail 'The selected public route did not serve Swirl Demo App.'
  smoke_curl "$deployment_url/runtime-config.json" | \
    jq -e --slurpfile metadata package-output/deployment.json '. == $metadata[0].runtimeConfig' >/dev/null ||
    fail 'The selected environment served another runtime configuration.'
  local service
  for service in user order; do
    smoke_curl "$deployment_url/health/$service" | jq -e '.status == "UP"' >/dev/null ||
      fail "The selected environment's $service API is unhealthy."
  done
  printf 'APP_VERSION=%s\nDEPLOY_REVISION=%s\nDEPLOY_COMMIT=%s\nDEPLOYMENT_URL=%s\n' \
    "$APP_VERSION" "$DEPLOY_REVISION" "$DEPLOY_COMMIT" "$deployment_url" > release.env
}

validate_delivery_request() {
  if [[ -n "${NEW_MAJOR_VERSION:-}" ]]; then
    [[ "${PIPELINE_MODE:-standard}" == standard && -z "${RELEASE_VERSION:-}" &&
       "${APP_ONBOARDING:-false}" != true && -n "${CI_DEFAULT_BRANCH:-}" &&
       "${CI_COMMIT_BRANCH:-}" == "$CI_DEFAULT_BRANCH" ]] ||
      fail 'A major-version change requires a separate standard pipeline on the default branch, without release promotion or onboarding.'
  fi
  [[ "${DEPLOYMENT_ENVIRONMENT:-int}" =~ ^(int|uat|prod)$ ]] || fail 'Select int, uat or prod.'
  if [[ -n "${RELEASE_VERSION:-}" ]]; then
    [[ "$RELEASE_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
      fail 'RELEASE_VERSION must select a finalized major.minor.patch release; snapshots deploy only from a branch to int.'
    [[ -n "${CI_DEFAULT_BRANCH:-}" && "${CI_COMMIT_BRANCH:-}" == "$CI_DEFAULT_BRANCH" ]] ||
      fail 'Select the default branch to promote a finalized release.'
  elif [[ "${DEPLOYMENT_ENVIRONMENT:-int}" == prod ]]; then
    [[ -n "${CI_DEFAULT_BRANCH:-}" && "${CI_COMMIT_BRANCH:-}" == "$CI_DEFAULT_BRANCH" ]] ||
      fail 'prod promotes the release running on uat; select the default branch.'
  elif [[ "${DEPLOYMENT_ENVIRONMENT:-int}" != int ]]; then
    [[ -n "${CI_DEFAULT_BRANCH:-}" && "${CI_COMMIT_BRANCH:-}" == "$CI_DEFAULT_BRANCH" ]] ||
      fail 'uat requires a finalized release; select the default branch to publish or promote one. Branch snapshots deploy to int.'
    [[ "$(cat VERSION)" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
      fail 'uat cannot publish a SNAPSHOT baseline; select a finalized RELEASE_VERSION.'
  fi
}

case "$phase" in
  validate)
    validate_delivery_request
    ;;
  publish)
    publish_release
    ;;
  snapshot)
    publish_snapshot
    ;;
  deploy)
    deploy_release
    ;;
  all)
    publish_release
    deploy_release
    ;;
  *)
    printf 'Usage: %s [validate|publish|snapshot|deploy|all]\n' "$0" >&2
    exit 2
    ;;
esac
