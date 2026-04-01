package chess.domain.event

/** Minimal monoid abstraction — no external library dependency. */
trait Monoid[A]:
  def empty: A
  def combine(a: A, b: A): A

object Monoid:
  given listDomainEvent: Monoid[List[DomainEvent]] with
    def empty                                        = Nil
    def combine(a: List[DomainEvent], b: List[DomainEvent]) = a ++ b

extension [A](a: A)(using m: Monoid[A])
  def |+|(b: A): A = m.combine(a, b)
