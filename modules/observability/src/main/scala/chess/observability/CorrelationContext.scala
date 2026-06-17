package chess.observability

object CorrelationContext:
  private val currentId = ThreadLocal[String]()

  def current: Option[String] =
    Option(currentId.get()).filter(_.nonEmpty)

  def push(correlationId: String): Option[String] =
    val previous = currentId.get()
    currentId.set(correlationId)
    Option(previous)

  def restore(previous: Option[String]): Unit =
    previous match
      case Some(value) => currentId.set(value)
      case None        => currentId.remove()
