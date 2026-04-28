package chess.application.migration

import chess.adapter.migration.InMemorySessionMigrationReader
import chess.application.port.repository.{
  GameRepository,
  RepositoryError,
  SessionGameStore,
  SessionRepository
}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import scala.collection.mutable

class PersistenceMigrationServiceSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues:

  private val fixedNow = Instant.parse("2026-04-26T12:00:00Z")
  private val service = new PersistenceMigrationService(() => fixedNow)

  "PersistenceMigrationService" should "report WouldMigrate in DryRun when target is empty" in {
    val fixture = freshFixture()

    val report = service.run(fixture.source, fixture.target, MigrationMode.DryRun, batchSize = 10)

    report.sourceAdapterName shouldBe "source-in-memory"
    report.targetAdapterName shouldBe "target-in-memory"
    report.mode shouldBe MigrationMode.DryRun
    report.startedAt shouldBe fixedNow
    report.finishedAt shouldBe fixedNow
    report.duration shouldBe java.time.Duration.ZERO
    report.batchSize shouldBe 10
    report.scannedCount shouldBe 1
    report.itemResults shouldBe List(
      MigrationItemResult.WouldMigrate(fixture.sourceSession.sessionId, fixture.sourceSession.gameId)
    )
    report.wouldMigrateCount shouldBe 1
    report.migratedCount shouldBe 0
    report.skippedEquivalentCount shouldBe 0
    report.conflictCount shouldBe 0
    report.failedCount shouldBe 0
    report.validationRan shouldBe false
    report.validationResult shouldBe None
    report.finalStatus shouldBe MigrationFinalStatus.Success
  }

  it should "not write anything in DryRun" in {
    val fixture = freshFixture()

    service.run(fixture.source, fixture.target, MigrationMode.DryRun, batchSize = 10)

    fixture.targetSessionRepository.load(fixture.sourceSession.sessionId).left.value shouldBe
      RepositoryError.NotFound(fixture.sourceSession.sessionId.value.toString)
    fixture.targetGameRepository.load(fixture.sourceSession.gameId).left.value shouldBe
      RepositoryError.NotFound(fixture.sourceSession.gameId.value.toString)
  }

  it should "report SkippedEquivalent in DryRun when target already matches source" in {
    val fixture = freshFixture(seedTargetEquivalent = true)

    val report = service.run(fixture.source, fixture.target, MigrationMode.DryRun, batchSize = 10)

    report.itemResults shouldBe List(
      MigrationItemResult.SkippedEquivalent(
        fixture.sourceSession.sessionId,
        fixture.sourceSession.gameId
      )
    )
  }

  it should "report Conflict in DryRun when target differs or is partial" in {
    val differentFixture = freshFixture()
    differentFixture.targetStore
      .save(differentFixture.sourceSession, differentFixture.differentState)
      .value

    val differentReport =
      service.run(
        differentFixture.source,
        differentFixture.target,
        MigrationMode.DryRun,
        batchSize = 10
      )
    differentReport.itemResults.head shouldBe a[MigrationItemResult.Conflict]
    differentReport.conflictCount shouldBe 1
    differentReport.failedCount shouldBe 0
    differentReport.finalStatus shouldBe MigrationFinalStatus.CompletedWithConflicts

    val partialFixture = freshFixture()
    partialFixture.targetSessionRepository.save(partialFixture.sourceSession).value

    val partialReport =
      service.run(partialFixture.source, partialFixture.target, MigrationMode.DryRun, batchSize = 10)
    partialReport.itemResults.head shouldBe a[MigrationItemResult.Conflict]
    partialReport.finalStatus shouldBe MigrationFinalStatus.CompletedWithConflicts
  }

  it should "migrate a missing aggregate in Execute" in {
    val fixture = freshFixture()

    val report = service.run(fixture.source, fixture.target, MigrationMode.Execute, batchSize = 10)

    report.itemResults shouldBe List(
      MigrationItemResult.Migrated(fixture.sourceSession.sessionId, fixture.sourceSession.gameId)
    )
    report.scannedCount shouldBe 1
    report.migratedCount shouldBe 1
    report.skippedEquivalentCount shouldBe 0
    report.conflictCount shouldBe 0
    report.failedCount shouldBe 0
    report.validationRan shouldBe false
    report.validationResult shouldBe None
    report.finalStatus shouldBe MigrationFinalStatus.Success
    fixture.targetSessionRepository.load(fixture.sourceSession.sessionId).value shouldBe
      fixture.sourceSession
    fixture.targetGameRepository.load(fixture.sourceSession.gameId).value shouldBe fixture.sourceState
  }

  it should "skip an equivalent aggregate in Execute" in {
    val fixture = freshFixture(seedTargetEquivalent = true)

    val report = service.run(fixture.source, fixture.target, MigrationMode.Execute, batchSize = 10)

    report.itemResults shouldBe List(
      MigrationItemResult.SkippedEquivalent(
        fixture.sourceSession.sessionId,
        fixture.sourceSession.gameId
      )
    )
  }

  it should "report Conflict in Execute when target differs" in {
    val fixture = freshFixture()
    fixture.targetStore.save(fixture.sourceSession, fixture.differentState).value

    val report = service.run(fixture.source, fixture.target, MigrationMode.Execute, batchSize = 10)

    report.itemResults.head shouldBe a[MigrationItemResult.Conflict]
    fixture.targetGameRepository.load(fixture.sourceSession.gameId).value shouldBe fixture.differentState
  }

  it should "be rerunnable in Execute" in {
    val fixture = freshFixture()

    val first = service.run(fixture.source, fixture.target, MigrationMode.Execute, batchSize = 10)
    val second = service.run(fixture.source, fixture.target, MigrationMode.Execute, batchSize = 10)

    first.itemResults.head shouldBe a[MigrationItemResult.Migrated]
    second.itemResults.head shouldBe a[MigrationItemResult.SkippedEquivalent]
  }

  it should "validate an equivalent target in ValidateOnly" in {
    val fixture = freshFixture(seedTargetEquivalent = true)

    val report =
      service.run(fixture.source, fixture.target, MigrationMode.ValidateOnly, batchSize = 10)

    report.itemResults shouldBe List(
      MigrationItemResult.ValidatedEquivalent(
        fixture.sourceSession.sessionId,
        fixture.sourceSession.gameId
      )
    )
    report.scannedCount shouldBe 1
    report.validatedEquivalentCount shouldBe 1
    report.validationMismatchCount shouldBe 0
    report.failedCount shouldBe 0
    report.validationRan shouldBe true
    report.validationResult shouldBe Some(MigrationValidationResult.Passed)
    report.finalStatus shouldBe MigrationFinalStatus.Success
  }

  it should "report mismatch in ValidateOnly for missing different and partial target data" in {
    val missingFixture = freshFixture()
    val missingReport =
      service.run(missingFixture.source, missingFixture.target, MigrationMode.ValidateOnly, 10)
    missingReport.itemResults.head shouldBe a[MigrationItemResult.ValidationMismatch]
    missingReport.validationMismatchCount shouldBe 1
    missingReport.validationResult shouldBe Some(MigrationValidationResult.Failed)
    missingReport.finalStatus shouldBe MigrationFinalStatus.CompletedWithConflicts

    val differentFixture = freshFixture()
    differentFixture.targetStore
      .save(differentFixture.sourceSession, differentFixture.differentState)
      .value
    val differentReport =
      service.run(differentFixture.source, differentFixture.target, MigrationMode.ValidateOnly, 10)
    differentReport.itemResults.head shouldBe a[MigrationItemResult.ValidationMismatch]
    differentReport.validationResult shouldBe Some(MigrationValidationResult.Failed)
    differentReport.finalStatus shouldBe MigrationFinalStatus.CompletedWithConflicts

    val partialFixture = freshFixture()
    partialFixture.targetGameRepository.save(partialFixture.sourceSession.gameId, partialFixture.sourceState).value
    val partialReport =
      service.run(partialFixture.source, partialFixture.target, MigrationMode.ValidateOnly, 10)
    partialReport.itemResults.head shouldBe a[MigrationItemResult.ValidationMismatch]
    partialReport.validationResult shouldBe Some(MigrationValidationResult.Failed)
    partialReport.finalStatus shouldBe MigrationFinalStatus.CompletedWithConflicts
  }

  it should "report SourceGameStateMissing when source game state is absent" in {
    val sourceSessionRepo = new InMemorySessionRepository()
    val sourceGameRepo = new InMemoryGameRepository()
    val sourceSession = baseSession()
    sourceSessionRepo.save(sourceSession).value

    val targetSessionRepo = new InMemorySessionRepository()
    val targetGameRepo = new InMemoryGameRepository()
    val targetStore = new InMemorySessionGameStore(targetSessionRepo, targetGameRepo)

    val report = service.run(
      source = MigrationSourceAdapter(
        name = "source",
        sessionReader = InMemorySessionMigrationReader(List(sourceSession)),
        gameRepository = sourceGameRepo
      ),
      target = MigrationTargetAdapter(
        name = "target",
        sessionRepository = targetSessionRepo,
        gameRepository = targetGameRepo,
        store = targetStore
      ),
      mode = MigrationMode.Execute,
      batchSize = 10
    )

    report.itemResults shouldBe List(
      MigrationItemResult.SourceGameStateMissing(sourceSession.sessionId, sourceSession.gameId)
    )
  }

  it should "report TargetWriteFailed when the target store write fails" in {
    val fixture = freshFixture()
    val failingStore = new FailingSessionGameStore()
    val target = fixture.target.copy(store = failingStore)

    val report = service.run(fixture.source, target, MigrationMode.Execute, batchSize = 10)

    report.itemResults.head shouldBe a[MigrationItemResult.TargetWriteFailed]
    report.failedCount shouldBe 1
    report.finalStatus shouldBe MigrationFinalStatus.Failed
    fixture.targetSessionRepository.load(fixture.sourceSession.sessionId).left.value shouldBe
      RepositoryError.NotFound(fixture.sourceSession.sessionId.value.toString)
  }

  it should "stop the run with fatalFailure when the reader fails" in {
    val fixture = freshFixture()
    val failingReader = new SessionMigrationReader:
      override def readBatch(
          cursor: Option[SessionMigrationCursor],
          batchSize: Int
      ): Either[RepositoryError, SessionMigrationBatch] =
        Left(RepositoryError.StorageFailure("reader boom"))

    val report = service.run(
      fixture.source.copy(sessionReader = failingReader),
      fixture.target,
      MigrationMode.Execute,
      batchSize = 10
    )

    report.itemResults shouldBe Nil
    report.fatalFailure shouldBe Some(MigrationRunFailure.ReaderFailure("reader boom"))
    report.failedCount shouldBe 1
    report.finalStatus shouldBe MigrationFinalStatus.Failed
  }

  it should "derive summary counters from itemResults" in {
    val sessions = List(
      baseSession(
        sessionId = "00000000-0000-0000-0000-000000000011",
        gameId = "10000000-0000-0000-0000-000000000011"
      ),
      baseSession(
        sessionId = "00000000-0000-0000-0000-000000000012",
        gameId = "10000000-0000-0000-0000-000000000012"
      ),
      baseSession(
        sessionId = "00000000-0000-0000-0000-000000000013",
        gameId = "10000000-0000-0000-0000-000000000013"
      ),
      baseSession(
        sessionId = "00000000-0000-0000-0000-000000000014",
        gameId = "10000000-0000-0000-0000-000000000014"
      )
    )

    val sourceGameRepo = new InMemoryGameRepository()
    sourceGameRepo.save(sessions(0).gameId, richState()).value
    sourceGameRepo.save(sessions(1).gameId, richState(fullmoveNumber = 20)).value
    sourceGameRepo.save(sessions(2).gameId, richState(fullmoveNumber = 30)).value

    val targetSessionRepo = new InMemorySessionRepository()
    val targetGameRepo = new InMemoryGameRepository()
    val targetStore = new InMemorySessionGameStore(targetSessionRepo, targetGameRepo)
    targetStore.save(sessions(1), richState(fullmoveNumber = 20)).value
    targetStore.save(sessions(2), richState(fullmoveNumber = 99)).value

    val report = service.run(
      source = MigrationSourceAdapter(
        name = "source",
        sessionReader = InMemorySessionMigrationReader(sessions),
        gameRepository = sourceGameRepo
      ),
      target = MigrationTargetAdapter(
        name = "target",
        sessionRepository = targetSessionRepo,
        gameRepository = targetGameRepo,
        store = targetStore
      ),
      mode = MigrationMode.Execute,
      batchSize = 2
    )

    report.sourceSessionCount shouldBe 4
    report.sourceGameLoadCount shouldBe 4
    report.migratedCount shouldBe 1
    report.skippedEquivalentCount shouldBe 1
    report.conflictCount shouldBe 1
    report.sourceDataMissingCount shouldBe 1
    report.writeFailureCount shouldBe 0
    report.readFailureCount shouldBe 0
    report.storageFailureCount shouldBe 0
  }

  private final case class Fixture(
      source: MigrationSourceAdapter,
      target: MigrationTargetAdapter,
      sourceSession: GameSession,
      sourceState: GameState,
      differentState: GameState,
      targetSessionRepository: SessionRepository,
      targetGameRepository: GameRepository,
      targetStore: SessionGameStore
  )

  private def freshFixture(seedTargetEquivalent: Boolean = false): Fixture =
    val sourceSession = baseSession()
    val sourceState = richState()
    val differentState = richState(fullmoveNumber = 42)

    val sourceGameRepository = new InMemoryGameRepository()
    sourceGameRepository.save(sourceSession.gameId, sourceState).value

    val targetSessionRepository = new InMemorySessionRepository()
    val targetGameRepository = new InMemoryGameRepository()
    val targetStore = new InMemorySessionGameStore(targetSessionRepository, targetGameRepository)

    if seedTargetEquivalent then
      targetStore.save(sourceSession, sourceState).value

    Fixture(
      source = MigrationSourceAdapter(
        name = "source-in-memory",
        sessionReader = InMemorySessionMigrationReader(List(sourceSession)),
        gameRepository = sourceGameRepository
      ),
      target = MigrationTargetAdapter(
        name = "target-in-memory",
        sessionRepository = targetSessionRepository,
        gameRepository = targetGameRepository,
        store = targetStore
      ),
      sourceSession = sourceSession,
      sourceState = sourceState,
      differentState = differentState,
      targetSessionRepository = targetSessionRepository,
      targetGameRepository = targetGameRepository,
      targetStore = targetStore
    )

  private def baseSession(
      sessionId: String = "00000000-0000-0000-0000-000000000001",
      gameId: String = "10000000-0000-0000-0000-000000000001"
  ): GameSession =
    GameSession(
      sessionId = SessionId(UUID.fromString(sessionId)),
      gameId = GameId(UUID.fromString(gameId)),
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal,
      lifecycle = SessionLifecycle.Active,
      createdAt = Instant.parse("2024-01-01T00:00:00Z"),
      updatedAt = Instant.parse("2024-01-01T00:05:00Z")
    )

  private def richState(fullmoveNumber: Int = 12): GameState =
    GameState(
      board = Board.empty
        .place(pos("e4"), Piece(Color.White, PieceType.Pawn))
        .place(pos("e8"), Piece(Color.Black, PieceType.King))
        .place(pos("g1"), Piece(Color.White, PieceType.King)),
      currentPlayer = Color.Black,
      moveHistory = List(Move(pos("e2"), pos("e4"))),
      status = GameStatus.Ongoing(inCheck = true),
      castlingRights = CastlingRights(
        whiteKingSide = false,
        whiteQueenSide = true,
        blackKingSide = true,
        blackQueenSide = false
      ),
      enPassantState = Some(EnPassantState(pos("e3"), pos("e4"), Color.White)),
      halfmoveClock = 7,
      fullmoveNumber = fullmoveNumber
    )

  private def pos(algebraic: String): Position =
    Position.fromAlgebraic(algebraic).value

  private final class FailingSessionGameStore extends SessionGameStore:
    override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
      Left(RepositoryError.StorageFailure("target write boom"))

  private final class InMemorySessionRepository extends SessionRepository:
    private val bySessionId = mutable.HashMap.empty[SessionId, GameSession]
    private val sessionIdByGameId = mutable.HashMap.empty[GameId, SessionId]

    override def save(session: GameSession): Either[RepositoryError, Unit] =
      sessionIdByGameId.get(session.gameId) match
        case Some(existingSessionId) if existingSessionId != session.sessionId =>
          Left(
            RepositoryError.Conflict(
              s"GameId ${session.gameId.value} is already owned by session $existingSessionId"
            )
          )
        case _ =>
          bySessionId.get(session.sessionId).foreach { previous =>
            if previous.gameId != session.gameId then sessionIdByGameId.remove(previous.gameId)
          }
          bySessionId.put(session.sessionId, session)
          sessionIdByGameId.put(session.gameId, session.sessionId)
          Right(())

    override def load(id: SessionId): Either[RepositoryError, GameSession] =
      bySessionId.get(id).toRight(RepositoryError.NotFound(id.value.toString))

    override def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
      sessionIdByGameId
        .get(id)
        .flatMap(bySessionId.get)
        .toRight(RepositoryError.NotFound(id.value.toString))

    override def listActive(): Either[RepositoryError, List[GameSession]] =
      Right(bySessionId.values.toList)

  private final class InMemoryGameRepository extends GameRepository:
    private val store = mutable.HashMap.empty[GameId, GameState]

    override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
      store.put(gameId, state)
      Right(())

    override def load(gameId: GameId): Either[RepositoryError, GameState] =
      store.get(gameId).toRight(RepositoryError.NotFound(gameId.value.toString))

  private final class InMemorySessionGameStore(
      sessionRepository: InMemorySessionRepository,
      gameRepository: InMemoryGameRepository
  ) extends SessionGameStore:
    override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
      for
        _ <- sessionRepository.save(session)
        _ <- gameRepository.save(session.gameId, state)
      yield ()
