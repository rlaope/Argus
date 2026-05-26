# Argus Aggregator REST API Contract

**Version:** v1alpha1  
**Base URL:** `http://<aggregator-host>:<port>/`  
**Content-Type:** `application/json` (all request and response bodies)  
**Auth:** None (day-1; ingress-level auth deferred to follow-up ADR)

---

## Security Model

All endpoints are **unauthenticated by design** (v1alpha1). The aggregator is intended to live inside a Kubernetes cluster, accessed only by:

- **argus-operator** — `POST /fleet/targets`, `DELETE /fleet/targets/{podId}`
- **argus-frontend** — `GET /fleet/list`, `GET /fleet/pod/{podId}`, `GET /fleet/summary`, `GET /fleet/alerts`
- **Prometheus scraper** — `GET /metrics`

**DO NOT expose the aggregator service externally.** The Helm chart ships a `NetworkPolicy` that restricts ingress to in-cluster components when `operator.enabled=true`. Verify this policy is active before deploying to a production cluster.

Future (post-v1alpha1): bearer token or mTLS on `POST /fleet/targets` and `DELETE /fleet/targets/{podId}`.

---

## Overview

`argus-aggregator` exposes a REST API consumed by:

- **argus-frontend** (`/fleet` page — tile grid, drill-down, alert overlay)
- **argus-operator** (registers and deregisters scrape targets)
- **Prometheus scrapers** (`/metrics` endpoint in Prometheus exposition format)

All JSON bodies use record-shaped objects (flat, no polymorphic wrapping). Field names use `camelCase`. Timestamps are ISO-8601 strings in UTC (e.g. `"2026-05-26T10:30:00Z"`). Unknown fields in request bodies are ignored. Missing optional request fields default to their documented defaults.

---

## Data Shapes (Shared Records)

These shapes appear in multiple endpoints. Defined once here; referenced below.

### `PodTarget`

Represents one registered scrape target (a pod running argus-agent).

```json
{
  "podId":        "string",   // unique ID: "<namespace>/<podName>" e.g. "prod/payment-5c7d9f-xkz2q"
  "namespace":    "string",   // K8s namespace
  "podName":      "string",   // K8s pod name
  "deployment":   "string",   // K8s deployment/statefulset name (empty string if unknown)
  "host":         "string",   // pod IP or hostname
  "port":         "integer",  // argus-agent HTTP port (typically 7070)
  "scrapeUrl":    "string",   // derived: "http://<host>:<port>" — read-only, computed by aggregator
  "registeredAt": "string",   // ISO-8601 UTC timestamp of first registration
  "lastScrapeAt": "string",   // ISO-8601 UTC timestamp of most recent successful scrape (null if never scraped)
  "scrapeOk":     "boolean"   // true if last scrape succeeded
}
```

### `TileMetrics`

Summary metrics used to render one tile in the fleet grid.

```json
{
  "heapPercent":      "number | null",  // JVM heap used % (0–100), null if unavailable
  "gcOverheadPercent":"number | null",  // GC overhead % (0–100), null if unavailable
  "cpuPercent":       "number | null",  // process CPU % (0–100), null if unavailable
  "activeVThreads":   "integer",        // active virtual threads count (0 if not applicable)
  "leakSuspected":    "boolean"         // true if memory leak signal detected
}
```

### `TileColor`

Enum string — one of `"green"`, `"yellow"`, `"red"`, `"grey"`.

- `"green"`: all metrics within normal bounds, no active alerts
- `"yellow"`: at least one metric approaching threshold, or non-critical alert
- `"red"`: at least one metric breached threshold, or critical/warning alert active
- `"grey"`: target unreachable or never successfully scraped

### `Tile`

Full tile descriptor for one pod. Used in `/fleet/list` and `/fleet/pod/{podId}`.

```json
{
  "podId":      "string",       // matches PodTarget.podId
  "color":      "TileColor",    // "green" | "yellow" | "red" | "grey"
  "target":     "PodTarget",    // embedded full target record
  "metrics":    "TileMetrics",  // latest scraped metrics
  "alertCount": "integer",      // number of active (firing) alerts for this pod
  "drillDownUrl":"string"       // frontend relative URL: "/pod/<podId>" — for drill-down link
}
```

### `FleetSummary`

Cluster-wide roll-up.

