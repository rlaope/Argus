# Argus APM Facade Contract

Status: planning contract for the Argus APM Core roadmap.

Code foundation: the `argus-apm` module owns the public facade interface,
entity models, and DTOs that implement this contract boundary.

This contract defines the public control-plane boundary for future APM work. It
must exist before runtime source changes that expose service, endpoint, trace,
log, profile, or incident workflows.

## Boundary

Argus APM Core has two separate surfaces:

| Surface | Visibility | Owns | Must not own |
|---|---|---|---|
| `argus-apm` facade | Public product API | Authenticated APM reads, tenant/project/environment scoping, service and endpoint views, incident workflow, backend links | Raw pod scrape registration, unauthenticated fleet mutation |
| `argus-aggregator` | Internal cluster cache | Pod target registration, scrape cache, fleet rollups, alert cache, profile cache | Public APM API, tenant authorization, external ingress |

The current `argus-aggregator` v1alpha1 API is unauthenticated and
cluster-internal by design. No public APM endpoint may expose it directly,
proxy it directly, or require callers to know its v1alpha1 routes.

## Endpoint Matrix

Initial APM endpoints are contract names, not an implementation commitment.
They describe the product facade shape that future code must satisfy.

| Endpoint | Purpose | Required scope | Data sources |
|---|---|---|---|
| `GET /apm/services` | List service health and ownership cards | `tenant`, `project`, `environment` | OTel resources, K8s metadata, Argus fleet rollups |
| `GET /apm/services/{service}` | Service overview with RED metrics and JVM pressure | `tenant`, `project`, `environment`, `service` | Prometheus/OTLP metrics, Argus findings, deployment metadata |
| `GET /apm/services/{service}/endpoints` | Endpoint latency/error/hotspot table | `tenant`, `project`, `environment`, `service` | OTel spans, route attributes, Argus correlation summaries |
| `GET /apm/traces/{traceId}` | Trace context with JVM findings and backend links | `tenant`, `project`, `environment`, `trace` | Trace backend, Argus GC/profile/contention/pinning joins |
| `GET /apm/incidents` | Incident list and timeline summaries | `tenant`, `project`, `environment` | Alerts, deploy markers, traces, logs, profiles, Argus findings |
| `GET /apm/backend-links` | Generate Grafana/Tempo/Loki/Pyroscope links | `tenant`, `project`, `environment` | Configured backend link templates |

Write endpoints are deferred until auth, audit, and ownership are defined. The
first APM release should be read-first.

## Required Claims

Every public APM request must be authorized before data is loaded. The facade
expects these claims from the auth layer:

| Claim | Required | Notes |
|---|---:|---|
| `sub` | yes | Stable user or service-account subject |
| `tenant` | yes | Top-level isolation boundary |
| `project` | yes | Product/application boundary |
| `environments` | yes | Allowed environments such as `dev`, `staging`, `prod` |
| `roles` | yes | At minimum `viewer`, `operator`, `admin` |
| `service_allowlist` | no | Optional service-level restriction |

Authorization is fail-closed. Missing tenant, project, or environment claims
return an authorization error before service, trace, log, profile, or fleet
data is queried.

## Scoping Rules

APM reads are scoped in this order:

1. `tenant`
2. `project`
3. `environment`
4. `service`
5. `deployment`
6. `instance` or `pod`
7. `endpoint`
8. time range

Cross-project or cross-environment joins are forbidden unless an explicit future
federation contract adds them. A trace that crosses services may be displayed
only for spans whose resources are visible to the caller; hidden spans must be
redacted or summarized as unavailable.

## Entity Source Of Truth

