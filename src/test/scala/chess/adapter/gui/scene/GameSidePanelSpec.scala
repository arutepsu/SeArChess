// $COVERAGE-OFF$ — JavaFX widget; excluded from coverage instrumentation
package chess.adapter.gui.scene

import javafx.application.Platform
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.{BeforeAndAfterAll, Canceled, Outcome}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.adapter.gui.notation.{GuiNotationApi, NotationSidebar, NotationSidebarController}
import chess.adapter.gui.render.MoveHistoryPanel
import chess.adapter.gui.viewmodel.{MoveHistoryRowViewModel, MoveHistoryViewModel}
import chess.application.ChessService

/** Tests for [[GameSidePanel]]: layout composition and history refresh.
 *
 *  Validates:
 *  - the panel hosts both notation and history sections
 *  - [[GameSidePanel.refreshHistory]] drives history updates, not direct controller coupling
 *  - notation sidebar remains accessible within the panel
 */
class GameSidePanelSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private var displayAvailable = true

  override def beforeAll(): Unit =
    val latch = new CountDownLatch(1)
    try
      try Platform.startup(() => latch.countDown())
      catch case _: IllegalStateException => latch.countDown()
      assert(latch.await(10, TimeUnit.SECONDS), "JavaFX Platform failed to start")
    catch
      case _: UnsupportedOperationException =>
        displayAvailable = false

  override def withFixture(test: NoArgTest): Outcome =
    if !displayAvailable then Canceled("No display available; JavaFX tests skipped")
    else super.withFixture(test)

  private def onFx[A](body: => A): A =
    @volatile var result: Option[A] = None
    val latch = new CountDownLatch(1)
    Platform.runLater { () =>
      result = Some(body)
      latch.countDown()
    }
    assert(latch.await(5, TimeUnit.SECONDS), "JavaFX runLater timed out")
    result.get

  private val freshState = ChessService.createNewGame()

  private def makePanel(): GameSidePanel =
    val controller = new NotationSidebarController(
      api             = GuiNotationApi.default,
      stateProvider   = () => freshState,
      onImportedState = _ => (),
      onRefresh       = _ => ()
    )
    val sidebar      = new NotationSidebar(controller)
    val historyPanel = new MoveHistoryPanel
    new GameSidePanel(sidebar, historyPanel)

  // ── Root layout ───────────────────────────────────────────────────────────────

  "GameSidePanel" should "have prefWidth 220" in {
    val panel = onFx { makePanel() }
    onFx { panel.root.prefWidth.value shouldBe 220.0 }
  }

  it should "apply the dark background style" in {
    val panel = onFx { makePanel() }
    onFx { panel.root.style.value should include("2a2623") }
  }

  it should "contain three children: notation section, separator, history section" in {
    val panel = onFx { makePanel() }
    onFx { panel.root.children should have size 3 }
  }

  // ── History refresh ───────────────────────────────────────────────────────────

  it should "accept a history VM push without error when history is empty" in {
    val panel = onFx { makePanel() }
    onFx { panel.refreshHistory(MoveHistoryViewModel.empty) }
  }

  it should "accept a non-empty history VM push without error" in {
    val panel = onFx { makePanel() }
    val vm    = MoveHistoryViewModel(Vector(MoveHistoryRowViewModel(1, "e2-e4", Some("e7-e5"))))
    onFx { panel.refreshHistory(vm) }
  }

  // ── Structure ─────────────────────────────────────────────────────────────────

  it should "keep the notation sidebar root as the first child" in {
    val panel = onFx { makePanel() }
    onFx {
      panel.root.children.get(0) shouldBe a[javafx.scene.layout.VBox]
    }
  }

  it should "keep the history panel root as the third child" in {
    val panel = onFx { makePanel() }
    onFx {
      panel.root.children.get(2) shouldBe a[javafx.scene.layout.VBox]
    }
  }
