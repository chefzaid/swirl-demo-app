# Service Levels And Alerts

This guide defines what "working" means for the user and order services, how
it is measured, and which alerts fire when it is at risk. The objectives are
starting points for a template; tune them once real traffic gives a baseline.

## Service-Level Indicators And Objectives

Every indicator counts API traffic only (`/api/**`); Actuator probes and
scrapes are excluded. Each objective covers a rolling 30-day window, per
service and environment.

| Indicator | Measured as | Objective | Error budget |
| --- | --- | --- | --- |
| Availability | share of API requests answered without a 5xx | 99.5% | 0.5% of requests (about 3.6 hours of full outage) |
| Latency | share of API requests answered within 300 ms | 95% | 5% of requests |
| Event backlog | consumer lag per topic | below 1000 records | not budgeted; alerts after 10 minutes |
| Saturation | pending database connections, heap use | no sustained waiting; heap under 90% | not budgeted; alerts after 5 or 15 minutes |

Both services publish fixed `http.server.requests` histogram buckets at 300 ms
and 1 s (`management.metrics.distribution.slo` in each `application.yml`), so
Prometheus can count requests inside the latency objective without storing
full histograms. Change the bucket and the rules together.

## Alert Rules

[`infra/observability/slo-rules.yaml`](../infra/observability/slo-rules.yaml)
holds the recording rules (`swirl:api_error_ratio:*`, `swirl:api_slow_ratio:*`)
and the alerts. Budget alerts use multi-window burn rates: an alert needs both
a long and a short window above the threshold, so it fires quickly on a real
incident and clears quickly once it ends.

| Alert | Fires when | Severity |
| --- | --- | --- |
| `SwirlApiErrorBudgetFastBurn` | 5xx ratio above 7.2% over both 1 h and 5 min (14.4x burn, 2% of the budget per hour) | critical |
| `SwirlApiErrorBudgetSlowBurn` | 5xx ratio above 3% over both 6 h and 30 min (6x burn) | warning |
| `SwirlApiLatencyBudgetFastBurn` | over 72% of requests slower than 300 ms over both 1 h and 5 min | critical |
| `SwirlApiLatencyBudgetSlowBurn` | over 30% of requests slower than 300 ms over both 6 h and 30 min | warning |
| `SwirlTargetDown` | a backend pod cannot be scraped for 5 minutes | critical |
| `SwirlDatabasePoolSaturated` | requests wait for a pooled database connection for 5 minutes | warning |
| `SwirlHeapNearLimit` | a pod uses over 90% of its maximum heap for 15 minutes | warning |
| `SwirlConsumerLagHigh` | a consumer is over 1000 records behind for 10 minutes | warning |
| `SwirlEventsDeadLettered` | any record reached a dead-letter topic in the last 15 minutes | warning |
| `SwirlOutboxBacklog` | over 100 order events wait in the outbox for 10 minutes | warning |
| `SwirlCacheFailing` | cache operations keep failing for 10 minutes | warning |

`critical` alerts need a person now; `warning` alerts need attention within
working hours. These are the severities the platform's own alerts use, so
Alertmanager routes both sets the same way.

### Availability Alerts

Open the dashboard's **API 5xx Ratio** panel and the request table to find the
failing route, then follow the matching [operations incident](operations.md#common-incidents).
A burst after a rollout is a [rollback](operations.md#rollback) candidate.

### Latency Alerts

Compare **API Requests Slower Than 300 ms** with **Database Connections** and
**Application CPU**. Pending connections point to the database; high CPU with
idle connections points to the pod size or the
[scaling notes](operations.md#scaling-notes).

### Event Backlog Alerts

Lag and dead letters follow the [Kafka events remain pending](operations.md#kafka-events-remain-pending)
runbook. A dead-lettered record has exhausted its retries; inspect it before
replaying, because replaying a record that fails deterministically only refills
the dead-letter topic.

### Saturation And Target Alerts

A target that cannot be scraped follows
[Prometheus target is down](operations.md#prometheus-target-is-down). Cache
errors follow [Redis is unavailable](operations.md#redis-is-unavailable); the
services keep answering from the database while the cache is down.

## Grafana Views

The overview dashboard in `infra/k8s/observability.yaml` shows each objective
next to its indicator: the 5xx and slow-request panels draw the objective as a
red threshold line, and the event, cache, and rate-limit panels show the
signals behind the backlog and saturation alerts. The panels query the raw
series, so they work before the rules are loaded.

## Loading And Testing The Rules

Every environment ships the rules as the `swirl-demo-app-prometheus-rules`
ConfigMap from [`infra/observability`](../infra/observability/kustomization.yaml),
labelled `prometheus_rule: "1"`. Every selector in the rule file names
`namespace="apps"`, and `render-deployment.py` rewrites it to the
environment's namespace, as it does for the dashboard. Each copy therefore
alerts only on its own environment, and copies loaded by one Prometheus never
record the same series twice. Like the environment's other manifests, a copy
holds the rule file of the revision rendered at that environment's last
deployment, not of its release tag: an image rollback keeps the current rules.

The platform's Prometheus does not load labelled ConfigMaps yet; the label and
the loader wait on the platform owner (see `TODO.md`, Needs Input). Until then
the ConfigMaps change nothing. Remote clusters have no Prometheus of their own
and the central one does not scrape them, so a remote environment's copy stays
inert until the platform connects
[remote telemetry](operations.md#metrics-dashboard), like its dashboard.

CI validates the syntax and runs the unit tests in
[`slo-rules.test.yaml`](../infra/observability/slo-rules.test.yaml), which
replay synthetic series and assert which alerts fire and with what text. Run
them locally with `promtool`:

```bash
promtool check rules infra/observability/slo-rules.yaml
promtool test rules infra/observability/slo-rules.test.yaml
```
