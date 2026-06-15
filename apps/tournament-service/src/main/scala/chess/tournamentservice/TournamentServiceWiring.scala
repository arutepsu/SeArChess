package chess.tournamentservice

import cats.effect.{FiberIO, IO}
import cats.effect.unsafe.implicits.global
import chess.observability.StructuredLog
import chess.tournamentservice.db.{InMemoryTournamentJobRepository, SlickOutboxEventRepository, SlickTournamentJobRepository}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.duration.*

object TournamentServiceWiring:

  def start(config: TournamentServiceConfig): TournamentServiceRuntime =
    val registry   = DefaultBotRegistry(config)
    val (repository, outboxRepoOpt) = buildRepositories(config)
    val service    = TournamentJobService.create(registry, config, SparkTournamentAnalyticsProcessRunner(config.analyticsSbtCommand), repository).unsafeRunSync()
    val worker     = service.startWorker().unsafeRunSync()
    val analyticsWorkers = service.startAnalyticsWorkers().unsafeRunSync()
    val httpApp    = TournamentRoutes(service).routes.orNotFound

    val host = Host
      .fromString(config.host)
      .getOrElse(throw RuntimeException(s"Invalid TOURNAMENT_HTTP_HOST: ${config.host}"))
    val port = Port
      .fromInt(config.port)
      .getOrElse(throw RuntimeException(s"Invalid TOURNAMENT_HTTP_PORT: ${config.port}"))

    val (_, shutdownHttp) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()

    val (outboxPollerFiber, closeResources) = buildOutboxPoller(config, outboxRepoOpt)

    TournamentServiceRuntime(shutdownHttp, worker, analyticsWorkers, outboxPollerFiber, closeResources)

  private def buildRepositories(config: TournamentServiceConfig) =
    config.jobStore match
      case "postgres" =>
        val url      = config.postgresUrl.getOrElse(throw RuntimeException("TOURNAMENT_POSTGRES_URL is required when TOURNAMENT_JOB_STORE=postgres"))
        val user     = config.postgresUser.getOrElse("")
        val password = config.postgresPassword.getOrElse("")
        val db       = Database.forURL(url = url, user = user, password = password, driver = "org.postgresql.Driver")
        StructuredLog.info("tournament-service", "job_store_postgres", "schema" -> config.postgresSchema)
        val jobRepo    = SlickTournamentJobRepository(db, config.postgresSchema)
        val outboxRepo = SlickOutboxEventRepository(db, config.postgresSchema)
        jobRepo.initSchema().unsafeRunSync()
        (jobRepo, Some(outboxRepo))
      case _ =>
        StructuredLog.info("tournament-service", "job_store_memory")
        val repo = InMemoryTournamentJobRepository.create().unsafeRunSync()
        (repo, None)

  private def buildOutboxPoller(
      config: TournamentServiceConfig,
      outboxRepoOpt: Option[SlickOutboxEventRepository]
  ): (Option[FiberIO[Nothing]], IO[Unit]) =
    if config.outboxPublisherEnabled then
      outboxRepoOpt match
        case Some(outboxRepo) =>
          val (publisher, closePublisher) = buildPublisher(config)
          val poller = OutboxPoller(
            repo         = outboxRepo,
            publisher    = publisher,
            pollInterval = config.outboxPollIntervalSeconds.seconds,
            batchSize    = config.outboxBatchSize
          )
          StructuredLog.info(
            "tournament-service",
            "outbox_poller_starting",
            "publisherType"       -> config.outboxPublisherType,
            "pollIntervalSeconds" -> config.outboxPollIntervalSeconds,
            "batchSize"           -> config.outboxBatchSize
          )
          val fiber = poller.start().start.unsafeRunSync()
          (Some(fiber), closePublisher)
        case None =>
          StructuredLog.warn("tournament-service", "outbox_poller_skipped",
            "reason" -> "TOURNAMENT_OUTBOX_PUBLISHER_ENABLED=true requires TOURNAMENT_JOB_STORE=postgres"
          )
          (None, IO.unit)
    else (None, IO.unit)

  private def buildPublisher(config: TournamentServiceConfig): (OutboxPublisher, IO[Unit]) =
    config.outboxPublisherType match
      case "kafka" =>
        val bootstrapServers = config.kafkaBootstrapServers.getOrElse(
          throw RuntimeException("TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS is required when TOURNAMENT_OUTBOX_PUBLISHER_TYPE=kafka")
        )
        StructuredLog.info(
          "tournament-service",
          "kafka_publisher_starting",
          "topic"            -> config.kafkaTopic,
          "bootstrapServers" -> bootstrapServers,
          "clientId"         -> config.kafkaClientId,
          "acks"             -> config.kafkaAcks
        )
        val sender    = JvmKafkaRecordSender.create(bootstrapServers, config.kafkaClientId, config.kafkaAcks)
        val publisher = KafkaOutboxPublisher(sender, config.kafkaTopic)
        (publisher, sender.close())
      case "noop"  => (NoOpOutboxPublisher, IO.unit)
      case _       => (LoggingOutboxPublisher, IO.unit)
