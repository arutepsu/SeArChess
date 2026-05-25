# syntax=docker/dockerfile:1

FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.12.6_3.8.2 AS build

WORKDIR /build

COPY project/ project/
COPY build.sbt ./
COPY apps/ apps/
COPY modules/ modules/

RUN sbt "gameService / stage"

FROM eclipse-temurin:21-jre AS otel-agent
ARG OTEL_AGENT_VERSION=2.10.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /opentelemetry-javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data

COPY --from=build /build/apps/game-service/target/universal/stage/ ./
COPY --from=otel-agent /opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

EXPOSE 8080 9090

ENV HTTP_HOST=0.0.0.0 \
    HTTP_PORT=8080 \
    WS_ENABLED=true \
    WS_PORT=9090 \
    EVENT_MODE=in-process \
    AI_PROVIDER_MODE=remote \
    AI_REMOTE_BASE_URL=http://ai-service:8765 \
    AI_TIMEOUT_MILLIS=2000

CMD ["bin/searchess-game-service"]
