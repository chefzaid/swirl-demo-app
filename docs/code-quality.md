# Code Quality

Swirl Demo App combines tests and coverage with backend/frontend Sonar analysis and
dependency reports. This guide covers what a service built from this template
accepts today, manual scans, automatic analysis and the contract to preserve
when creating an application from this template. See the
[testing guide](testing.md) for test commands and
[ADR 0008](adr/0008-code-quality-and-verification.md#amendment-source-analysis-and-discovery-2026-09-08)
for the source-analysis decision and rationale.

Each repository owns its source paths, build commands, tests and scanner job.
The scheduled platform discovery below covers managed local application
namespaces, including `apps` and registered shared-cluster environments. It does not yet discover remote application clusters; those use
normal CI or manual scans. See the
[platform discovery scope](https://github.com/chefzaid/swirl-cloud/blob/main/docs/operations.md#namespace-and-discovery).

## How It Runs

The platform's `infra/sonar-apps-discovery` CronJob runs every 15 minutes. It
follows Argo CD tracking annotations from managed local workloads to their source
repositories and deduplicates components from the same repository. Swirl Demo App's web,
user and order Deployments therefore share one Sonar project, `swirlit:swirl-demo-app`.
Repositories must belong to the platform's configured GitLab hosts and group.

Discovery provisions missing Sonar projects, binds them to GitLab and supplies a
protected, masked project analysis token when `SONAR_TOKEN` is absent. Private
repositories receive private Sonar projects. Application code does not need a
Sonar administrator credential for its CI scan.

### Analysis Triggers

Manual scans remain available alongside automatic analysis:

| Trigger | When it runs |
| --- | --- |
| Manual scan | Start a default-branch pipeline with `SONAR_SCAN_ONLY=true` whenever a fresh analysis is needed. |
| Normal CI | Default-branch pipelines run `02-quality` automatically in both standard and full mode. |
| Discovery: first scan | The platform requests a scan when a discovered repository has no completed analysis. |
| Discovery: refresh | The platform requests another scan when the latest completed analysis is older than 24 hours. |

Discovery checks every 15 minutes. It requests at most one pipeline per run,
defers while an application pipeline is active and applies a six-hour retry
interval after an API-triggered attempt. The refresh threshold is therefore not
a promise of a scan at an exact time. A completed manual or normal CI analysis
also resets the age used by discovery.

Manual and discovery-triggered scan-only pipelines set `SONAR_SCAN_ONLY=true` and run
`01-build → 02-test → 02-quality`. Image packaging, E2E, Trivy, release, deployment
and repository version changes are excluded by job rules. SonarQube Community
Build analyzes the default branch (`main` here); other branches retain their
local test and dependency reports.

### Run A Manual Scan

1. Open GitLab **Build → Pipelines → Run pipeline** for the application.
2. Select the default branch (`main` in Swirl Demo App).
3. Add the pipeline variable `SONAR_SCAN_ONLY` with value `true`, then run the
   pipeline. Build, test and quality run automatically.
4. Check the `02-quality` log and the completed analysis in Sonar. You can also
   retry an existing default-branch `02-quality` job while its required test
   artifacts are still available.

Manual scans use the same source configuration, reports and credentials as
automatic scans. They do not require a new discovery run.

## Backend And Frontend Scope

[sonar-project.properties](../sonar-project.properties) includes both sides of
the application in the same analysis:

| Component | Source paths | Analysis inputs |
| --- | --- | --- |
| Java backend and shared code | `swirlapp-common/src/main`, `swirlapp-user/src/main`, `swirlapp-order/src/main` | Compiled classes from all three modules, Maven dependency JARs and service JaCoCo XML reports |
| Angular frontend | `swirlapp-web/src` | TypeScript, HTML and CSS source, plus `swirlapp-web/coverage/swirlapp-web/lcov.info` |

The [test job](../.gitlab-ci.yml) publishes classes and coverage as artifacts;
`02-quality` downloads them through `needs`. Maven dependencies come from the CI
cache. [ci-quality.sh](../infra/scripts/ci-quality.sh) submits the analysis and
retains its task reference in `quality-reports/report-task.txt`.

Source/test filters determine which files are analyzed. Coverage exclusions
affect coverage metrics; they do not exclude those files from issue analysis.
Review both when adding modules or moving source directories.

## Before You Adopt

A service built from this template today accepts the conditions below. Each
links to where it was decided or to the open roadmap item that would lift it;
none is solved yet.

- **GPL 3.0 for distributed services.** Code copied from the template keeps the
  GPL 3.0, so a service distributed to others must be released under it, with
  its source. Running the backend services for others over a network does not
  convey them, but the Angular bundle is copied to every visitor's browser, so
  its source has to be offered as well. See
  [ADR 0013](adr/0013-gpl-license.md), and have the adopting organisation's own
  legal review confirm how this applies to its services.
- **The `swirl-cloud` platform for CI and cluster delivery.** Docker Compose runs
  the complete stack on any Docker host, but the GitLab pipeline needs the
  platform's project runners (the `swirl-cloud-application-*` tags in
  [`.gitlab-ci.yml`](../.gitlab-ci.yml)) and its internal hosts
  (`REGISTRY_PUSH_HOST`, `PACKAGE_REGISTRY_API_V4_URL`, `SONAR_HOST_URL`), and
  its deploy jobs expect Argo CD in `infra`. Kubernetes delivery goes through
  the platform's
  [onboarding contract](deployment.md#add-or-reconfigure-this-repository), which
  supplies Argo CD, Vault, Traefik, Keycloak and each environment's PostgreSQL,
  Redis and Kafka. A generic Kubernetes target is open under
  [Enterprise Patterns](../TODO.md#enterprise-patterns-minimal-domain-tested-failure-modes).
- **An example you rename and remove by hand.** The user and order example
  sits beside the reusable code in both services and in `swirlapp-common`
  (`OrderEvent` and `OrderStatus` share the module with the security, Kafka and
  web infrastructure). Platform onboarding rewrites the platform settings that
  [`infra/onboarding.json`](../infra/onboarding.json) lists, such as the
  registry, GitLab project path, Sonar key, domain and Keycloak realm, but no
  script renames the group ID, the `dev.swirlit.demo` packages, the
  `swirlapp-*` services and images, or the bare `swirl-demo-app` names of the
  Argo CD Application, Kubernetes resources, Vault paths and CI cache keys.
  Both are open under
  [Template Experience](../TODO.md#priority-template-experience).
- **A pinned stack without a support matrix.** CI builds and tests on Java 25,
  Spring Boot 4 and Angular 22 only
  ([ADR 0003](adr/0003-platform-stack-and-profiles.md)). The PostgreSQL, Redis,
  Kafka and Keycloak images are pinned for the local Compose stack and exercised
  only by its manual
  [acceptance run](testing.md#containerized-full-stack-acceptance); backend tests
  use H2, a simple cache and mocks, and deployed environments use the platform's
  versions. The compatibility matrix and upgrade tests are open under
  [Template Experience](../TODO.md#priority-template-experience), and integration
  tests against real backing services under
  [Delivery And Data Correctness](../TODO.md#priority-delivery-and-data-correctness).
- **Verification that reports but does not gate.** The build job's
  compilation, packaging and repository checks (Markdown, links, manifest
  policy, Prometheus rules, delivery-script tests) and the delivery-policy check
  stop delivery, but failing unit tests, coverage below 85 percent, Sonar
  findings and high or critical Trivy findings stay visible and never block a
  snapshot or a release ([ADR 0009](adr/0009-explicit-delivery-jobs.md)).
  Opt-in quality-gate enforcement is open under
  [Testing And Quality](../TODO.md#priority-testing-and-quality), and release
  approval under [Compliance Evidence](../TODO.md#compliance-evidence).
- **No compliance evidence yet.** The optional `03-security` job scans
  dependencies, infrastructure code and secrets and publishes a CycloneDX
  source SBOM ([Secrets And Supply Chain](security.md#secrets-and-supply-chain)), but there
  is no OWASP ASVS mapping, threat model or release evidence pack to hand a
  reviewer. They are open under [Compliance Evidence](../TODO.md#compliance-evidence),
  and until they ship the template claims none of them
  ([ADR 0014](adr/0014-template-scope-and-audience.md)).

## Adapting The Template

1. Update the repository URL, Application name and source path in
   [infra/argocd/application.yaml](../infra/argocd/application.yaml). Keep the
   Application in `infra` and the first-party workloads in `apps`. Let Argo CD
   manage the workloads and their tracking annotations so discovery can identify
   their source owner.
2. Set `sonar.projectKey` to the GitLab repository path with every `/` replaced
   by `:`: `swirlit/my-app` becomes `swirlit:my-app`. Update the display name,
   backend/frontend source and test paths, Java binaries/libraries and coverage
   paths in `sonar-project.properties`. When retaining the optional bootstrap
   scripts, also adapt their `APP_NAME`, `GITLAB_PROJECT_PATH` and
   `SONAR_PROJECT_KEY` settings to the new application.
3. Keep [.sonar-auto.json](../.sonar-auto.json) on the default branch:

   ```json
   {
     "version": 1,
     "job": "02-quality",
     "scanOnlyVariable": "SONAR_SCAN_ONLY"
   }
   ```

   If the quality job is renamed, update `job` to match. Preserve the contract
   version and variable name.
4. Adapt [.gitlab-ci.yml](../.gitlab-ci.yml) and the build/test helpers to the new
   stack. Preserve automatic default-branch quality analysis, its artifact
   dependencies and the scan-only rules on every publishing, deployment and
   version-changing job. Preserve scanner failure reporting in
   [ci-quality.sh](../infra/scripts/ci-quality.sh). Keep the default branch
   protected so its jobs can receive the protected `SONAR_TOKEN` variable.
5. Commit these files to the new repository's default branch, validate the CI
   configuration with both values of `SONAR_SCAN_ONLY`, and check a scan-only
   pipeline. Confirm that both backend and frontend files appear in the Sonar
   project's Code view and that the scanner imports the expected coverage
   reports. A green pipeline alone does not prove that both source trees were
   included.

No central application-name list needs updating. A new workload without an Argo
source mapping, a supported repository or the required CI contract produces a
visible discovery error. The controller cannot infer arbitrary build commands
or add the missing configuration to the application repository. Vendor software
in `corp`, such as Odoo, is outside this source-discovery scope.

## Verification And Troubleshooting

From the Swirl Demo App repository, verify the submission helper's success, scanner
failure and missing-token behavior without contacting live services:

```bash
bash infra/scripts/test-quality.sh
```

For platform-local workloads, inspect discovery with the central kubeconfig:

```bash
kubectl -n infra get cronjob sonar-apps-discovery
kubectl -n infra logs -l app=sonar-apps-discovery --prefix --tail=100
```

Look for the new repository and a summary with zero errors. An `analysis current`
message means no refresh is due. An active application pipeline or the retry
interval can defer a requested scan. To request analysis yourself, follow
[Run A Manual Scan](#run-a-manual-scan).

Inspect `02-quality` for missing credentials, missing binaries, unresolved source
paths or missing coverage reports. Scanner submission failures fail that job
visibly. `SONAR_REPORT_STATUS=submitted` means the report was uploaded; check
Sonar's completed analysis timestamp and quality gate separately. The job does
not wait for or enforce the quality gate, and its findings do not block release.

The platform's [source-analysis guide](https://github.com/chefzaid/swirl-cloud/blob/main/docs/operations.md#source-analysis)
documents controller installation, credentials, permissions and tests. See also
the [deployment guide](deployment.md#automatic-sonar-coverage),
[ADR 0008](adr/0008-code-quality-and-verification.md) and the CI job contract in
[ADR 0009](adr/0009-explicit-delivery-jobs.md).
