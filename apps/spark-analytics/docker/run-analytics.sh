#!/bin/sh
# Wrapper around the sbt-native-packager-staged spark-analytics binary.
#
# The generated launcher script (target/universal/stage/bin/spark-analytics)
# does not bake in JVM flags by default. Spark 3.5.x needs --add-opens on
# Java 17+ (see sparkJvmOpens in build.sbt — this list must stay in sync with
# that one). Setting JAVA_OPTS here, scoped to this one exec, keeps the
# tournament-service runner and TournamentServiceConfig free of Spark-specific
# JVM internals: TOURNAMENT_ANALYTICS_COMMAND just points at this script and
# passes <inputPath> <outputPath> through unchanged.
#
# Usage: run-analytics.sh <inputJsonlPath> <outputPath>

export JAVA_OPTS="--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens=java.base/java.io=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.nio=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens=java.base/sun.nio.cs=ALL-UNNAMED \
--add-opens=java.base/sun.security.action=ALL-UNNAMED \
--add-opens=java.base/sun.util.calendar=ALL-UNNAMED \
--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/spark-analytics" "$@"
