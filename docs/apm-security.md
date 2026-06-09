# Argus APM Security And Production Guardrails

Status: planning and implementation guard for Argus APM Core.

## Public Boundary

Only authenticated `argus-apm` facade routes may be exposed outside the cluster.
The existing `argus-aggregator` v1alpha1 routes remain internal and must not be
published through ingress, browser JavaScript, reverse proxies, or public API
docs.

## Required Ingress Policy

- Route `/apm/*` only to the future APM facade service.
- Do not route `/fleet/*`, `/api/pods*`, `/profile/*`, or aggregator debug
  paths through public ingress.
- Terminate TLS before the facade and forward only trusted identity claims.
- Preserve tenant, project, and environment claims through the auth layer.
- Reject caller-supplied aggregator host or port values.

## Authorization Rules

- Missing principal or scope fails closed before data lookup.
- Tenant, project, and environment must match the caller's claims.
- Service allowlists restrict service, trace, profile, and incident reads.
- Aggregator authentication is not equivalent to APM authorization.

## Cardinality And Overhead Rules

- Endpoint paths must use route templates such as `/checkout/{id}`.
- High-cardinality raw ids, UUIDs, emails, and long hex values are normalized
  before they become APM entity keys.
- Runaway endpoint counts, trace finding fanout, and backend link fanout are
  rejected by facade guardrails before response construction.

## Self-Observability

The facade must emit its own counters and latency gauges:

- `argus_apm_facade_requests_total`
- `argus_apm_facade_errors_total`
- `argus_apm_authorization_denied_total`
- `argus_apm_route_rejected_total`
- `argus_apm_backend_links_generated_total`
- `argus_apm_facade_latency_avg_ms`

These metrics describe facade health only; they do not turn Argus into a
first-party metrics, trace, log, or profile datastore.