```json
{
  "totalTargets":    "integer",  // total registered targets
  "upTargets":       "integer",  // targets with scrapeOk=true
  "downTargets":     "integer",  // targets with scrapeOk=false
  "greenCount":      "integer",
  "yellowCount":     "integer",
  "redCount":        "integer",
  "greyCount":       "integer",
  "totalAlerts":     "integer",  // sum of active alerts across all pods
  "heap": {
    "min":  "number | null",     // % across up targets
    "max":  "number | null",
    "avg":  "number | null"
  },
  "gc": {
    "min":  "number | null",
    "max":  "number | null",
    "avg":  "number | null"
  },
  "cpu": {
    "min":  "number | null",
    "max":  "number | null",
    "avg":  "number | null"
  },
  "totalActiveVThreads": "integer",
  "leakSuspectedCount":  "integer",
  "worstPodId":          "string | null",  // podId of highest-GC or highest-heap pod
  "worstReason":         "string | null"   // human-readable reason e.g. "GC overhead 42.3%"
}
```

### `AlertEvent`

A single firing alert.

```json
{
  "alertId":    "string",   // stable ID: "<podId>/<ruleName>"
  "podId":      "string",   // which target triggered this alert
  "ruleName":   "string",   // alert rule name as configured
  "metric":     "string",   // Prometheus metric name that breached
  "value":      "number",   // metric value at breach time
  "threshold":  "number",   // configured threshold
  "comparator": "string",   // ">", ">=", "<", "<="
  "severity":   "string",   // "critical" | "warning" | "info"
  "firedAt":    "string",   // ISO-8601 UTC timestamp when alert first fired
  "ongoing":    "boolean"   // true if still breached on latest scrape
}
```

---

## Endpoints

---

### `GET /fleet/list`

Returns all registered targets as tiles. The frontend uses this to render the full pod grid.

#### Request

No request body. Optional query parameters:

| Parameter    | Type   | Default | Description |
|--------------|--------|---------|-------------|
| `namespace`  | string | (all)   | Filter tiles to a single K8s namespace |
| `deployment` | string | (all)   | Filter tiles to a single deployment name |
| `color`      | string | (all)   | Filter by tile color: `green`, `yellow`, `red`, `grey` |

#### Response `200 OK`

```json
{
  "tiles":      ["Tile"],   // array of Tile records, ordered by podId ascending
  "totalCount": "integer",  // total tiles (before any filter — always reflects full fleet size)
  "filteredCount": "integer" // tiles returned after filters applied
}
```

#### Error Codes

| Code | Condition |
|------|-----------|
| 400  | Invalid `color` value |
| 500  | Internal aggregator error |

#### Notes

- Empty fleet returns `{ "tiles": [], "totalCount": 0, "filteredCount": 0 }` with `200`.
- `drillDownUrl` in each tile is always set regardless of filter.

---

### `GET /fleet/pod/{podId}`

Returns full tile detail for a single pod. Used for the drill-down view.

#### Path Parameter

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `podId`   | string | URL-encoded pod ID: `<namespace>%2F<podName>` |

#### Request

No request body.

#### Response `200 OK`

```json
{
  "tile":     "Tile",          // full Tile record for this pod
  "alerts":   ["AlertEvent"],  // active alerts for this pod (empty array if none)
  "history":  {
    "windowSeconds": "integer",   // ring buffer retention in seconds (default 3600)
    "sampleCount":   "integer",   // number of samples in buffer for this pod
    "samples": [
      {
        "ts":               "string",   // ISO-8601 UTC sample timestamp
        "heapPercent":      "number | null",
        "gcOverheadPercent":"number | null",
        "cpuPercent":       "number | null",
        "activeVThreads":   "integer"
      }
    ]
  }
}
```

#### Error Codes

| Code | Condition |
|------|-----------|
| 404  | `podId` not registered |
| 500  | Internal aggregator error |

---

### `GET /fleet/summary`

Returns cluster-wide aggregated statistics. Used for the fleet command center header bar.

#### Request

No request body.

#### Response `200 OK`

```json
{
  "summary": "FleetSummary"
}
```

#### Error Codes

| Code | Condition |
|------|-----------|
| 500  | Internal aggregator error |

#### Notes

- Always returns `200` even with zero targets. All numeric fields default to `null` when no data is available.

---

### `POST /fleet/targets`

Registers one scrape target. Called by argus-operator when it discovers a new argus-agent pod. Idempotent: registering an already-known `podId` updates the record in place.

#### Request Body

```json
{
  "podId":      "string",    // required; "<namespace>/<podName>"
  "namespace":  "string",    // required
  "podName":    "string",    // required
  "deployment": "string",    // optional; defaults to ""
  "host":       "string",    // required; pod IP or resolvable hostname
  "port":       "integer"    // required; argus-agent port (e.g. 7070)
}
```

#### Response `201 Created` (new) or `200 OK` (updated existing)

