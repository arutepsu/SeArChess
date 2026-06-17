#!/bin/sh
# Entry point for GameEventStreamingAnalyticsJob (Spark Structured Streaming).
#
# The sbt-native-packager staged binary (bin/spark-analytics) defaults to
# chess.analytics.app.GameAnalyticsJob (the batch job). APP_MAIN_CLASS is NOT
# honored by the generated launcher. Instead this script delegates to the
# generated bin/game-event-streaming-analytics-job, which passes
# -main chess.analytics.app.GameEventStreamingAnalyticsJob via CLI args.
#
# All configuration is read from environment variables by LiveGameAnalyticsConfig.fromEnv().

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

# sbt-native-packager generates bin/game-event-streaming-analytics-job, which calls:
#   bin/spark-analytics -main chess.analytics.app.GameEventStreamingAnalyticsJob
# APP_MAIN_CLASS env var is NOT honored by the generated launcher; use the generated
# per-class script instead, which passes -main via CLI args (the supported override path).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/game-event-streaming-analytics-job"
