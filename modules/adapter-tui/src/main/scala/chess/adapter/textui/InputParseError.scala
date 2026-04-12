package chess.adapter.textui

enum InputParseError:
  case EmptyInput
  case UnknownCommand(input: String)
  case WrongArgumentCount(command: String)
  case InvalidPromotionToken(token: String)
