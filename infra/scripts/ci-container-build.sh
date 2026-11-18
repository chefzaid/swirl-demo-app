#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository_root"

mode="${1:-verify}"
: "${APP_VERSION:?APP_VERSION is required}"
: "${REGISTRY_PUSH_HOST:?REGISTRY_PUSH_HOST is required}"
: "${KANIKO_EXECUTOR:?KANIKO_EXECUTOR is required}"

kaniko_options=()
case "$mode" in
  verify)
    kaniko_options+=(--no-push --cache=false)
    ;;
  publish)
    : "${CI_REGISTRY_USER:?CI_REGISTRY_USER is required}"
    : "${CI_REGISTRY_PASSWORD:?CI_REGISTRY_PASSWORD is required}"
    mkdir -p /kaniko/.docker
    jq -n --arg registry "$REGISTRY_PUSH_HOST" --arg username "$CI_REGISTRY_USER" \
      --arg password "$CI_REGISTRY_PASSWORD" \
      '{auths:{($registry):{username:$username,password:$password}}}' \
      > /kaniko/.docker/config.json
    export DOCKER_CONFIG=/kaniko/.docker
    mkdir -p package-output/image-digests
    ;;
  *)
    printf 'Usage: %s [verify|publish]\n' "$0" >&2
    exit 2
    ;;
esac

# IMAGE_TAG optionally adds the version tag a deployment shows (snapshots keep
# their snapshot-<commit>-<pipeline> identity tag as well).
image_tag="${IMAGE_TAG:-$APP_VERSION}"
[[ "$image_tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || {
  printf 'Invalid image tag: %s\n' "$image_tag" >&2
  exit 2
}

build_image() {
  local name="$1" context="$2" dockerfile="$3"
  local repository="$REGISTRY_PUSH_HOST/$CI_PROJECT_PATH/$name"
  local options=(
    --context "dir://$context"
    --dockerfile "$dockerfile"
    --destination "$repository:$APP_VERSION"
    --insecure-registry "$REGISTRY_PUSH_HOST"
  )
  if [[ "$image_tag" != "$APP_VERSION" ]]; then
    options+=(--destination "$repository:$image_tag")
  fi
  if [[ "$mode" == publish ]]; then
    options+=(--cache=true --cache-repo "$repository/cache" --cache-ttl=720h
      --digest-file "$repository_root/package-output/image-digests/$name")
  else
    options+=("${kaniko_options[@]}")
  fi
  "$KANIKO_EXECUTOR" "${options[@]}"
  if [[ "$mode" == publish ]]; then
    grep -Eq '^sha256:[0-9a-f]{64}$' "$repository_root/package-output/image-digests/$name"
  fi
}

build_image swirlapp-user "$repository_root" "$repository_root/swirlapp-user/Dockerfile.runtime"
build_image swirlapp-order "$repository_root" "$repository_root/swirlapp-order/Dockerfile.runtime"
build_image swirlapp-web "$repository_root/swirlapp-web" "$repository_root/swirlapp-web/Dockerfile.runtime"
