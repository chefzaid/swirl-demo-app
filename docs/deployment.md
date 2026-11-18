# Deployment Guide

One shared `swirl-cloud` platform provides GitLab, its runner and registry, Argo CD,
Vault, PostgreSQL, Redis, Kafka and Keycloak. Swirl Demo App's `int`, `uat` and `prod`
environments can run in separate namespaces on that same cluster or on optional
remote clusters. One GitLab project publishes images; central Argo CD deploys
the selected environment.

## Infrastructure Layout

| Directory or file | Responsibility |
| --- | --- |
| `infra/k8s/` | Shared application manifests and public configuration templates. |
| `infra/observability/` | Prometheus SLO recording and alert rules with their `promtool` tests, shipped to each environment as a labelled ConfigMap scoped to its namespace. |
| `infra/overlays/ha/` | Optional application replica and disruption-budget profile. |
| `infra/overlays/canary/` | Optional Argo Rollouts canary example with an error-rate analysis. |
| `infra/environments/<env>/` | Generated environment settings, runtime ConfigMaps, ingress and image tags with their digests. |
| `infra/argocd/application.yaml` | Onboarding template; do not apply it to deploy an environment. |
| `infra/argocd/<env>.yaml` | Selected environment's central Argo CD Application and pinned source revision. |
| `infra/deployment-environments.json` | Public platform inventory copied during onboarding. |
| `infra/releases/<version>.json` | Published release identity, image tag and immutable component image digests. |
| `infra/snapshots/<pipeline-id>.json` | Snapshot provenance, image tag and digests on its dedicated integration delivery branch. |
| `infra/keycloak/` | Browser client template and disposable local realm. |
| `infra/compose/` | Local authenticated development stack. |
| `infra/scripts/` | Rendering, release and maintenance helpers. |

Application runtime resources stay in this repository. Cluster registration,
shared-service connectivity and platform credentials belong to `swirl-cloud`.

## Ownership And Topology

Each environment has an Application named `swirl-demo-app-<env>` in the central `infra`
namespace. It uses AppProject `applications-<env>` and the registered cluster and
namespace. Shared-cluster targets use destination `in-cluster` and namespaces
`apps-int`, `apps-uat` and, for prod, the shared `apps` namespace by default. An environment may instead use
its own registered remote cluster; local and remote targets can coexist. Remote
targets need distinct cluster registrations. All use the same GitLab, registry
and Argo CD installation.

Subdomains route to separate Deployments; they do not create isolation by
themselves. `apps-int` and `apps-uat` have their own Secrets and platform-managed
access controls, network policies and resource limits. Prod shares `apps` with the
platform's single-environment applications: it keeps its own Vault store and Argo
project, and ships the `allow-swirl-demo-app-ingress` and `allow-swirl-demo-app-egress`
NetworkPolicies itself.
Shared-cluster environments also share capacity, outages and cluster maintenance.

The central `infra/deployment-environments` ConfigMap supplies the authoritative
cluster and shared-service endpoints. The registrar publishes this checkpoint
only after verifying Argo CD registration, target identity and prerequisites;
CI reads that public record without access to cluster credentials. Rerun platform
registration after credential or target maintenance. For a newly registered
environment, rerun application onboarding to prepare its data, identity and DNS;
then select it without changing application source. Reassigning an existing environment
to another cluster or namespace requires an explicit migration.

Each environment has separate database credentials/database, Redis cache prefix
and credentials, Kafka topic prefix and credentials, and browser client
`swirl-demo-app-<env>-web`. Runtime credentials come from Vault paths
`apps/swirl-demo-app/<env>/{database,redis,kafka}`. The registry credential at
`apps/swirl-demo-app/registry` is shared read access to this project's images. The
registered `secretStoreName` selects each environment's scoped Vault store.
Shared-cluster workloads use Kubernetes Service endpoints; remote workloads use
the platform's private gateway. Registration supplies these endpoint choices,
connectivity and secret access before deployment.

## Kubernetes Desired State

