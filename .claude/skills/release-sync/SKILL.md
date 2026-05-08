---
name: release-sync
description: Audit and update Argus external distribution surfaces (Spring Boot starter, Helm chart, Docker, install scripts, Homebrew Formula, SDKMAN, GitHub Actions, action.yml) against gradle.properties and known-good upstream versions. Detects version drift, floating image tags, deprecated K8s APIs, and outdated dependencies. Use before a release, or when the user asks to "sync release", "update distributions", "check outdated deps", "verify external integrations".
argument-hint: "[--check] [--fix]"
---

# release-sync

Keep every Argus distribution channel aligned with the project version and free of stale dependencies.

## When to use

- Before tagging a release
- After bumping `argusVersion` in `gradle.properties`
- Periodic outdated-dependency sweep (Netty CVEs, Spring Boot, Micrometer)
- User says: "sync release", "release-sync", "check outdated", "verify distributions", "/release-sync"

## Authoritative sources

| Truth | Path |
|---|---|
| Project version | `gradle.properties` → `argusVersion` |
| Pinned framework versions | `gradle.properties` → `nettyVersion`, `junitVersion` |
| Module list | `settings.gradle.kts` |
| Java baseline | root `build.gradle.kts` toolchain |

## Targets

### Channel inventory

| Channel | Files | Version surface |
|---|---|---|
| Maven Central (starter) | `argus-spring-boot-starter/build.gradle.kts` | Spring Boot, Spring Context, Micrometer, configuration-processor versions |
| Helm chart | `charts/argus/Chart.yaml`, `charts/argus/values.yaml`, `charts/argus/templates/*.yaml`, `charts/argus/README.md` | `version`, `appVersion`, `kubeVersion`, image tag, K8s API versions |
| Docker compose | `deploy/docker-compose.yml` | image tags (must NOT be `:latest`) |
| Dockerfiles | `Dockerfile`, `deploy/docker/Dockerfile.*` | base image tag (e.g., `eclipse-temurin:21-jre-alpine`) |
| Install (Unix) | `install.sh` | `VERSION` fallback, `ASPROF_VERSION`, checksum verification logic |
| Install (Windows) | `install.ps1` | `Version` fallback, example block |
| Homebrew | `Formula/argus.rb` | `version`, `url`, `sha256`, `depends_on` JDK version |
| SDKMAN | `deploy/sdkman/argus-candidate.json`, `deploy/sdkman/README.md` | candidate version |
| GitHub Action | `action/action.yml` | `version` input default |
| CI workflows | `.github/workflows/{ci,release,docker,native-image,pages}.yml` | `actions/checkout@v?`, `actions/setup-java@v?`, JDK version, runner |

### Upstream "known-good" baselines (update this table when bumping)

These are the current stable minima. Refresh only when the user asks for a sweep.

| Library / image | Minimum acceptable | Why |
|---|---|---|
| Netty | `4.1.115.Final` | CVE-2024-47535 patched here |
| JUnit Jupiter | `5.11.x` | Bug fixes vs 5.10 |
| Spring Boot (compileOnly) | `3.2.0` (LTS-equivalent OK) | Argus targets Spring Boot 3.2+ |
| Micrometer | `1.12.0` (`1.13.x+` preferred for VT metrics) | Virtual Thread metric surface |
| `actions/checkout` | `v4` | v3 deprecated track |
| `actions/setup-java` | `v4` | v3 deprecated track |
| `eclipse-temurin` base | `21-jre-alpine` | Project Java baseline |
| `prom/prometheus` | a pinned `vX.Y.Z`, never `latest` | reproducibility |
| `grafana/grafana` | a pinned `X.Y.Z`, never `latest` | reproducibility |
| Kubernetes API | `apps/v1`, `networking.k8s.io/v1`, `monitoring.coreos.com/v1` | K8s 1.25+ removed `*beta1` variants |

## Procedure

### 1. Resolve truth

```bash
ARGUS_VERSION="$(awk -F= '/^argusVersion=/{print $2}' gradle.properties)"
NETTY="$(awk -F= '/^nettyVersion=/{print $2}' gradle.properties)"
JUNIT="$(awk -F= '/^junitVersion=/{print $2}' gradle.properties)"
echo "TRUTH version=$ARGUS_VERSION netty=$NETTY junit=$JUNIT"
```

### 2. Per-channel drift checks

#### 2.1 Helm chart

```bash
grep -E '^(version|appVersion|kubeVersion):' charts/argus/Chart.yaml
```

- `version` and `appVersion` MUST equal `$ARGUS_VERSION` (appVersion in quotes).
- `kubeVersion` MUST be present, e.g. `>=1.23.0-0`.
- Grep templates for deprecated APIs:
  ```bash
  grep -rEn 'apiVersion: (extensions/v1beta1|policy/v1beta1|networking\.k8s\.io/v1beta1)' charts/argus/templates/
  ```
  Any hit is a P0 — those resources won't apply on K8s 1.25+.
- Verify `image.tag` in `values.yaml` matches `$ARGUS_VERSION` (or is omitted to inherit appVersion).

