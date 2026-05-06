package chess.workbench.review

object ReviewCommand:
  def execute(
      args: List[String],
      printLine: String => Unit
  ): ReviewReport =
    val context = contextFrom(args)
    val report = StubAIReviewService().review(context)
    ReviewFormatter.format(report).foreach(printLine)
    report

  def contextFrom(args: List[String]): ReviewContext =
    val target = args match
      case "review" :: rest => rest
      case other            => other

    val moduleName = target match
      case "game" :: _  => "game"
      case "tests" :: _ => "tests"
      case _            => "project"

    val question = target match
      case Nil => "Review the project."
      case _   => s"Review ${target.mkString(" ")}."

    ReviewContext(
      moduleName = moduleName,
      userQuestion = question,
      notes = Vector("Step 1 stub review; no file scanning or AI calls.")
    )
