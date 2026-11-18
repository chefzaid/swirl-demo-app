# ADR 0009: Use Explicit Delivery Jobs And Non-Blocking Verification

## Status

Accepted. This supersedes the job topology in ADR 0007 while retaining its GitOps, daemonless Kaniko, and immutable-tag decisions.

## Decision

Expose GitLab jobs named `01-build`, `02-test`, `03-package`, `01-e2e`, `02-quality`, `03-security`, `01-release`, `02-deploy`, and `set-major-version`. Numeric prefixes preserve the intended order in GitLab's alphabetically sorted stage boxes. Build compiles the application, optional tests own unit and coverage results, and package validates the runtime images.

`01-e2e` is optional and manual. `02-quality` consumes the test artifacts directly, submits SonarQube analysis without waiting for its gate, and retains dependency-audit reports. Default-branch quality is automatic and non-blocking in both standard and full mode. `03-security` independently runs Trivy over dependencies, IaC, and secrets and retains JSON/SARIF reports; it is manual in standard mode and automatic but non-blocking in full mode. No verify job is a release dependency. `01-release` requires the compiled artifacts and successful package validation, publishes images/packages and Git release state, and `02-deploy` runs only after release passes.

Normal pipelines run `01-build`, `02-test`, and `03-package` automatically and expose E2E, security, and release manually. Default-branch `02-quality` is also automatic. A default-branch pipeline started with `PIPELINE_MODE=full` additionally automates security reporting, release and deploy after the required build path; E2E remains optional and manual.

Keep a `VERSION` baseline in Git. Increment patch deterministically from new commits, tag the exact version deployed, prepare the next minor baseline after release, and reset to `<major>.0.0` only through the major-version job. Synchronize Maven and npm manifests when a version baseline changes.

Keep Sonar, GitLab project metadata, labels, badges, branch protection, CI variables, and retention automation in repository-owned scripts. Keep secrets in Vault and GitLab masked variables.

## Consequences

- Every operation has an accurate job name, status, log, duration, and retry action.
- Compilation and package failures stop delivery; test, coverage, browser, Sonar, and dependency findings remain visible without becoming release gates.
- Release reuses build outputs instead of compiling and scanning a second time.
- Thirty-day registry-backed Kaniko layers and dependency/analyzer caches reduce repeated work.
- SonarQube Community Build analyzes only `main`; branch and merge-request pipelines still retain local test, coverage, and dependency reports.

## Amendment: automatic namespace coverage (2026-09-07)

Default-branch `02-quality` is automatic in standard and full pipelines. The
platform discovers repositories from Argo-owned workloads in `apps`, provisions
Sonar credentials and requests first scans or refreshes of analyses older than
24 hours. Manual scans remain available by starting a default-branch pipeline
with `SONAR_SCAN_ONLY=true`. `.sonar-auto.json` declares this contract:
only build, test and quality run in scan-only pipelines. Package, E2E,
security, release, deploy and version jobs are absent. Failed scanner submissions
fail the non-blocking quality job visibly; quality gates do not authorize release.

The [code-quality guide](../code-quality.md) describes how template adopters
preserve this contract while changing repository names, source paths and build
tools. [ADR 0008](0008-code-quality-and-verification.md#amendment-source-analysis-and-discovery-2026-09-08)
records the source-analysis decision and rationale.

## Amendment: environment jobs and versioned images (2026-11-18)

The `int`/`uat`/`prod` delivery keeps the jobs above and adds `00-delivery-policy`,
`01-snapshot`, `01-publish` and `02-deploy-uat`. `03-package` verifies the three
runtime images in every build pipeline before `01-snapshot`, `01-release` or
`01-publish` builds and pushes them. A pipeline with `PLAYWRIGHT_SCHEDULE=true`
runs build, test, package and E2E, with E2E required, and nothing after. Each
environment deploys its images as `<image>:<version>@sha256:<digest>`, so the
digest stays the deployed identity and the tag shows the version. The
[pipeline jobs](../deployment.md#pipeline-jobs) table is the current reference.
