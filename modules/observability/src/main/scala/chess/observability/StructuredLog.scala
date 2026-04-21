package chess.observability

import java.io.PrintStream
import java.time.Instant

/** Tiny JSON-lines logger for service-boundary diagnostics.
<<<<<<< HEAD
  *
  * This is intentionally dependency-free and deliberately small. It gives the services stable,
  * machine-readable log fields now, while leaving room to swap in a real logging/telemetry backend
  * later.
  */
=======
 *
 *  This is intentionally dependency-free and deliberately small. It gives the
 *  services stable, machine-readable log fields now, while leaving room to swap
 *  in a real logging/telemetry backend later.
 */
>>>>>>> ce08c01e (local microservices)
object StructuredLog:

  def info(service: String, event: String, fields: (String, Any)*): Unit =
    write(Console.out, "info", service, event, fields*)

  def warn(service: String, event: String, fields: (String, Any)*): Unit =
    write(Console.err, "warn", service, event, fields*)

  def error(service: String, event: String, fields: (String, Any)*): Unit =
    write(Console.err, "error", service, event, fields*)

  private def write(
<<<<<<< HEAD
      stream: PrintStream,
      level: String,
      service: String,
      event: String,
      fields: (String, Any)*
  ): Unit =
    val allFields =
      Seq(
        "ts" -> Instant.now().toString,
        "level" -> level,
        "service" -> service,
        "event" -> event
      ) ++ fields

    stream.println(
      allFields
        .map { case (key, value) => quote(key) + ":" + render(value) }
        .mkString("{", ",", "}")
    )

  private def render(value: Any): String = value match
    case null        => "null"
    case None        => "null"
    case Some(v)     => render(v)
    case s: String   => quote(s)
    case b: Boolean  => b.toString
    case n: Byte     => n.toString
    case n: Short    => n.toString
    case n: Int      => n.toString
    case n: Long     => n.toString
    case n: Float    => renderDecimal(n.toDouble)
    case n: Double   => renderDecimal(n)
    case i: Instant  => quote(i.toString)
    case seq: Seq[?] => seq.map(render).mkString("[", ",", "]")
    case other       => quote(other.toString)
=======
    stream:  PrintStream,
    level:   String,
    service: String,
    event:   String,
    fields:  (String, Any)*
  ): Unit =
    val allFields =
      Seq(
        "ts"      -> Instant.now().toString,
        "level"   -> level,
        "service" -> service,
        "event"   -> event
      ) ++ fields

    stream.println(allFields.map { case (key, value) => quote(key) + ":" + render(value) }.mkString("{", ",", "}"))

  private def render(value: Any): String = value match
    case null          => "null"
    case None          => "null"
    case Some(v)       => render(v)
    case s: String     => quote(s)
    case b: Boolean    => b.toString
    case n: Byte       => n.toString
    case n: Short      => n.toString
    case n: Int        => n.toString
    case n: Long       => n.toString
    case n: Float      => renderDecimal(n.toDouble)
    case n: Double     => renderDecimal(n)
    case i: Instant    => quote(i.toString)
    case seq: Seq[?]   => seq.map(render).mkString("[", ",", "]")
    case other         => quote(other.toString)
>>>>>>> ce08c01e (local microservices)

  private def renderDecimal(value: Double): String =
    if value.isNaN || value.isInfinity then quote(value.toString)
    else value.toString

  private def quote(value: String): String =
    "\"" + value.flatMap {
<<<<<<< HEAD
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\b'         => "\\b"
      case '\f'         => "\\f"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
=======
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
>>>>>>> ce08c01e (local microservices)
    } + "\""
