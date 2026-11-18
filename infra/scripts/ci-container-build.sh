#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository_root"

mode="${1:-verify}"
: "${APP_VERSION:?APP_VERSION is required}"
: "${REGISTRY_PUSH_HOST:?REGISTRY_PUSH_HOST is required}"
: "${KANIKO_EXECUTOR:?KANIKO_EXECUTOR is required}"

kaniko_options=()
mirror_options=()
# Credentials reach jq through the environment, never the command line or the log.
auths='{}'
add_auth() {
  auths="$(REGISTRY_HOST="$1" REGISTRY_USERNAME="$2" REGISTRY_PASSWORD="$3" jq -c \
    '. + {(env.REGISTRY_HOST): {username: env.REGISTRY_USERNAME, password: env.REGISTRY_PASSWORD}}' <<<"$auths")"
}

# Docker Hub base images come through the GitLab group's Dependency Proxy, which
# avoids Docker Hub's anonymous pull limit; Kaniko falls back to Docker Hub if the
# mirror fails. The proxy answers on its server with and without :443.
if [[ -n "${CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX:-}" && -n "${CI_DEPENDENCY_PROXY_SERVER:-}" &&
      -n "${CI_DEPENDENCY_PROXY_USER:-}" && -n "${CI_DEPENDENCY_PROXY_PASSWORD:-}" ]]; then
  mirror_options+=(--registry-mirror "$CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX")
  add_auth "$CI_DEPENDENCY_PROXY_SERVER" "$CI_DEPENDENCY_PROXY_USER" "$CI_DEPENDENCY_PROXY_PASSWORD"
  add_auth "${CI_DEPENDENCY_PROXY_SERVER%:443}" "$CI_DEPENDENCY_PROXY_USER" "$CI_DEPENDENCY_PROXY_PASSWORD"
fi

case "$mode" in
  verify)
    kaniko_options+=(--no-push --cache=false)
    ;;
  publish)
    : "${CI_REGISTRY_USER:?CI_REGISTRY_USER is required}"
    : "${CI_REGISTRY_PASSWORD:?CI_REGISTRY_PASSWORD is required}"
    add_auth "$REGISTRY_PUSH_HOST" "$CI_REGISTRY_USER" "$CI_REGISTRY_PASSWORD"
    mkdir -p package-output/image-digests
    ;;
  *)
    printf 'Usage: %s [verify|publish]\n' "$0" >&2
    exit 2
    ;;
esac

if [[ "$auths" != '{}' ]]; then
  mkdir -p /kaniko/.docker
  (umask 077 && jq '{auths: .}' <<<"$auths" > /kaniko/.docker/config.json)
  export DOCKER_CONFIG=/kaniko/.docker
fi

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
    "${mirror_options[@]}"
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
