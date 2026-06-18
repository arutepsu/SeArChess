package chess.userservice.application

import java.util.regex.Pattern

object NicknameValidator:

  private val allowed = Pattern.compile("^[A-Za-z0-9_-]{3,24}$")

  def validate(raw: String): Either[String, String] =
    val trimmed = raw.trim
    if trimmed.length < 3 then Left("Nickname must be at least 3 characters")
    else if trimmed.length > 24 then Left("Nickname must be at most 24 characters")
    else if !allowed.matcher(trimmed).matches() then
      Left("Nickname may only contain letters, digits, underscores (_), and hyphens (-)")
    else Right(trimmed)
