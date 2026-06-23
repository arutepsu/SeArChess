package chess.tournamentservice

import cats.effect.{FiberIO, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
import chess.observability.StructuredLog
import chess.tournamentservice.db.{InMemoryPublicImportRepository, InMemoryTournamentJobRepository, SlickOutboxEventRepository, SlickTournamentJobRepository}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import slick.jdbc.PostgresProfile.api.*

import java.io.File
import scala.concurrent.duration.*

object TournamentServiceWiring:

  def start(config: TournamentServiceConfig): TournamentServiceRuntime =
    val registry   = DefaultBotRegistry(config)
    val (repository, outboxRepoOpt) = buildRepositories(config)
    val analyticsRunner = buildAnalyticsRunner(config)
    val service    = TournamentJobService.create(registry, config, analyticsRunner, repository).unsafeRunSync()
    val worker     = service.startWorker().unsafeRunSync()
    val analyticsWorkers = service.startAnalyticsWorkers().unsafeRunSync()
    val importRepository = InMemoryPublicImportRepository.create().unsafeRunSync()
    val importClient  = JdkPublicTournamentClient()
    val importService = PublicImportService(repository, importRepository, importClient, config.outputBasePath)
    val recentGuard   = Some(RecentSubmissionGuard(config.publicRunnerRecentlySubmittedTtlSecs * 1000L))
    val runner        = buildRunner(config, registry, recentGuard)
    val httpApp    = (TournamentRoutes(service).routes <+> PublicImportRoutes(importService).routes <+> RunnerRoutes(runner).routes).orNotFound

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
    val schedulerFiber = buildScheduler(config, runner)

    TournamentServiceRuntime(shutdownHttp, worker, analyticsWorkers, outboxPollerFiber, schedulerFiber, closeResources)

  private[tournamentservice] def buildRunner(
      config:      TournamentServiceConfig,
      registry:    BotRegistry,
      recentGuard: Option[RecentSubmissionGuard]
  ): Option[PublicTournamentBotRunner] =
    (config.tournamentServerBaseUrl, config.userServiceBaseUrl, config.gatewayInternalBaseUrl) match
      case (Some(tsUrl), Some(usUrl), Some(gwUrl)) =>
        val serverClient      = JdkTournamentRunnerServerClient(tsUrl)
        val participantClient = JdkSearchessParticipantClient(usUrl)
        val moveClient        = JdkGatewayMoveClient(gwUrl, config.runnerServiceToken)
        Some(PublicTournamentBotRunner(registry, serverClient, participantClient, moveClient, recentGuard))
      case _ =>
        None

  private[tournamentservice] def buildScheduler(
      config:    TournamentServiceConfig,
      runnerOpt: Option[TournamentTickRunner]
  ): Option[FiberIO[Nothing]] =
    if !config.publicRunnerSchedulerEnabled then
      StructuredLog.info("tournament-service", "runner_scheduler_disabled")
      None
    else
      runnerOpt match
        case None =>
          StructuredLog.info("tournament-service", "runner_scheduler_skipped",
            "reason" -> "runner not configured (check TOURNAMENT_SERVER_BASE_URL, TOURNAMENT_USER_SERVICE_BASE_URL, TOURNAMENT_GATEWAY_INTERNAL_BASE_URL)"
          )
          None
        case Some(runner) =>
          config.tournamentServerBaseUrl match
            case None =>
              StructuredLog.info("tournament-service", "runner_scheduler_skipped",
                "reason" -> "TOURNAMENT_SERVER_BASE_URL not set"
              )
              None
            case Some(tsUrl) =>
              val discovery = JdkPublicTournamentDiscoveryClient(tsUrl)
              val scheduler = PublicTournamentRunnerScheduler(
                runner         = runner,
                discovery      = discovery,
                intervalSecs   = config.publicRunnerIntervalSeconds,
                maxTournaments = config.publicRunnerMaxTournamentsPerCycle
              )
              StructuredLog.info(
                "tournament-service", "runner_scheduler_starting",
                "intervalSeconds"        -> config.publicRunnerIntervalSeconds,
                "maxTournamentsPerCycle" -> config.publicRunnerMaxTournamentsPerCycle,
                "recentSubmitTtlSecs"    -> config.publicRunnerRecentlySubmittedTtlSecs
              )
              Some(scheduler.start().start.unsafeRunSync())

  private[tournamentservice] def buildAnalyticsRunner(config: TournamentServiceConfig): TournamentAnalyticsRunner =
    config.analyticsCommand match
      case Some(command) =>
        val workingDir = config.analyticsWorkingDir.map(File(_)).getOrElse(File(System.getProperty("user.dir")))
        PackagedSparkAnalyticsProcessRunner(command, workingDir)
      case None =>
        SparkTournamentAnalyticsProcessRunner(config.analyticsSbtCommand)

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