#### 2.2 Docker compose

```bash
grep -nE 'image:\s*[^[:space:]]+:latest' deploy/docker-compose.yml
```
Any hit is a P0 — pin to a concrete version.

#### 2.3 Dockerfiles

```bash
grep -rEn '^FROM ' Dockerfile deploy/docker/
```
Confirm base image is `eclipse-temurin:21-jre-alpine` (or the agreed baseline). Flag any divergence.

#### 2.4 install.sh / install.ps1

```bash
grep -nE "VERSION=\"v|fallback|Version = \"v" install.sh install.ps1
```
- Fallback string MUST be `v$ARGUS_VERSION`.
- `install.sh` example URLs in the comment header use a recent version.
- Confirm `ASPROF_VERSION` matches the version Argus actually wraps.

#### 2.5 Homebrew Formula

```bash
grep -E "(version|url|sha256)" Formula/argus.rb
```
- `version` matches `$ARGUS_VERSION`.
- `url` points at the GitHub release for that version.
- `sha256` matches the actual JAR/binary on the release; if updating version, recompute:
  ```bash
  curl -fsSL "<url>" | shasum -a 256
  ```
  Never invent a hash — compute or escalate.

#### 2.6 SDKMAN

```bash
grep -E '"version"|"url"' deploy/sdkman/argus-candidate.json
```
Version field equals `$ARGUS_VERSION`.

#### 2.7 GitHub Action

```bash
grep -nE '(version|default):' action/action.yml
```
`version` input default should be `$ARGUS_VERSION` or `latest` — confirm with user which convention this repo uses.

#### 2.8 CI workflows

```bash
grep -rnE 'uses: [a-z-]+/[a-z-]+@v[0-9]+' .github/workflows/
grep -rnE 'java-version: ' .github/workflows/
```
Compare action @vN against the baseline table. JDK version should match the project toolchain (21).

#### 2.9 Spring Boot starter dependencies

```bash
grep -E "compileOnly|annotationProcessor|api\(" argus-spring-boot-starter/build.gradle.kts
```
Compare against baseline minima above. Spring Boot can stay at `3.2.0` if intentional, but flag `< 3.2.0` as a hard fail.

#### 2.10 Outdated framework versions in gradle.properties

```bash
cat gradle.properties
```
Compare to baseline minima. Netty `< 4.1.115.Final` is a P0 (CVE).

### 3. Fix policy

- **Pure version swaps** with a clear baseline (Helm appVersion, install fallback, compose pin): edit directly.
- **Dependency bumps** that touch `gradle.properties` or starter `build.gradle.kts`: run `./gradlew compileJava` and `./gradlew :argus-cli:test` after the edit; revert if it breaks.
- **Homebrew `sha256`**: never guess. Either fetch the release artifact and compute, or escalate.
- **Deprecated K8s APIs**: replace per K8s migration guide; do not silently delete a resource.
- **GitHub Action default version**: ask the user — some projects pin to specific tags, some prefer `latest`.

### 4. Verify

```bash
./gradlew compileJava --quiet         # bumps don't break compilation
./gradlew :argus-cli:test --quiet     # tests still pass
helm lint charts/argus                # if helm is on PATH
docker compose -f deploy/docker-compose.yml config >/dev/null   # compose syntax OK
```

## Output format

```
release-sync report
===================
Truth:  version=1.2.0  netty=4.1.115.Final  junit=5.11.4

Channel matrix:
  starter      : OK     (Spring Boot 3.2.0 compileOnly, Micrometer 1.12.0)
  helm         : FIXED  Chart.yaml +kubeVersion: ">=1.23.0-0"
  docker       : FIXED  prom/prometheus:latest → :v2.55.0; grafana/grafana:latest → 11.3.0
  install.sh   : FIXED  fallback v1.1.0 → v1.2.0
  install.ps1  : FIXED  fallback v0.4.0 → v1.2.0; example block
  formula      : OK
  sdkman       : OK
  action.yml   : OK     (default version: latest, by design)
  ci-workflows : OK     (actions @v4, JDK 21)

Dep bumps:
  netty 4.1.104.Final → 4.1.115.Final  (CVE-2024-47535)
  junit 5.10.1        → 5.11.4

Verification:
  ./gradlew compileJava → exit 0
  ./gradlew :argus-cli:test → exit 0

Escalated to user:
  - Homebrew Formula sha256 — release artifact not yet uploaded; rerun after release publish.
```

## Project rules to honor

- Never commit to master directly — finish on a branch and propose a PR.
- Don't push tags; the release workflow handles that on a tag push.
- Don't bump `argusVersion` from this skill — that belongs to the release flow.
- Never write `--no-verify` or skip hooks.
- Don't invent SHA256 or release URLs; compute them or escalate.

## Out of scope

- Editing user-facing prose / READMEs / site copy → use `docs-sync`.
- Publishing to Maven Central (signing, Sonatype upload) — release workflow only.
- Generating SBOM / SLSA attestation — separate concern; flag if missing but don't implement here.