The shared base contains the user, order and web Deployments and Services,
ingress, observability resources, ExternalSecrets and generated ConfigMaps.
Remote deployments and the shared prod namespace also include the application's
ingress and egress NetworkPolicies; dedicated local namespaces use platform-owned
network policies that application CI cannot relax. The egress policy mirrors the
platform's dedicated-namespace allowance, limited to the application pods: the
other application pods, cluster DNS, PostgreSQL (5432), Redis (6379), Kafka (9094)
and HTTP(S) services in `infra`, the ingress controller (Keycloak's public hostname
hairpins to it) and public HTTPS outside private ranges. Pods cannot open any other
connection, so add a rule to `infra/k8s/network-policy.yaml` before configuring a
new outbound dependency such as an OTLP collector. To roll back, revert that
policy; removing it restores unrestricted egress at the next sync.
The workloads use non-root containers, read-only root filesystems, explicit
resources, health probes and restricted Linux capabilities.
Argo CD creates application and health ingress routes after the workloads become
healthy. Moving an existing hostname between namespaces still needs an explicit
route cutover so two environments never compete for the same hostname.

Environment overlays replace public runtime configuration and select images by
digest. They contain no copied Java, Angular or Kubernetes base source. ConfigMap
content hashes roll the affected Deployments when settings change. The generated
Application pins an exact Git revision, so changing the shared base or deploying
another environment does not move an existing environment's desired state.

Render without changing a cluster:

```sh
kubectl kustomize infra/k8s >/dev/null
kubectl kustomize infra/overlays/ha >/dev/null
kubectl kustomize infra/overlays/canary >/dev/null
# After onboarding/deployment generated the selected environment:
kubectl kustomize infra/environments/int >/dev/null
```

## Images

The release publishes three images in the central project registry:

```text
<registry>/<gitlab-project-path>/swirlapp-user:<semantic-version>
<registry>/<gitlab-project-path>/swirlapp-order:<semantic-version>
<registry>/<gitlab-project-path>/swirlapp-web:<semantic-version>
```

Each image is built once during publication. The release manifest records its
`sha256` digest, image tag, publishing pipeline and source revision; deployment
uses those digests. Environment promotion reuses the published images and does
not rebuild Java, Angular or containers. Public settings load at runtime, so the
same web image works with every environment hostname and browser client.

Every environment overlay pins each image as `<image>:<tag>@sha256:<digest>`.
The digest decides what runs; the tag names the version, so each pod's first
container image shows it, as with the platform's other applications. The
platform portal reads the version of the `app=swirlapp-web` pods from that tag. `uat` and `prod`
carry the release version, for example
`registry.swirlit.dev/swirl/swirl-demo-app/swirlapp-web:1.15.3@sha256:...`, and
the user and order images carry the same tag.

Integration snapshots push two tags: `snapshot-<source-commit>-<pipeline-id>`,
their immutable identity, and `<version>-<short-commit>`, where the version is
the one the commit builds (`infra/scripts/current-version.sh`, also the build
version its JARs report) and the short commit is the first eight characters of
its SHA, for example `1.15.4-0b92a2e1`. `int` deploys the second tag with the
digest. A new pipeline for the same commit moves that tag, but each deployment
keeps its own digest, and the identity tag keeps every published digest
addressable. Snapshots are not GitLab releases and cannot deploy to `uat` or
`prod`; a source version ending in `-SNAPSHOT` is supported for integration and
stays in the tag, for example `2.0.3-SNAPSHOT-0b92a2e1`.

## Add or reconfigure this repository

Run the platform's `./add-repos.sh` and select Swirl Demo App. Its
[onboarding declaration](../infra/onboarding.json) supplies the app contract;
the platform imports this repository once and prepares its registered environment
settings. The initial deployment defaults to `int`; set
`ONBOARDING_DEPLOYMENT_ENVIRONMENT` explicitly to select another registered target.

Public choices are stored in `infra/environments/<env>/settings.json`:

