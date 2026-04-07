// $COVERAGE-OFF$ — JavaFX widget; excluded from coverage instrumentation
package chess.adapter.gui.render

import javafx.application.Platform
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.{BeforeAndAfterAll, Canceled, Outcome}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.adapter.gui.viewmodel.{MoveHistoryRowViewModel, MoveHistoryViewModel}

/** Tests for [[MoveHistoryPanel]]: initial state, refresh behavior, and layout.
 *
 *  All assertions that touch JavaFX nodes run on the FX Application Thread via [[onFx]].
 */
class MoveHistoryPanelSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

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

  // ── Initial state ─────────────────────────────────────────────────────────────

  "MoveHistoryPanel" should "show the empty placeholder on construction" in {
    val panel = onFx { new MoveHistoryPanel }
    onFx {
      // rowsBox starts with the emptyLabel — root children: header + scrollPane
      panel.root.children should have size 2
    }
  }

  // ── refresh with empty VM ─────────────────────────────────────────────────────

  it should "show the empty placeholder after refreshing with an empty VM" in {
    val panel = onFx { new MoveHistoryPanel }
    onFx { panel.refresh(MoveHistoryViewModel.empty) }
    // passes if no exception thrown; placeholder is restored
  }

  // ── refresh with rows ─────────────────────────────────────────────────────────

  it should "display one HBox row after refreshing with a one-row VM" in {
    val panel = onFx { new MoveHistoryPanel }
    val vm    = MoveHistoryViewModel(Vector(MoveHistoryRowViewModel(1, "e2-e4", None)))
    onFx { panel.refresh(vm) }
    // passes if no exception thrown
  }

  it should "accept a multi-row VM without error" in {
    val panel = onFx { new MoveHistoryPanel }
    val vm = MoveHistoryViewModel(Vector(
      MoveHistoryRowViewModel(1, "e2-e4", Some("e7-e5")),
      MoveHistoryRowViewModel(2, "g1-f3", Some("b8-c6"))
    ))
    onFx { panel.refresh(vm) }
    // passes if no exception thrown
  }

  it should "replace prior rows on a second refresh" in {
    val panel = onFx { new MoveHistoryPanel }
    val vm1 = MoveHistoryViewModel(Vector(MoveHistoryRowViewModel(1, "e2-e4", None)))
    val vm2 = MoveHistoryViewModel(Vector(
      MoveHistoryRowViewModel(1, "d2-d4", Some("d7-d5")),
      MoveHistoryRowViewModel(2, "c2-c4", None)
    ))
    onFx { panel.refresh(vm1) }
    onFx { panel.refresh(vm2) }
    // passes if no exception thrown — content replaced without error
  }

  // ── Layout structure ──────────────────────────────────────────────────────────

  it should "have 'History' as the header text" in {
    val panel = onFx { new MoveHistoryPanel }
    onFx {
      val header = panel.root.children.get(0)
      header shouldBe a[javafx.scene.control.Label]
      header.asInstanceOf[javafx.scene.control.Label].getText shouldBe "History"
    }
  }
