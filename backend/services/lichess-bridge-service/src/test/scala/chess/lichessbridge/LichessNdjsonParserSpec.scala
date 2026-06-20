package chess.lichessbridge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LichessNdjsonParserSpec extends AnyFlatSpec with Matchers:

  // ── parseBotEventLine ─────────────────────────────────────────────────────────

  "parseBotEventLine" should "parse a challenge event" in {
    val line = """{"type":"challenge","challenge":{"id":"abc123","challenger":{"name":"opponent","rating":1500},"variant":{"key":"standard"},"speed":"blitz","rated":false,"timeControl":{"type":"clock","limit":300,"increment":0},"color":"random"}}"""
    parseBotEventLine(line) match
      case Right(LichessBotEvent.ChallengeCreated(c)) =>
        c.challengeId          shouldBe "abc123"
        c.challengerUsername   shouldBe "opponent"
        c.variant              shouldBe "standard"
        c.speed                shouldBe "blitz"
        c.rated                shouldBe false
        c.timeControl.limit    shouldBe Some(300)
        c.timeControl.increment shouldBe Some(0)
      case other => fail(s"Expected ChallengeCreated, got $other")
  }

  it should "parse a rated challenge" in {
    val line = """{"type":"challenge","challenge":{"id":"r1","challenger":{"name":"rater"},"variant":{"key":"standard"},"speed":"rapid","rated":true,"timeControl":{"type":"clock","limit":600,"increment":5}}}"""
    parseBotEventLine(line) match
      case Right(LichessBotEvent.ChallengeCreated(c)) =>
        c.rated               shouldBe true
        c.timeControl.limit   shouldBe Some(600)
        c.timeControl.increment shouldBe Some(5)
      case other => fail(s"Expected ChallengeCreated, got $other")
  }

  it should "parse a challengeCanceled event" in {
    val line = """{"type":"challengeCanceled","challenge":{"id":"abc123"}}"""
    parseBotEventLine(line) shouldBe Right(LichessBotEvent.ChallengeCanceled("abc123"))
  }

  it should "parse a gameStart event" in {
    val line = """{"type":"gameStart","game":{"gameId":"g1","fullId":"g1abc","color":"white","opponent":{"username":"human1"}}}"""
    parseBotEventLine(line) match
      case Right(LichessBotEvent.GameStart(g)) =>
        g.gameId   shouldBe "g1"
        g.opponent shouldBe "human1"
        g.side     shouldBe Some("white")
        g.url      shouldBe "https://lichess.org/g1abc"
      case other => fail(s"Expected GameStart, got $other")
  }

  it should "parse a gameFinish event" in {
    val line = """{"type":"gameFinish","game":{"gameId":"g1","fullId":"g1abc","color":"black","opponent":{"username":"human1"}}}"""
    parseBotEventLine(line) match
      case Right(LichessBotEvent.GameFinish(g)) =>
        g.gameId shouldBe "g1"
        g.side   shouldBe Some("black")
      case other => fail(s"Expected GameFinish, got $other")
  }

  it should "produce Unknown for an unrecognised event type without crashing" in {
    val line = """{"type":"futureTournamentEvent","data":{}}"""
    parseBotEventLine(line) match
      case Right(LichessBotEvent.Unknown(t, _)) => t shouldBe "futureTournamentEvent"
      case other                                => fail(s"Expected Unknown, got $other")
  }

  it should "produce Unknown for a keep-alive blank line" in {
    parseBotEventLine("") match
      case Right(LichessBotEvent.Unknown("keep-alive", _)) => succeed
      case other                                           => fail(s"Expected keep-alive Unknown, got $other")
  }

  it should "produce Unknown for a whitespace-only line" in {
    parseBotEventLine("   ") match
      case Right(LichessBotEvent.Unknown("keep-alive", _)) => succeed
      case other                                           => fail(s"Expected keep-alive Unknown, got $other")
  }

  it should "return Left(ParseError) for malformed JSON" in {
    parseBotEventLine("{not valid json}") match
      case Left(ParseError(msg, raw)) =>
        msg should not be empty
        raw should include ("not valid")
      case other => fail(s"Expected ParseError, got $other")
  }

  it should "return Left(ParseError) for valid JSON missing required fields" in {
    // Challenge event with no challenger.name
    val line = """{"type":"challenge","challenge":{"id":"x"}}"""
    parseBotEventLine(line).isLeft shouldBe true
  }

  it should "default variant to standard when absent" in {
    val line = """{"type":"challenge","challenge":{"id":"x","challenger":{"name":"p"},"speed":"blitz","rated":false,"timeControl":{"type":"clock","limit":180,"increment":0}}}"""
    parseBotEventLine(line) match
      case Right(LichessBotEvent.ChallengeCreated(c)) => c.variant shouldBe "standard"
      case other                                      => fail(s"Unexpected $other")
  }

  // ── parseGameEventLine ────────────────────────────────────────────────────────

  "parseGameEventLine" should "parse a gameFull event" in {
    val line = """{"type":"gameFull","id":"g1","white":{"name":"Bot"},"black":{"name":"Human"},"initialFen":"startpos","state":{"type":"gameState","moves":"e2e4","wtime":300000,"btime":300000,"status":"started"}}"""
    parseGameEventLine(line) match
      case Right(LichessGameEvent.GameFull(id, white, black, fen, moves, wtime, btime, status)) =>
        id     shouldBe "g1"
        white  shouldBe "Bot"
        black  shouldBe "Human"
        fen    shouldBe "startpos"
        moves  shouldBe "e2e4"
        wtime  shouldBe 300000
        btime  shouldBe 300000
        status shouldBe "started"
      case other => fail(s"Expected GameFull, got $other")
  }

  it should "parse a gameState event" in {
    val line = """{"type":"gameState","moves":"e2e4 e7e5","wtime":295000,"btime":300000,"status":"started"}"""
    parseGameEventLine(line) match
      case Right(LichessGameEvent.GameState(moves, wtime, btime, status)) =>
        moves  shouldBe "e2e4 e7e5"
        wtime  shouldBe 295000
        btime  shouldBe 300000
        status shouldBe "started"
      case other => fail(s"Expected GameState, got $other")
  }

  it should "parse a chatLine event" in {
    val line = """{"type":"chatLine","username":"spectator","text":"gg","room":"spectator"}"""
    parseGameEventLine(line) match
      case Right(LichessGameEvent.ChatLine(username, text)) =>
        username shouldBe "spectator"
        text     shouldBe "gg"
      case other => fail(s"Expected ChatLine, got $other")
  }

  it should "parse an opponentGone event" in {
    val line = """{"type":"opponentGone","gone":true,"claimWinInSeconds":30}"""
    parseGameEventLine(line) match
      case Right(LichessGameEvent.OpponentGone(gone, claimIn)) =>
        gone    shouldBe true
        claimIn shouldBe Some(30)
      case other => fail(s"Expected OpponentGone, got $other")
  }

  it should "produce Unknown for an unrecognised game event type" in {
    val line = """{"type":"newUnknownType","data":"something"}"""
    parseGameEventLine(line) match
      case Right(LichessGameEvent.Unknown(t, _)) => t shouldBe "newUnknownType"
      case other                                 => fail(s"Expected Unknown, got $other")
  }

  it should "produce keep-alive Unknown for blank lines" in {
    parseGameEventLine("") match
      case Right(LichessGameEvent.Unknown("keep-alive", _)) => succeed
      case other                                            => fail(s"Expected keep-alive, got $other")
  }

  it should "return Left(ParseError) for malformed JSON" in {
    parseGameEventLine("{broken") match
      case Left(_) => succeed
      case other   => fail(s"Expected ParseError, got $other")
  }

  // ── helpers ───────────────────────────────────────────────────────────────────

  private def parseBotEventLine(line: String)  = LichessNdjsonParser.parseBotEventLine(line)
  private def parseGameEventLine(line: String) = LichessNdjsonParser.parseGameEventLine(line)