| Setting | Purpose |
| --- | --- |
| `appSubdomain` | The shared onboarding app label, default `demo`; `@` selects each environment's domain apex. |
| `trustedProxyCIDRs` | Trusted ingress addresses for API rate limiting; first setup uses the target pod CIDR. |
| `highAvailability` | Enables the [HA profile](#future-multi-node-ha-profile) on a prepared application cluster. |
| `edgeRateLimit` | Optional per-visitor throttling at Traefik on each Ingress path, such as `{"average": 50, "burst": 100}`: `average` requests per second with bursts of up to `burst` requests, at most twice `average`. Off when absent, refused for remote clusters, and only safe when public traffic arrives through Cloudflare; see [Edge Throttling](security.md#edge-throttling). |
| `apiDocs` | Publishes Swagger UI and the OpenAPI documents without a token, default `true`. `false` makes them require authentication like any API and hides the frontend's API link. |
| `databaseName` | Defaults to `swirl_demo_app_<env>`; production alone can explicitly retain legacy `swirl_demo_app_db` after the ownership/credential migration below. |

The shared platform supplies registry/project paths, service endpoints and the
Keycloak realm. Environment registration supplies its domain and TLS policy,
namespace, hostname style and destination. The `suffix` style produces
`demo-int.example.com`, `demo-uat.example.com` and `demo.example.com`, covered
by the parent zone wildcard. The optional `nested` style retains
`demo.int.example.com` and `demo.uat.example.com` when deeper TLS coverage is
available.
Onboarding applies one app label across all registered environments; proxy CIDRs
and HA selection remain specific to each environment.

Angular validates `/runtime-config.json` before authentication starts. Backend
settings come from `backend-runtime.properties`; credentials come from the
`swirl-demo-app-runtime-credentials` ExternalSecret. Central provisioning creates each
environment's database and restricted user before delivery; application pods do
not receive the PostgreSQL administrator credential or run a database-creation
hook. Flyway owns schema migrations inside that environment's database.

For an existing production `swirl_demo_app_db`, first migrate its owner and schema objects
to the restricted `swirl_demo_app_prod` role and preserve its credentials at
`apps/swirl-demo-app/prod/database` in Vault. Set `databaseName` to `swirl_demo_app_db` in production
settings before onboarding. Central provisioning verifies this explicit adoption;
it never transfers ownership or selects a different database silently. Other
environments cannot select that database.

Onboarding commits public configuration and starts an API pipeline for that exact
source commit. Its release job is automatic only for an explicit onboarding
request or a full-mode web pipeline. Sonar-only pipelines cannot deploy. The
final deployment pointer carries pipeline/source trailers so an interrupted
onboarding run can verify and resume its own work.

## Delivery Flow

| Environment | Trigger | Result |
| --- | --- | --- |
| `int` | Every commit, on any branch. | Build, publish and deploy an immutable snapshot automatically. |
| `uat` | **Publish** (`01-publish`) on a default-branch commit pipeline. | Publish a stable release, then `02-deploy-uat` deploys it automatically. |
| `prod` | A default-branch pipeline with `DEPLOYMENT_ENVIRONMENT=prod`, started from **Run pipeline** in GitLab or from a command line with `mask promote prod` ([below](#promote-prod-from-a-command-line)). | Promote the release running on `uat`; nothing is built. |

`prod` never publishes and never deploys a release directly. It reads the latest
`uat` deployment record on the default branch and requires the live `uat`
Application to be `Synced`/`Healthy` on that exact runtime revision. Leave
`RELEASE_VERSION` empty to promote it; any other version is refused. Only a
pipeline started from the GitLab UI or the API can deploy `prod`; pushes,
schedules, triggers and onboarding never do. A `prod` pipeline runs only
`00-delivery-policy` and `02-deploy`: build, test, package, coverage, quality,
scan, publication and version jobs are skipped, since `02-deploy` reuses the
published images and needs none of their artifacts.

A manual pipeline with `PIPELINE_MODE=full` can also redeploy a branch snapshot to
`int`, redeploy an existing release to `int` or `uat` with `RELEASE_VERSION`, or
publish and deploy a release to `uat` from the default branch.

All branch snapshots share the `int` environment and hostname; a newer deployment
replaces the previous integration deployment. This does not create a hostname per
branch. Feature branches need the current CI helpers, so merge or rebase older
branches once when adopting this delivery flow. A branch with an open merge
request can still start an explicit web deployment pipeline.

Release promotion, and every `prod` pipeline, skips build, test, scan, publication and version-change jobs.
Deployment verifies the annotated release tag, GitLab release and image digests.
Snapshots and unfinished releases are rejected for `uat` and `prod`.

Commit pipelines build, test, package and deploy `int` automatically and leave
publication manual; `int` delivery does not wait for the tests or `03-package`. Optional E2E, quality and security reports do not gate release.
Compilation/package artifacts are produced by `01-build`, and `03-package` checks
that the runtime images build. `01-release` (manual and onboarding pipelines) or
`01-publish` (commit pipelines) then builds, tags and pushes the release images
once; `01-snapshot` does the same for integration images. `02-deploy` and
`02-deploy-uat` depend on the appropriate successful publication unless promoting
an existing release.

Publication and major-version changes share a project-wide lock. Deployments use
one lock per environment and normal Git pushes; a concurrent change to the shared
branch makes a stale deployment stop instead of overwriting it. Start a new
promotion pipeline from the latest default branch after reviewing that change.

Release deployment commits the selected environment overlay, then a separate Application
pointer to that exact runtime commit. Both commits are pushed before CI applies
only the selected central Application. CI verifies its destination, source path,
pinned revision, three tagged image digests, `Synced`/`Healthy` status and Deployment
health. Public checks verify the SPA, exact runtime identity configuration and
both API health routes. These checks use the selected public hostname for both
shared-cluster and remote environments.

Snapshot deployment writes its runtime configuration and Application pointer to
`gitops/int/<pipeline-id>`, based on the selected source commit. It reads current
public target settings from the default branch and leaves the source branch,
default branch, version baseline and other environment pointers unchanged. The
Application pins the generated runtime commit. Retrying a published snapshot
reuses its recorded digests; a stale pipeline cannot replace a newer integration
deployment.

Onboarding gives Swirl Demo App dedicated project runners. Branch jobs can update only
the integration Application; a protected runner handles release delivery.
Kubernetes admission checks keep each Application bound to its assigned project,
repository and target. Shared instance runners are disabled for this project;
other applications keep their existing runner configuration.

### Promote prod from a command line

Starting a pipeline through the API needs the same GitLab permission as
**Run pipeline**, so the same promotion runs from a terminal:

```sh
export GITLAB_TOKEN=...   # api scope; a role allowed to run pipelines with variables
mask promote prod         # or: infra/scripts/promote-prod.sh
```

[`promote-prod.sh`](../infra/scripts/promote-prod.sh) creates the
default-branch pipeline with `DEPLOYMENT_ENVIRONMENT=prod`
(`POST /projects/:id/pipeline`), prints each job as its status changes, then the
result of every job and the version `prod` now runs, read from `02-deploy`. It
exits non-zero with the failing job's URL when the promotion fails. `--no-wait`
returns once the pipeline exists, `--token-file PATH` reads the token from a
file instead (the token is never printed or put on a command line), and
`--gitlab-url`, `--project` and `--ref` override the defaults, which come from
`infra/onboarding-values.json` and the project's default branch. Requires
Bash, curl and jq.

### Pipeline jobs

`.gitlab-ci.yml` uses the job names, modes and conventions of the platform's
other applications, plus four jobs for the three environments
(`00-delivery-policy`, `01-snapshot`, `01-publish` and `02-deploy-uat`).
A promotion pipeline is one started with `RELEASE_VERSION` or with `DEPLOYMENT_ENVIRONMENT=prod`.

| Job | Stage | Runs | What it does |
| --- | --- | --- | --- |
| `00-delivery-policy` | .pre | every pipeline | required: refuses an invalid environment, release or major-version request before anything builds |
| `01-build` | build | every pipeline except promotion | required, as two parallel jobs: `01-build: [server]` runs the delivery-script, manifest, alert-rule and documentation checks and builds both JARs; `01-build: [web]` builds the production web archive |
| `02-test` | build | every pipeline except promotion, from the start | allowed to fail, as two parallel jobs: `02-test: [server]` runs the Maven tests with JaCoCo, `02-test: [web]` the Vitest suite |
| `03-package` | build | every pipeline except promotion and Sonar scan-only, after `01-build` | required: builds the three runtime images with Kaniko, without pushing, alongside the tests |
| `01-e2e` | verify | manual; automatic in the [nightly E2E schedule](testing.md#scheduled-e2e-runs) | allowed to fail, except in the schedule: the Playwright smoke, accessibility and visual suites |
| `02-quality` | verify | automatic on the default branch and in full mode, manual otherwise | allowed to fail: Sonar analysis from the test artifacts |
| `03-security` | verify | automatic in full mode, manual otherwise | allowed to fail: Trivy scan and CycloneDX SBOM, failing on high or critical findings |
| `04-coverage` | verify | every pipeline except promotion, after both `02-test` jobs | allowed to fail: the combined line-coverage policy over the JaCoCo and Vitest reports, shown as the pipeline's coverage |
| `01-snapshot` | release | every branch commit pipeline for `int` | publishes the integration snapshot (above) |
| `01-release` | release | default branch for `uat`: manual with confirmation, automatic in full mode and API onboarding | publishes a release (above) |
| `01-publish` | release | default-branch commit pipelines, manual with confirmation | `01-release` for commit pipelines |
| `02-deploy` | release | after `01-snapshot` or `01-release`, or for a promotion | deploys the selected environment (above) |
| `02-deploy-uat` | release | after `01-publish` | deploys the release it published to `uat` |
| `set-major-version` | version | default branch, manual, in a pipeline started with `NEW_MAJOR_VERSION` | prepares `<NEW_MAJOR_VERSION>.0.0` and synchronizes the Maven and npm manifests |

`PIPELINE_MODE=full` runs quality and security automatically; E2E stays manual.
A pipeline with `PLAYWRIGHT_SCHEDULE=true` runs build, tests, coverage, package
and the E2E suites, nothing after. `SONAR_SCAN_ONLY=true` runs only build, test,
coverage and quality. Publication and deployment jobs are not interruptible and
hold a resource group.

Each job starts as soon as its inputs exist (`needs`): both test jobs and the
Trivy scan right after `00-delivery-policy`, `03-package` and `01-snapshot` once
both build jobs are done, `04-coverage` and `02-quality` once both test jobs are.
Integration delivery (`01-build`, `01-snapshot`, `02-deploy`) therefore waits for
neither the tests nor `03-package`; `01-snapshot` builds and pushes the images
itself. The test jobs run with 8 CPUs and 12 GiB (`KUBERNETES_CPU_LIMIT`,
`KUBERNETES_MEMORY_LIMIT`). Maven builds with one thread per CPU (`-T 1C`), so
user and order build and test side by side, and Surefire runs two test JVMs per
module. `ci-verify.sh` reads the job's CPU quota from its cgroup and caps
Angular's build and Vitest at one worker less (`NG_BUILD_MAX_WORKERS`,
`VITEST_MAX_WORKERS`), since Node counts the host's threads.

Every Kaniko build (`03-package`, `01-snapshot`, `01-release`, `01-publish`)
pulls its Docker Hub base images through the GitLab group's Dependency Proxy
(`--registry-mirror "$CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX"`, with the job's
`CI_DEPENDENCY_PROXY_*` credentials in Kaniko's Docker config), so Docker Hub's
anonymous pull limit cannot fail a build; if the proxy fails, Kaniko falls back
to Docker Hub.

## Version Lifecycle

[`VERSION`](../VERSION) establishes the `major.minor.patch` baseline. Builds add
one patch step for each first-parent commit since that baseline. Publication tags
the computed version, records the immutable image manifest and prepares the next
minor baseline with patch zero. Releasing `1.0.3`, for example, prepares `1.1.0`.
Release images are tagged with that version, and integration snapshot images with
the computed version and their commit (see [Images](#images)). Promotion and
integration snapshots do not change the baseline or create another release tag. Initial API onboarding retains its stable release-and-deploy flow.

For a deliberate major change, set `NEW_MAJOR_VERSION` when starting a normal
pipeline and run `set-major-version` on the default branch. It updates Maven and
npm versions together and refuses a stale branch.

## Verification And Rollback

Inspect the selected Application through the central Kubernetes context:

```sh
kubectl --kubeconfig /secure/platform.yaml -n infra get application swirl-demo-app-int -o json | jq '{source:.spec.source,destination:.spec.destination,status:.status}'
curl --fail https://demo-int.example.com/runtime-config.json
curl --fail https://demo-int.example.com/health/user
curl --fail https://demo-int.example.com/health/order
```

The health routes expose only application health; actuator metrics are not
published by those routes. Inspect Pods and ExternalSecrets in the selected
namespace: use the platform kubeconfig for local targets and the registered
target's kubeconfig for remote targets.

If GitLab release registration fails after publication, retry that pipeline's
release job. It verifies its tag, source/pipeline receipts and image manifest,
then completes registration without rebuilding or republishing. A failed deploy
job can retry its own committed pointer without adding another deployment commit.
An unrelated branch change requires a new pipeline. Credentials and already
published configuration are retained; there is no automatic data rollback.

To roll back images, run a promotion pipeline with the previous release version
and selected environment. Current public settings remain in effect. Restoring
older configuration also requires reviewing that environment's prior settings
and Application pin. Database migrations must remain compatible with the selected
application version; image rollback does not undo database changes.

## Local Checks

```sh
python3 infra/scripts/test-onboarding.py
python3 infra/scripts/test-release.py
python3 infra/scripts/test-deployment.py
python3 infra/scripts/test-promotion.py
python3 infra/scripts/test-promote-prod.py
```

These checks need Python with PyYAML, Git, Bash, jq and kubectl. They use disposable
Git repositories and fake APIs to verify publication recovery, routing, target
ownership, independent pins, digest promotion, wrong-target refusal and the
command-line prod promotion. Set
`SWIRL_CLOUD_SOURCE=/path/to/swirl-cloud` for the actual onboarding renderer checks.
No live deployment is needed.

## Production Identity

The platform owns the realm and its users. Each environment's public browser
client uses Authorization Code with PKCE `S256`, restricts redirects and origins
to its own hostname, and disables password and implicit grants. Its identifier
also supplies the API's expected JWT audience. Onboarding manages these clients
centrally; never import the disposable Compose realm into the shared identity
service. Removing workloads does not remove credentials, data or clients;
retire only the intended environment's resources after verifying ownership.

## Public DNS

Follow [application DNS ownership](dns.md). Onboarding targets each environment's
registered ingress address. Shared-cluster environments use the same address
with different hostnames. DNS changes do not select the Argo CD target.

## Local And Manual Paths

The authenticated local stack remains available through Compose:

```sh
docker compose -f infra/compose/compose.yaml up --build -d
```

Use the GitLab environment selector for deployed environments. The onboarding
Application template is rendered into a selected, pinned Application before use.

## Automatic Sonar coverage

One Sonar project covers both APIs, their shared Java module and Angular.
`sonar-project.properties` owns source/coverage inputs; `.sonar-auto.json` declares
scan-only behavior. The [code-quality guide](code-quality.md) covers credentials,
schedules, troubleshooting and adaptation to another application.

## Future multi-node HA profile

`infra/overlays/ha` runs at least two copies of each API and frontend, spreads Pods across
hostnames and adds disruption budgets. CPU-based HorizontalPodAutoscalers scale each
workload between 2 and 4 replicas at 70 percent of its CPU request, waiting five
minutes before scaling down; they need the metrics API (metrics-server) on the
cluster. The overlay removes `spec.replicas` from the Deployments, so the
autoscalers own the replica count and Argo CD self-healing does not undo a scale.
Rolling updates add one Pod before removing one (`maxSurge: 1`,
`maxUnavailable: 0`) and count a Pod as available after 5 ready seconds. Enable `highAvailability` in the selected
environment's settings only after at least two eligible hosts and their ingress
routing are ready. Other environments keep their own profile. The overlay
inherits the selected digest and generated runtime configuration.

Shared services must also remain available: PostgreSQL needs a replicated primary,
Redis needs failover, Kafka needs replicated brokers/topics, and Keycloak and
network routing must survive the tested failure. Application replicas do not
provide those guarantees. Flyway migrations must support rolling deployments;
rate limits remain per API process. Every order replica runs the outbox relay;
`SKIP LOCKED` gives each one a different batch.

## Optional canary example

`infra/overlays/canary` replaces the plain rolling update with an Argo Rollouts
canary. Each Rollout points at its existing Deployment (`workloadRef`), runs two
Pods and, on a new image or configuration, moves one Pod to the new version, then
waits five minutes before promoting. Pods keep their `app` label, so the Services,
NetworkPolicies and dashboards apply unchanged. For the user and order APIs the
`swirl-demo-app-error-rate` AnalysisTemplate queries the platform Prometheus every
minute; more than 5 percent 5xx responses in two of five samples aborts the
Rollout and returns all Pods to the stable version. The frontend has no request
metrics, so its canary only pauses.

The overlay needs the Argo Rollouts controller and CRDs on the cluster, which the
platform does not install today; no environment selects it. It removes
`spec.replicas` from the Deployments so Argo CD does not scale them back up while
Argo Rollouts scales them down (`scaleDown: progressively`). Both versions run
against the same database during the pause, so schema changes must be expand and
contract. Check a canary with `kubectl argo rollouts get rollout swirlapp-user -n <namespace>`;
abort it with `kubectl argo rollouts abort`, and roll back the overlay by deploying
the base again, which restores the Deployments' replica count.

Validate offline (the build job renders it; schema checks need the CRD schemas):

```sh
kubectl kustomize infra/overlays/canary | kubeconform -strict -summary \
  -schema-location default \
  -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
```

Before relying on HA, verify JWT requests across replicas, cache invalidation,
Kafka rebalance and duplicate handling, then drain one eligible application node
while exercising both APIs. A disruption budget governs voluntary eviction; it
cannot prevent hardware failures.
