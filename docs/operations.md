# Operations Runbook

Application commands use the selected environment's namespace and kubeconfig.
For the shared cluster, use the platform context and `apps-int`, `apps-uat` or
`apps` (prod). Remote targets use their registered context and namespace. Central
Argo CD Applications remain in `infra`. The examples below select local `int`;
change `APP_NAMESPACE` and `KUBECONFIG` for another target. See
[deployment](deployment.md#ownership-and-topology).

This runbook covers the Swirl Demo App application layer, including its public DNS record, registry-credential projection, Argo CD application, and dashboard metadata. Shared database, messaging, identity, registry, CI/CD, ingress, logging, and monitoring services are owned by [`swirl-cloud`](https://github.com/chefzaid/swirl-cloud); use its runbooks when the incident is platform-wide. All Swirl Demo App-specific configuration remains in this repository.

## Runtime Surfaces

Public addresses use the settings saved by
[onboarding](deployment.md#add-or-reconfigure-this-repository):

| Surface | URL |
| --- | --- |
| application | `https://<APP_HOST>` |
| API documentation | `https://<APP_HOST>/api/docs` |
| Grafana dashboard, after telemetry integration | `https://grafana.<PUBLIC_DOMAIN>/d/swirl-demo-app-<env>-overview` |
| Kibana logs, after telemetry integration | `https://kibana.<PUBLIC_DOMAIN>` |
| GitLab source, CI and releases | `<GITLAB_PUBLIC_URL>/<GITLAB_PROJECT_PATH>` |
| SonarQube | `https://sonarqube.<PUBLIC_DOMAIN>`, project `<SONAR_PROJECT_KEY>` |
| Argo CD | `https://argocd.<PUBLIC_DOMAIN>/applications/swirl-demo-app-<env>` |

Cluster-only:

| Surface | Address |
| --- | --- |
| user service | `swirlapp-user.<namespace>.svc.cluster.local:8080` |
| order service | `swirlapp-order.<namespace>.svc.cluster.local:8081` |
| web service | `swirlapp-web.<namespace>.svc.cluster.local:80` |

Application services use target-cluster Kubernetes DNS. Shared PostgreSQL,
Redis and Kafka use Kubernetes Services locally or the registered private gateway
remotely; Keycloak uses its shared public URL. Inspect the selected environment's generated
`infra/environments/<env>/backend-runtime.properties` for resolved addresses.

## First Checks After A Rollout

```bash
kubectl --kubeconfig /secure/platform.yaml get application swirl-demo-app-int -n infra
export KUBECONFIG=/secure/platform.yaml
export APP_NAMESPACE=apps-int
kubectl get deploy,pods,svc,ingress -n "$APP_NAMESPACE"
kubectl get externalsecret swirl-demo-app-runtime-credentials swirl-demo-app-registry-auth -n "$APP_NAMESPACE"
```

Expected state:

- Argo CD: `Synced` and `Healthy`
- `swirlapp-user`, `swirlapp-order`, and `swirlapp-web`: desired replicas available
- pods: ready with low/no restart growth
- ExternalSecret: `Ready=True`
- ingress host: the configured `APP_HOST`

Rollout checks:

```bash
kubectl rollout status deployment/swirlapp-user -n "$APP_NAMESPACE"
kubectl rollout status deployment/swirlapp-order -n "$APP_NAMESPACE"
kubectl rollout status deployment/swirlapp-web -n "$APP_NAMESPACE"
```

## Health And Readiness

Backend probes:

- `/actuator/health/liveness`: process can continue running
- `/actuator/health/readiness`: process can receive traffic
- `/actuator/health`: aggregate health for internal smoke tests

Frontend probes:

- liveness `/`: NGINX can serve the SPA
- readiness `/runtime-config.json`: public identity configuration is available

The release smoke check also validates the runtime JSON fields. Browser startup
validates their values before authentication; see the
[configuration contract](deployment.md#add-or-reconfigure-this-repository).

Public ingress exposes only the two health summaries. Inspect other Actuator endpoints through the service or a temporary port-forward:

```bash
kubectl port-forward -n "$APP_NAMESPACE" svc/swirlapp-user 18080:8080
curl http://127.0.0.1:18080/actuator/health
```

Use a separate terminal and stop the port-forward after inspection.

Each service has a custom `database` health contributor that performs a repository count. Redis is also a health contributor in production.

The `messaging` contributor asks the Kafka cluster for its ID in the background every 10 seconds, so probes never wait on the broker. It is part of the readiness group, and what an outage does depends on `APP_MESSAGING_REQUIRED`:

| Kafka | `APP_MESSAGING_REQUIRED=false` (default) | `APP_MESSAGING_REQUIRED=true` |
| --- | --- | --- |
| reachable | `UP` | `UP` |
| unreachable | `DEGRADED` (HTTP 200): pods stay ready, orders are accepted and stay `PENDING` while their events wait in the outbox | `DOWN` (HTTP 503): readiness takes pods out of service |
| messaging disabled | `UNKNOWN`, ignored | `UNKNOWN`, ignored |

Liveness never includes Kafka, so a broker outage does not restart pods. The deployment smoke check expects `UP`, so a release deployed during a Kafka outage fails its smoke check even when messaging is optional.

## What Healthy Looks Like

Application:

- login discovery and redirect work
- anonymous `/api/users` and `/api/orders` return `401`
- authenticated list requests return `200`
- creating an order returns `201` with `PENDING`
- the order later becomes `APPROVED` for an existing user or `REJECTED` for a missing user
- response headers include `X-Request-Id`

Metrics, once target telemetry is connected:

- both backend scrape targets report `up=1`
- request rate matches expected traffic
- 5xx rate is near zero
- response time and Hikari pending connections remain stable
- pod restarts do not grow repeatedly

Events:

- both consumer groups are active when messaging is enabled
- lag returns toward zero
- DLT topics do not grow unexpectedly
- repeated results do not generate invalid-transition loops

## Logs And Request Correlation

UAT/production services write structured JSON to stdout. Target bootstrap does
not forward those logs or metrics to the shared platform, and central discovery
does not inspect remote workloads. Configure that integration separately before
using central dashboards to assess an environment. The
[platform observability guide](https://github.com/chefzaid/swirl-cloud/blob/main/docs/operations.md#namespace-and-discovery)
defines collection and discovery scope; direct target logs remain available below.

Useful fields:

- `@timestamp`
- `app`
- `level`
- `logger_name`
- `thread_name`
- `message`
- `requestId` from MDC
- `traceId` and `spanId` from MDC, shared by every log line of one order across both services
- `audit.*` fields on [audit trail](security.md#audit-trail) entries (`logger_name` `dev.swirlit.demo.audit`)
- Kubernetes namespace, pod, and container fields added by the collector

Direct logs:

```bash
kubectl logs -n "$APP_NAMESPACE" deployment/swirlapp-user --since=15m
kubectl logs -n "$APP_NAMESPACE" deployment/swirlapp-order --since=15m
kubectl logs -n "$APP_NAMESPACE" deployment/swirlapp-web --since=15m
```

Follow a specific pod when diagnosing restart or concurrency behavior:

```bash
kubectl get pods -n "$APP_NAMESPACE" -l app=swirlapp-order
kubectl logs -n "$APP_NAMESPACE" <order-pod-name> -f
kubectl logs -n "$APP_NAMESPACE" <order-pod-name> --previous
```

Start with the request ID returned to the caller and search `requestId` in the
logs, or in Kibana after forwarding is configured. Take the `traceId` from that
line and search it to follow the order through Kafka into the user service and
back: Kafka records carry the W3C trace context in their headers
([ADR 0010](adr/0010-opentelemetry-tracing.md)). When
`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` points at a collector,
the same trace is also available as spans in the tracing backend.

Do not paste tokens, credentials, complete personal records, or browser test artifacts into incident tickets.

## Metrics Dashboard

`infra/k8s/observability.yaml` supplies a dashboard ConfigMap with panels for:

- application targets up
- HTTP request rate
- HTTP 5xx rate
- average response time
- requests by app/status
- JVM heap
- application CPU
- Hikari active/pending connections
- pod working-set memory
- pod restarts

On a remote target, the ConfigMap alone does not provision central Grafana or
supply metric series. Its dashboard needs to be imported and backed by that
environment's telemetry. The panels below the restarts table follow the
[service-level objectives and alerts](slo.md).

### Application Metrics

Both services export these series on `/actuator/prometheus` in addition to the
Spring Boot defaults:

| Behavior | Prometheus series | Tags |
| --- | --- | --- |
| Publish latency | `spring_kafka_template_seconds` | `spring_kafka_template_name`, `error` |
| Consume latency | `spring_kafka_listener_seconds` | `spring_kafka_listener_id`, `error` |
| Consumer lag | `kafka_consumer_fetch_manager_records_lag_max` | `client_id`, `topic`, `partition` |
| Listener failures (each failed attempt) | `swirl_events_failed_deliveries_total` | `topic` |
| Duplicates the inbox skipped | `swirl_events_duplicates_total` | `consumer` |
| Records sent to a DLT | `swirl_events_dead_lettered_total` | `topic` (source topic) |
| Cache hits and misses | `cache_gets_total` | `cache`, `result` |
| Cache outages that fell through | `swirl_cache_errors_total` | `cache`, `operation` |
| Outbox backlog and throughput | `swirl_outbox_pending`, `swirl_outbox_published_total`, `swirl_outbox_failed_total` | none |
| Rate-limit rejections (429) | `swirl_rate_limit_rejections_total` | none |

Kafka series appear only when messaging is enabled, and cache series only in
UAT and production, where Redis caching is active. No series carries a record
key, user name, or client address.

## Common Incidents

### Pod cannot start because the database secret is missing

Symptoms:

- pod has `CreateContainerConfigError`
- `swirl-demo-app-runtime-credentials` Secret absent
- ExternalSecret not ready

Checks:

```bash
kubectl describe externalsecret swirl-demo-app-runtime-credentials -n "$APP_NAMESPACE"
kubectl get secret swirl-demo-app-runtime-credentials -n "$APP_NAMESPACE"
kubectl get clustersecretstore vault-backend-int
```

Confirm central Vault health, target External Secrets health and the selected
environment's Vault role/connectivity. Do not create an ad hoc plaintext secret in Git.

### Flyway migration or Hibernate validation fails

Symptoms:

- service crashes during startup
- logs mention Flyway checksum/version or schema validation

Checks:

```bash
kubectl logs -n "$APP_NAMESPACE" deployment/swirlapp-user --previous
kubectl logs -n "$APP_NAMESPACE" deployment/swirlapp-order --previous
```

Identify the owning service and its history table:

- users: `flyway_schema_history_users`
- orders: `flyway_schema_history_orders`

Do not modify an applied migration or switch Hibernate to `update`. Correct the migration with a new version, test it on a copy/disposable database, and deploy through GitOps.

### Redis is unavailable

Symptoms:

- `/actuator/health` reports Redis down; readiness stays up because the readiness group does not include Redis
- reads keep working from PostgreSQL with higher latency (each lookup waits for the Redis timeout of 2 seconds first)
- logs contain `Cache get failed for cache=...; continuing without the cache` warnings

Checks:

- confirm shared Redis health in `swirl-cloud`
- inspect application readiness details internally
- verify `REDIS_HOST`/port and NetworkPolicy behavior, including that `allow-swirl-demo-app-egress` still allows the Redis port

The services fail open (see [cache behavior](architecture.md#cache-behavior)), so a Redis outage degrades latency, not availability. After Redis returns, entries whose eviction failed during the outage can be stale until their 10-minute TTL expires; flush the environment's prefix (`swirl-demo-app:<env>:*`) if that window matters.

### Kafka events remain pending

Symptoms:

- orders stay `PENDING`
- consumer lag grows
- DLT records increase

Checks:

- both application pods are ready, and `/actuator/health` does not report `messaging` as `DEGRADED` or `DOWN`
- `KAFKA_ENABLED=true`
- bootstrap address is reachable
- user and order consumer groups are active
- request/result topics have expected partitions
- application logs show consume, retry, or publish errors

Interpretation:

- missing user should produce a normal `REJECTED` result
- database/network/runtime failures should retry, not reject
- malformed identity/status/state is non-retryable and moves toward DLT
- a record the consumer cannot deserialize (invalid JSON, an unknown `OrderStatus` value) goes to the DLT at once with its original bytes, and the `springDeserializerExceptionValue` header records why; the partition keeps moving
- a record with a newer `schemaVersion` than the consumer supports is dead-lettered; replay it after upgrading the consumer
- result publication is awaited before the request record is acknowledged

### Inspect and replay dead letters

Dead-letter topics are the quarantine: a record stays there, untouched, until someone replays it or the topic's retention (14 days, `APP_MESSAGING_DEAD_LETTER_RETENTION`) removes it. Kafka applies that retention only when it creates the topic; change an existing topic with the platform's Kafka tooling.

Each service exposes `/actuator/deadletters` on its management port. The ingress does not route it, so reach it from inside the cluster, for example with `kubectl -n apps port-forward deploy/swirlapp-user 8080`. With API security on it needs a bearer token, and the realm role named by `APP_SECURITY_ROLES_OPERATOR` when that is set.

1. List the newest records (20 by default, `?limit=` up to 200), newest first:

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/deadletters
   ```

   Each entry names the dead-letter topic, partition, offset, original topic, key (the order ID), `swirl-event-id`, and the failure class and message. Payloads are left out because results carry user names.
2. Fix the cause first: deploy the consumer that reads a newer schema, restore the missing reference data, or correct the configuration. A record that fails deterministically only comes back.
3. Replay one record to the topic it failed on:

   ```bash
   curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"topic":"order_topic.DLT","partition":0,"offset":7}' http://localhost:8080/actuator/deadletters
   ```

   The replay keeps the original bytes, key, and headers (including `swirl-event-id`), drops the dead-letter headers, and adds `swirl-replayed-from`. Consumers treat it like a redelivery, so replaying the same record twice is harmless: the order service's inbox skips a result it already applied. Each replay writes a `dead-letter.replayed` audit entry.

A record that fails again lands back in the dead-letter topic with `replayedFrom` set, which shows it has already been tried.

### Order events are waiting in the outbox

Every order change records its event in `order_outbox` in the same transaction, so an order cannot lose its event; it can only wait for the relay. `SwirlOutboxBacklog` fires when `swirl_outbox_pending` stays above 100.

Checks:

- `messaging` health and the order logs: `Could not publish outbox event id=... attempt=...` names the failing row and the exception type
- the oldest pending rows: `SELECT id, event_key, attempts, created_at FROM order_outbox WHERE published_at IS NULL ORDER BY id LIMIT 20`
- a row whose `attempts` keeps rising while others publish has a payload or topic Kafka rejects; fix the cause rather than deleting the row, because the order stays `PENDING` until its event is published

The relay retries every second once Kafka is back; no manual replay is needed.

### Authentication redirects or JWT validation fail

Symptoms:

- discovery request fails
- redirect loop
- API returns `401` after apparent login
- logs report issuer mismatch or JWK retrieval failure

Checks:

- `/runtime-config.json` loads as JSON, not the SPA or a cached old configuration
- public discovery: `<JWT_ISSUER_URI>/.well-known/openid-configuration`
- token `iss` and the browser's configured realm match `JWT_ISSUER_URI` in `backend-runtime.properties`
- the shared public Keycloak JWK URL resolves and is reachable from the target pod
- canonical Keycloak ingress and the shared internal service are healthy
- system time is synchronized

Do not log or paste the full access token. Decode only non-sensitive header/claim metadata in controlled tooling when necessary.

### Public site works but API path returns the SPA

Check ingress path ordering/routing and the rendered manifest:

```bash
kubectl describe ingress swirl-demo-app-ingress -n "$APP_NAMESPACE"
kubectl kustomize infra/k8s | less
```

`/api/users`, `/api/orders`, and documentation paths must route before the `/` catch-all. Keycloak is reached on its own canonical public hostname and is not proxied by the Swirl Demo App ingress.

### Prometheus target is down

First confirm this target has a configured metrics collector and forwarding path;
the minimal application-cluster foundation does not provide them.

Checks:

- pod readiness and annotations
- service endpoints
- `allow-swirl-demo-app-ingress` and `allow-swirl-demo-app-egress` NetworkPolicies (prod in `apps`); platform policies in `apps-int` and `apps-uat`
- Prometheus namespace/pod labels still match the policy
- `/actuator/prometheus` responds through the cluster service

Only the exact `/health/user` and `/health/order` routes expose a health summary. Metrics and other Actuator paths remain private.

### Kibana dashboard is absent or stale

For a remote environment, check its separately configured log forwarding and
dashboard integration. The
[platform discovery diagnostics](https://github.com/chefzaid/swirl-cloud/blob/main/docs/operations.md#ownership-and-troubleshooting)
apply to workloads on the platform cluster; Argo CD tracking alone does not make
remote workloads discoverable.

### Argo CD reverts a manual change

This is expected: automated self-heal is enabled. Make the change under `infra/k8s/`, commit it, and let Argo CD reconcile.

### GitLab CI published images but deployment did not advance

Inspect the explicit delivery jobs:

- `01-snapshot`: publishes an integration snapshot from the selected branch
- `01-release`: publishes stable release artifacts/images from the default branch
- `02-deploy`: applies the Application, waits for the exact GitOps revision, and runs smoke checks
- optional `01-e2e`: retains browser acceptance output but cannot suppress release or deploy

Compare:

```bash
git show HEAD:infra/argocd/int.yaml
kubectl --kubeconfig /secure/platform.yaml get application swirl-demo-app-int -n infra -o yaml
```

Compare the selected Application pointer and its pinned runtime commit; snapshot
pointers live on `gitops/int/<pipeline-id>`, while release pointers live on the
default branch. For an interrupted
onboarding publication, follow [same-pipeline recovery](deployment.md#add-or-reconfigure-this-repository);
unrelated Git changes still require a new run.

### GitHub and GitLab differ

GitHub pushes start the repository reconciler directly. GitLab branch and tag pushes invoke it through the managed repository-dispatch webhook, including GitLab CI commits marked `[skip ci]`. The workflow normally fast-forwards or merges the mirrors without force pushing, and its monthly schedule renews the GitLab credential before expiry. Inspect the **Sync GitHub and GitLab** workflow and the GitLab webhook delivery log if synchronization fails; a true content conflict or conflicting immutable tag intentionally stops instead of discarding repository history.

## Rate-Limit Incidents

The backend limiter is fixed-window and in-process per replica. It identifies an authenticated principal where available, otherwise the remote address visible to the app.

When a caller receives `429`:

- inspect `RateLimit-*` and `Retry-After`
- correlate with `X-Request-Id`
- a `429` with `X-Retry-In` but no `RateLimit-*` headers comes from the environment's
  [edge throttling](security.md#edge-throttling); check its `edgeRateLimit` setting
  and the Traefik access logs, since the application never saw the request
- confirm traffic source and whether the caller is retrying too aggressively
- do not raise limits until an abuse or capacity assessment is made

With multiple replicas, each pod has independent counters. Wider enforcement belongs at Traefik, Cloudflare/API gateway or a shared store; the current limiter is defense in depth, not a complete DDoS control.

## Scaling Notes

The workloads currently have one replica. Before scaling:

- confirm shared Redis cache behavior
- confirm Kafka consumer groups and partition count meet concurrency goals
- recognize the per-instance rate limiter becomes approximate
- confirm database connection capacity against Hikari pools
- add PodDisruptionBudgets/topology spread as needed
- load test the ingress and service resources
- expect `swirl_events_duplicates_total` to rise briefly after rebalances and relay restarts; the inbox absorbs those duplicates
- the outbox relay runs on every replica and shares rows through `SKIP LOCKED`; the retention job runs on one replica at a time through `order_scheduler_lock`, so a new cluster-wide job needs `@SchedulerLock` too

Kafka topics have three partitions, which caps useful parallel consumption per consumer group at three active consumers unless partitioning changes.

## Rollback

Preferred rollback is Git-based:

1. identify the last known good image-tag commit
2. revert or create a reviewed Kustomize tag change in GitLab
3. let Argo CD reconcile
4. wait for exact revision health
5. rerun smoke and browser acceptance

Database migrations require separate care. Application rollback is safe only when the old application remains compatible with the migrated schema. Prefer backward-compatible expand/migrate/contract sequences.

## Backup And Recovery Ownership

Application data resides in shared PostgreSQL. Kafka and Redis have persistent platform storage in relevant environments. Backup schedules, retention, encryption, and restore infrastructure belong to `swirl-cloud`.

Swirl Demo App still needs application-level recovery validation:

- restore a database copy and run both services with Hibernate validation
- verify both Flyway histories
- validate user/order counts and representative reads
- understand Kafka offset/topic recovery separately from database recovery
- define RPO/RTO and rehearse them

These drills are not automated yet.

## Secret Rotation

Database credential rotation must coordinate:

1. PostgreSQL credential update
2. Vault value update
3. ExternalSecret reconciliation
4. application restart/reconnection
5. health verification

Registry/repository credentials live at Vault `apps/swirl-demo-app/registry`. Coordinate
their replacement with ExternalSecret refreshes before revoking the prior token.
Publication uses GitLab's job token; there is no separate `apps/swirl-demo-app/ci` Secret.

Keycloak signing-key rotation should allow token/JWK overlap and verify both backend resource servers. Never rotate by editing the exported disposable realm secret values for a live realm.

## Related Guides

- [Deployment](./deployment.md)
- [Security](./security.md)
- [Testing](./testing.md)
- [Data Model](./data-model.md)
- [Architecture and ADRs](./architecture.md)
