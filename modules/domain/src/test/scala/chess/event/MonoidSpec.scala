package chess.domain.event

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.model.Move

class MonoidSpec extends AnyFlatSpec with Matchers:

  import Monoid.given

  private val move = Move(
    chess.domain.model.Position.fromAlgebraic("e2").getOrElse(throw AssertionError("bad pos")),
    chess.domain.model.Position.fromAlgebraic("e4").getOrElse(throw AssertionError("bad pos"))
  )

  private val eventA: DomainEvent = DomainEvent.MoveApplied(move)
  private val eventB: DomainEvent =
    DomainEvent.GameStatusChanged(chess.domain.model.GameStatus.Ongoing(true))

  "Monoid[List[DomainEvent]]" should "have empty = Nil" in {
    summon[Monoid[List[DomainEvent]]].empty shouldBe Nil
  }

  it should "combine two lists by concatenation" in {
    val result = summon[Monoid[List[DomainEvent]]].combine(List(eventA), List(eventB))
    result shouldBe List(eventA, eventB)
  }

  it should "satisfy left-identity: empty |+| xs == xs" in {
    val xs: List[DomainEvent] = List(eventA, eventB)
    (summon[Monoid[List[DomainEvent]]].empty |+| xs) shouldBe xs
  }

  it should "satisfy right-identity: xs |+| empty == xs" in {
    val xs: List[DomainEvent] = List(eventA, eventB)
    (xs |+| summon[Monoid[List[DomainEvent]]].empty) shouldBe xs
  }

  it should "satisfy associativity: (a |+| b) |+| c == a |+| (b |+| c)" in {
    val a: List[DomainEvent] = List(eventA)
    val b: List[DomainEvent] = List(eventB)
    val c: List[DomainEvent] = List(eventA)
    ((a |+| b) |+| c) shouldBe (a |+| (b |+| c))
  }
