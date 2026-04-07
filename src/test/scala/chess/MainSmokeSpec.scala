package chess

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class MainSmokeSpec extends AnyFlatSpec with Matchers {
  "Main.main" should "start without throwing (smoke test, 2s timeout)" in {
    val f = Future {
      Main.main(Array.empty)
    }
    intercept[TimeoutException] {
      Await.result(f, 2.seconds)
    }
    // If TimeoutException is thrown, that's expected (means it didn't crash immediately)
  }
}
