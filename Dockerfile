FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build
COPY . .
RUN chmod +x gradlew && ./gradlew :argus-cli:fatJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="Argus" \
      org.opencontainers.image.description="Lightweight JVM Diagnostic Toolkit — 50+ CLI commands" \
      org.opencontainers.image.url="https://github.com/rlaope/Argus" \
      org.opencontainers.image.source="https://github.com/rlaope/Argus"

COPY --from=builder /build/argus-cli/build/libs/argus-cli-*-all.jar /opt/argus/argus-cli.jar

# Create wrapper script
RUN echo '#!/bin/sh' > /usr/local/bin/argus && \
    echo 'exec java -jar /opt/argus/argus-cli.jar "$@"' >> /usr/local/bin/argus && \
    chmod +x /usr/local/bin/argus

ENTRYPOINT ["argus"]
CMD ["--help"]
