import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayOutputStream

class MainSpec extends AnyFlatSpec with Matchers:

  "msg" should "return the expected string" in {
    msg shouldBe "I was compiled by Scala 3. :)"
  }

  "hello" should "print Hello world! and the msg" in {
    val out = new ByteArrayOutputStream()
    Console.withOut(out) { hello() }
    val printed = out.toString
    printed should include("Hello world!")
    printed should include(msg)
  }
