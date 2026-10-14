#!/usr/bin/env bash
# Run the visual regression check in the pinned Playwright image, the only
# place its baselines are valid. --update rewrites the baselines that differ.
set -euo pipefail

web="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../swirlapp-web" && pwd)"
script=test:visual
if [[ "${1:-}" == --update ]]; then
  script=test:visual:update
elif [[ $# -gt 0 ]]; then
  printf 'Usage: %s [--update]\n' "$0" >&2
  exit 2
fi

# The container runs as root: keep its Linux node_modules in a named volume
# (a user-owned mount point keeps the host's own npm ci working) and hand
# what it writes into the checkout back to the caller.
mkdir -p "$web/node_modules"
docker run --rm --init --ipc=host -e CI=true \
  -v "$web":/work -w /work -v swirl-demo-app-visual-modules:/work/node_modules \
  mcr.microsoft.com/playwright:v1.64.0-noble \
  sh -c "npm ci && npm run $script; status=\$?
    chown -R $(id -u):$(id -g) test-results playwright-report e2e/visual.spec.ts-snapshots 2>/dev/null
    exit \$status"
