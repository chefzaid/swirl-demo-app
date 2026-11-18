#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository_root"

phase="${1:-all}"
# CI runs build and test once per suite (01-build: [server], 02-test: [web], ...);
# without a suite, both run in turn.
suite="${2:-${SUITE:-all}}"

# The CPUs this job may use: its cgroup quota, not the host threads that nproc
# and os.availableParallelism() report inside a CI pod.
job_cpus() {
  local quota period
  if read -r quota period < /sys/fs/cgroup/cpu.max 2>/dev/null &&
     [[ "$quota" =~ ^[0-9]+$ && "$period" =~ ^[1-9][0-9]*$ ]]; then
    printf '%s\n' "$(( (quota + period - 1) / period ))"
  else
    nproc
  fi
}
cpus="$(job_cpus)"
workers=$(( cpus > 1 ? cpus - 1 : 1 ))
# Angular's build and Vitest size their worker pools from the host's threads.
export NG_BUILD_MAX_WORKERS="${NG_BUILD_MAX_WORKERS:-$workers}"
export VITEST_MAX_WORKERS="${VITEST_MAX_WORKERS:-$workers}"
# One builder thread per CPU the JVM sees (it reads the quota); user and order
# build in parallel once common is done. Surefire's forks are set in pom.xml.
maven=(mvn --batch-mode --threads 1C -Dhttp.proxyHost= -Dhttps.proxyHost=)

prepare_sources() {
  infra/scripts/set-project-version.sh "$APP_VERSION"
}

check_repository() {
  python3 infra/scripts/test-onboarding.py
  python3 infra/scripts/test-release.py
  python3 infra/scripts/test-keycloak.py
  python3 infra/scripts/test-deployment.py
  python3 infra/scripts/test-promotion.py
  python3 infra/scripts/test-promote-prod.py
  python3 infra/scripts/test-doc-links.py
  python3 infra/scripts/check-doc-links.py
  python3 infra/scripts/test-playwright-images.py
  python3 infra/scripts/check-playwright-images.py
  python3 infra/scripts/test-compose-images.py
  python3 infra/scripts/check-compose-images.py
  python3 infra/scripts/test-identity.py
  python3 infra/scripts/check-identity.py
  npx --yes markdownlint-cli2@0.23.3
  kubectl kustomize infra/k8s >/dev/null
  kubectl kustomize infra/overlays/ha >/dev/null
  kubectl kustomize infra/overlays/canary >/dev/null
  python3 infra/scripts/test-manifest-policy.py
  python3 infra/scripts/check-manifest-policy.py
  promtool check rules infra/observability/slo-rules.yaml
  promtool test rules infra/observability/slo-rules.test.yaml
  kubectl create --dry-run=client --validate=false \
    -f infra/argocd/application.yaml >/dev/null
}

build_server() {
  check_repository
  "${maven[@]}" clean package -DskipTests
}

build_web() {
  pushd swirlapp-web >/dev/null
  npm ci
  npm run test:e2e:types
  npm run build-prod
  tar -czf "swirlapp-web-${APP_VERSION}.tar.gz" -C dist swirlapp-web
  popd >/dev/null
}

test_server() {
  "${maven[@]}" clean verify -Djacoco.haltOnFailure=false
}

test_web() {
  pushd swirlapp-web >/dev/null
  npm ci
  npm run test:ci -- --coverage-reporters=cobertura
  coverage_report="coverage/swirlapp-web/cobertura-coverage.xml"
  [[ -s "$coverage_report" ]]
  coverage_line_rate="$(awk 'match($0, /line-rate="[^"]+"/) { print substr($0, RSTART + 11, RLENGTH - 12); exit }' "$coverage_report")"
  [[ "$coverage_line_rate" =~ ^[0-9]+([.][0-9]+)?$ ]]
  awk -v rate="$coverage_line_rate" 'BEGIN { printf "FRONTEND_COVERAGE=%.2f%%\n", rate * 100 }'
  popd >/dev/null
}

# Needs both suites' reports, so CI runs it in 04-coverage after both test jobs.
check_coverage() {
  python3 infra/scripts/ci-coverage-check.py \
    ./*/target/site/jacoco/jacoco.xml \
    swirlapp-web/coverage/swirlapp-web/cobertura-coverage.xml
}

usage() {
  printf 'Usage: %s [build|test|coverage|all] [server|web|all]\n' "$0" >&2
  exit 2
}

run_suites() {
  case "$suite" in
    server|web)
      "${1}_$suite"
      ;;
    all)
      "${1}_server"
      "${1}_web"
      ;;
    *)
      usage
      ;;
  esac
}

case "$phase" in
  build)
    prepare_sources
    infra/scripts/check-onboarding-revision.sh build
    run_suites build
    ;;
  test)
    prepare_sources
    run_suites test
    if [[ "$suite" == all ]]; then
      check_coverage
    fi
    ;;
  coverage)
    check_coverage
    ;;
  all)
    prepare_sources
    infra/scripts/check-onboarding-revision.sh build
    run_suites build
    run_suites test
    check_coverage
    ;;
  *)
    usage
    ;;
esac
