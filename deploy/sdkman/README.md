# SDKMAN Candidate Submission

This directory contains the candidate definition for submitting Argus to the [SDKMAN vendors portal](https://vendors.sdkman.io/).

## Prerequisites

1. Register at https://vendors.sdkman.io/ with your GitHub account
2. Create a candidate (one-time) via the portal — use `argus-candidate.json` as reference for the fields
3. Obtain your **Consumer Key** and **Consumer Secret** from the portal

## Releasing a New Version

Once the candidate is registered, publish each release via the SDKMAN Vendor API:

```bash
VERSION="1.0.0"
CONSUMER_KEY="<your-key>"
CONSUMER_SECRET="<your-secret>"

# Publish the release
curl -X POST \
  "https://vendors.sdkman.io/release" \
  -H "Consumer-Key: $CONSUMER_KEY" \
  -H "Consumer-Token: $CONSUMER_SECRET" \
  -H "Content-Type: application/json" \
  -d "{
    \"candidate\": \"argus\",
    \"version\": \"$VERSION\",
    \"url\": \"https://github.com/rlaope/Argus/releases/download/v${VERSION}/argus-cli-${VERSION}-all.jar\"
  }"

# Set as default (optional)
curl -X PUT \
  "https://vendors.sdkman.io/default/candidate/argus/version/$VERSION" \
  -H "Consumer-Key: $CONSUMER_KEY" \
  -H "Consumer-Token: $CONSUMER_SECRET"

# Announce (optional — sends broadcast to SDKMAN users)
curl -X POST \
  "https://vendors.sdkman.io/announce/struct" \
  -H "Consumer-Key: $CONSUMER_KEY" \
  -H "Consumer-Token: $CONSUMER_SECRET" \
  -H "Content-Type: application/json" \
  -d "{
    \"candidate\": \"argus\",
    \"version\": \"$VERSION\",
    \"hashtag\": \"argus\"
  }"
```

## Installation (after acceptance)

Once accepted, users can install Argus via:

```bash
sdk install argus
```

Or a specific version:

```bash
sdk install argus 1.0.0
```

## Notes

- SDKMAN distributes the fat JAR directly — no native binary needed
- The JAR requires Java 11+ on the user's machine (SDKMAN does not manage the JDK dep)
- Platform entries in `argus-candidate.json` all point to the same JAR since it is platform-independent
- For the initial submission, expect a review period of a few days