```json
{
  "podId":        "string",   // echoed back
  "registeredAt": "string",   // ISO-8601 UTC — original registration time (unchanged on update)
  "updated":      "boolean"   // true if this was an update to an existing target
}
```

#### Error Codes

| Code | Condition |
|------|-----------|
| 400  | Missing required field, invalid `port` (≤0 or >65535), or `host` rejected by allowlist (see below) |
| 500  | Internal aggregator error |

#### Host Allowlist (SSRF prevention)

The `host` field must be either an in-cluster DNS name (ending in `.svc.cluster.local` or `.local`) or an address in a private CIDR (RFC 1918: `10/8`, `172.16/12`, `192.168/16`). The aggregator rejects the following with `400 Bad Request` to prevent server-side request forgery:

- Link-local addresses (`169.254.0.0/16`, `fe80::/10`)
- Loopback addresses (`127.0.0.0/8`, `::1`)
- Unspecified address (`0.0.0.0`, `::`)
- Any host that resolves outside the above allowlist at registration time

See `HostAllowlist` in `argus-aggregator` for the implementation.

#### Idempotency

Re-registering a `podId` with different `host`/`port` updates the scrape URL immediately. The aggregator will use the new address on the next scrape cycle. `registeredAt` is preserved from the original registration.

---

### `DELETE /fleet/targets/{podId}`

Removes a scrape target from the registry. Called by argus-operator when a pod is deleted or loses the `argus.io/scrape=true` label. Idempotent: deleting an unknown `podId` returns `204`.

#### Path Parameter

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `podId`   | string | URL-encoded pod ID: `<namespace>%2F<podName>` |

#### Request

No request body.

#### Response `204 No Content`

Empty body on success (whether the target existed or not).

#### Error Codes

| Code | Condition |
|------|-----------|
| 500  | Internal aggregator error |

#### Notes

- Ring buffer data for the deleted pod is discarded immediately.
- Any active alert events for the pod are also cleared.

---

### `GET /fleet/alerts`

Returns all currently-firing alerts across the entire fleet. Used for the alert overlay on the fleet view.

#### Request

No request body. Optional query parameters:

| Parameter  | Type   | Default    | Description |
|------------|--------|------------|-------------|
| `podId`    | string | (all pods) | Filter alerts to a specific pod |
| `severity` | string | (all)      | Filter by severity: `critical`, `warning`, `info` |

#### Response `200 OK`

```json
{
  "alerts":     ["AlertEvent"],  // all firing AlertEvent records matching filters
  "totalCount": "integer"        // total firing alerts before filter
}
```

#### Error Codes

| Code | Condition |
|------|-----------|
| 400  | Invalid `severity` value |
| 500  | Internal aggregator error |

#### Notes

- Returns `{ "alerts": [], "totalCount": 0 }` with `200` when no alerts are firing.
- `ongoing: true` on all returned events (resolved events are not included).

---

### `GET /metrics`

Prometheus exposition format metrics for the aggregator itself and per-pod fleet data. Intended for scraping by an external Prometheus instance.

#### Request

No request body. Honors standard Prometheus `Accept` negotiation (text/plain; version=0.0.4).

#### Response `200 OK`

Content-Type: `text/plain; version=0.0.4; charset=utf-8`

Exposes the following metric families:

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `argus_aggregator_targets_total` | gauge | — | Total registered targets |
| `argus_aggregator_targets_up` | gauge | — | Targets with last scrape OK |
| `argus_aggregator_scrape_duration_seconds` | histogram | `pod_id` | Per-pod scrape duration |
| `argus_aggregator_scrape_errors_total` | counter | `pod_id` | Cumulative scrape failures per pod |
| `argus_fleet_heap_percent` | gauge | `pod_id`, `namespace`, `deployment` | Latest heap used % per pod |
| `argus_fleet_gc_overhead_percent` | gauge | `pod_id`, `namespace`, `deployment` | Latest GC overhead % per pod |
| `argus_fleet_cpu_percent` | gauge | `pod_id`, `namespace`, `deployment` | Latest CPU % per pod |
| `argus_fleet_active_vthreads` | gauge | `pod_id`, `namespace`, `deployment` | Active virtual threads per pod |
| `argus_fleet_leak_suspected` | gauge | `pod_id`, `namespace`, `deployment` | 1.0 if leak suspected, 0.0 otherwise |
| `argus_fleet_alert_firing` | gauge | `pod_id`, `rule_name`, `severity` | 1.0 if alert firing, 0.0 otherwise |

#### Error Codes

| Code | Condition |
|------|-----------|
| 500  | Internal aggregator error |

---

## Error Response Shape

All `4xx` and `5xx` responses use this envelope:

```json
{
  "error": {
    "code":    "integer",  // HTTP status code
    "message": "string"    // human-readable description
  }
}
```

Example:

```json
{
  "error": {
    "code": 404,
    "message": "pod 'prod/payment-5c7d9f-xkz2q' not registered"
  }
}
```

---

## Pod ID Encoding

`podId` has the shape `{namespace}/{podName}`. In JSON bodies and query string values the literal `/` is used as-is. When used as a **URL path segment** (e.g. `/fleet/pod/{podId}`, `/fleet/targets/{podId}`), the entire podId MUST be URL-encoded so the router treats it as a single segment.

Encoding rules:

| Context | Encoding | Example input | Encoded form |
|---------|----------|---------------|--------------|
| JSON body / query param | none (literal `/`) | `prod/payment-5c7d9f-xkz2q` | `prod/payment-5c7d9f-xkz2q` |
| URL path segment | `/` → `%2F` | `prod/payment-5c7d9f-xkz2q` | `prod%2Fpayment-5c7d9f-xkz2q` |

Full URL examples:

| Raw podId | Full URL |
|-----------|----------|
| `prod/payment-5c7d9f-xkz2q` | `GET /fleet/pod/prod%2Fpayment-5c7d9f-xkz2q` |
| `monitoring/argus-agent-0` | `DELETE /fleet/targets/monitoring%2Fargus-agent-0` |

> **Important for implementors:** Do not double-encode. If you are building a URL with `URLEncoder.encode(podId, UTF_8)` in Java, that will encode `/` to `%2F` correctly. Do not then also call a generic URL encoder on the full path — that would turn `%2F` into `%252F`, which the router will not match.

### Server-side podId validation

The aggregator validates every podId received in a URL path segment and rejects with `400 Bad Request` if any of the following are true:

- Contains a null byte (` `)
- Contains the path-traversal sequence `..`
- Total length exceeds 253 characters (K8s name length limit for namespace + `/` + podName)
- Does not contain exactly one `/` separator

Namespace names and pod names follow standard K8s naming (lowercase alphanumeric and `-`). They will never contain characters that require additional percent-encoding beyond the `/` separator.

---

## Webhook URL Validation

Webhook URLs referenced by alert rules (inline `webhookUrl` on `AlertRule`, and `ArgusFleet.alertWebhook` in the operator CRD) are validated before use:

- Only `http` and `https` schemes are accepted.
- The resolved host must not be a loopback address (`127.0.0.0/8`, `::1`), link-local address (`169.254.0.0/16`, `fe80::/10`), or unspecified address (`0.0.0.0`).
- Invalid webhook URLs cause the alert rule to be rejected at load time with a configuration error (not silently swallowed).

This applies both to webhooks configured inline on alert rules and to `ArgusFleet.alertWebhook` set via the operator CRD field.

---

## Scrape Endpoints Pulled From argus-agent

The aggregator pulls these endpoints from each registered `PodTarget` on each scrape cycle:

| Endpoint | Used For |
|----------|----------|
| `GET /prometheus` | Raw Prometheus metrics → `TileMetrics` fields |
| `GET /gc-analysis` | GC log analysis result (stored in ring buffer, surfaced via drill-down) |
| `GET /doctor` | Doctor report (stored in ring buffer, surfaced via drill-down) |

These are pull-only. argus-agent code is not modified.

---

## Java Record Mapping (for W1 implementor)

The following Java records in `argus-aggregator` directly implement the shapes above:

| JSON Shape | Java Record |
|------------|-------------|
| `PodTarget` | `io.argus.aggregator.model.PodTarget` |
| `TileMetrics` | `io.argus.aggregator.model.TileMetrics` |
| `Tile` | `io.argus.aggregator.model.Tile` |
| `FleetSummary` | `io.argus.aggregator.model.FleetSummary` |
| `AlertEvent` | `io.argus.aggregator.model.AlertEvent` |
| Ring buffer sample | `io.argus.aggregator.model.MetricSample` |

Serialization: Jackson with `@JsonInclude(NON_NULL)`. `null` fields are omitted from responses.

HTTP handlers live in `io.argus.aggregator.http.FleetController`.

---

## Changelog

| Date | Change |
|------|--------|
| 2026-05-26 | Initial v1alpha1 contract |
| 2026-05-26 | Add security warning (no auth day-1, ClusterIP-only); clarify podId URL encoding rules + double-encode warning |
| 2026-05-26 | Promote security warning to full "Security Model" section; add podId server-side validation rules (null byte, .., length >253); add SSRF host allowlist to POST /fleet/targets; add webhook URL validation section |
