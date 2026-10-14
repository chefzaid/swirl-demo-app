#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository_root"

phase="${1:-all}"

prepare_sources() {
  infra/scripts/set-project-version.sh "$APP_VERSION"
}

build_application() {
  infra/scripts/check-onboarding-revision.sh build
  python3 infra/scripts/test-onboarding.py
  python3 infra/scripts/test-release.py
  python3 infra/scripts/test-keycloak.py
  python3 infra/scripts/test-deployment.py
  python3 infra/scripts/test-doc-links.py
  python3 infra/scripts/check-doc-links.py
  npx --yes markdownlint-cli2@0.23.3
  kubectl kustomize infra/k8s >/dev/null
  kubectl kustomize infra/overlays/ha >/dev/null
  python3 infra/scripts/test-manifest-policy.py
  python3 infra/scripts/check-manifest-policy.py
  promtool check rules infra/observability/slo-rules.yaml
  promtool test rules infra/observability/slo-rules.test.yaml
  kubectl create --dry-run=client --validate=false \
    -f infra/argocd/application.yaml >/dev/null
  mvn --batch-mode clean package -DskipTests \
    -Dhttp.proxyHost= -Dhttps.proxyHost=

  pushd swirlapp-web >/dev/null
  npm ci
  npm run test:e2e:types
  npm run build-prod
  tar -czf "swirlapp-web-${APP_VERSION}.tar.gz" -C dist swirlapp-web
  popd >/dev/null
}

test_application() {
  mvn --batch-mode clean verify -Djacoco.haltOnFailure=false \
    -Dhttp.proxyHost= -Dhttps.proxyHost=

  pushd swirlapp-web >/dev/null
  npm ci
  npm run test:ci -- --coverage-reporters=cobertura
  coverage_report="coverage/swirlapp-web/cobertura-coverage.xml"
  [[ -s "$coverage_report" ]]
  coverage_line_rate="$(awk 'match($0, /line-rate="[^"]+"/) { print substr($0, RSTART + 11, RLENGTH - 12); exit }' "$coverage_report")"
  [[ "$coverage_line_rate" =~ ^[0-9]+([.][0-9]+)?$ ]]
  awk -v rate="$coverage_line_rate" 'BEGIN { printf "FRONTEND_COVERAGE=%.2f%%\n", rate * 100 }'
  popd >/dev/null

  python3 infra/scripts/ci-coverage-check.py \
    ./*/target/site/jacoco/jacoco.xml \
    swirlapp-web/coverage/swirlapp-web/cobertura-coverage.xml
}

prepare_sources
case "$phase" in
  build)
    build_application
    ;;
  test)
    test_application
    ;;
  all)
    build_application
    test_application
    ;;
  *)
    printf 'Usage: %s [build|test|all]\n' "$0" >&2
    exit 2
    ;;
esac
