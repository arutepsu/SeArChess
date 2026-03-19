import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MySuite extends AnyFlatSpec with Matchers:
  "Example" should "pass a trivial sanity check" in {
    42 shouldBe 42
  }
