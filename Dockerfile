# syntax=docker/dockerfile:1

FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.12.6_3.8.2 AS build

WORKDIR /build

COPY project/ project/
COPY build.sbt ./
COPY apps/ apps/
COPY modules/ modules/

<<<<<<< HEAD
RUN sbt "gameService / stage"

FROM eclipse-temurin:21-jre AS otel-agent
ARG OTEL_AGENT_VERSION=2.10.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /opentelemetry-javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"
=======
RUN sbt "bootstrapServer / stage"
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data

<<<<<<< HEAD
COPY --from=build /build/apps/game-service/target/universal/stage/ ./
COPY --from=otel-agent /opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
=======
COPY --from=build /build/apps/bootstrap-server/target/universal/stage/ ./
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)

EXPOSE 8080 9090

ENV HTTP_HOST=0.0.0.0 \
    HTTP_PORT=8080 \
    WS_ENABLED=true \
    WS_PORT=9090 \
<<<<<<< HEAD
    EVENT_MODE=in-process \
    AI_PROVIDER_MODE=remote \
    AI_REMOTE_BASE_URL=http://ai-service:8765 \
    AI_TIMEOUT_MILLIS=15000
=======
    PERSISTENCE_MODE=sqlite \
    CHESS_DB_PATH=/data/searchess.sqlite \
    EVENT_MODE=in-process \
    AI_PROVIDER_MODE=remote \
    AI_REMOTE_BASE_URL=http://ai-service:8765 \
    AI_TIMEOUT_MILLIS=2000
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)

CMD ["bin/searchess-game-service"]