| Entity | Primary source | Fallback source | Conflict behavior |
|---|---|---|---|
| Service | OTel `service.name` and `service.namespace` | Kubernetes labels | Surface a metadata conflict diagnostic |
| Environment | OTel `deployment.environment.name` or `deployment.environment` | Helm/chart value | Surface a metadata conflict diagnostic |
| Deployment | Kubernetes deployment/statefulset metadata | OTel `service.version` plus pod labels | Prefer Kubernetes workload identity |
| Instance | OTel resource plus process/host identity | Argus `PodTarget` | Preserve both ids when they differ |
| Endpoint | OTel route/HTTP semantic attributes | normalized path template | Reject high-cardinality raw paths |
| JVM finding | Argus diagnostics and JFR-derived analysis | none | Keep Argus-specific namespace |
| Owner/runbook | future service catalog config | annotations | Mark missing owner/runbook explicitly |

## Identity Precedence

When multiple identity sources exist, use this precedence:

1. OpenTelemetry resource attributes.
2. Kubernetes metadata discovered by the operator or Downward API.
3. Argus fleet labels and scrape target metadata.
4. User-configured overrides.

Conflicts do not silently merge. The facade must preserve the winning identity
and expose a diagnostic that names the losing source.

## Backend Link Routing

Argus APM Core stores context and link templates, not first-party storage for
standard observability signals in the MVP.

| Signal | MVP backend | Link inputs |
|---|---|---|
| Metrics | Prometheus or Mimir | datasource, service, deployment, pod, time range |
| Traces | Tempo, Jaeger, or vendor OTLP backend | trace id, span id, service, time range |
| Logs | Loki or vendor log backend | trace id, span id, service, pod, time range |
| Profiles | Pyroscope and/or Argus profile store | service, pod, profile type, time range, trace id when present |

Every generated backend link must preserve tenant/project/environment context
where the backend supports it. Links that cannot encode scope must be marked as
best-effort and must not bypass Argus authorization.

The `argus-apm` link router generates MVP links for Prometheus/Grafana,
Tempo-or-Jaeger, Loki, and Pyroscope from scoped context only. It stores link
templates and request context, not backend signal data.

## Demo Topology

The `argus-apm` module includes a deterministic demo topology for end-to-end
APM workflows. It covers GC latency, lock contention, virtual-thread pinning,
and bad release regression incidents with service inventory, endpoint, trace,
JVM finding, backend link, local dashboard, and Grafana drilldown evidence.

## Aggregator Rule

Public APM code must not:

- expose `/fleet/*`, `/api/pods*`, `/profile/*`, or other v1alpha1 aggregator
  routes as public APM routes;
- call aggregator HTTP routes from browser code that may be reached outside the
  in-cluster trust boundary;
- treat `argus-aggregator` authentication as equivalent to APM facade
  authorization;
- accept caller-provided aggregator host or port values.

Allowed use:

- server-side internal reads from an in-cluster aggregator cache after the APM
  facade has authorized the request;
- importing aggregator rollups into APM entity summaries;
- preserving raw aggregator URLs only in internal debug evidence, never in
  public API contracts.

## Verification Requirements

Future APM implementation must add tests that prove:

- missing tenant/project/environment claims fail closed before data lookup;
- a caller cannot read a service, trace, profile, or fleet rollup outside its
  allowed scope;
- public APM route tests do not depend on raw aggregator v1alpha1 paths;
- route or build guards prevent an `argus-apm` public API from exposing
  unauthenticated aggregator endpoints;
- high-cardinality endpoint identities are normalized or rejected;
- backend links preserve service, pod, endpoint, trace, and time context.

## Relationship To Existing Contracts

- `docs/dashboard-contract.md` remains the shared metric and drilldown contract
  for Grafana and the local dashboard.
- `docs/aggregator-api.md` remains the internal v1alpha1 fleet cache contract.
- `docs/apm-security.md` defines ingress, authorization, cardinality, overhead,
  and self-observability guardrails for public APM deployments.
- This file is the public APM facade contract. If future code makes these
  contracts disagree, this file wins for public APM behavior and the others must
  be updated or explicitly scoped as internal.
