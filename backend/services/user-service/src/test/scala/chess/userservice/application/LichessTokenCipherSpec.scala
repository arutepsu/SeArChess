package chess.userservice.application

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Base64

class LichessTokenCipherSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def key32(fill: Byte = 1): String =
    Base64.getEncoder.encodeToString(Array.fill[Byte](32)(fill))

  // ── Key validation ────────────────────────────────────────────────────────

  "LichessTokenCipher.fromBase64Key" should "accept a valid 32-byte base64 key" in {
    LichessTokenCipher.fromBase64Key(key32()).isRight shouldBe true
  }

  it should "reject a non-base64 string" in {
    LichessTokenCipher.fromBase64Key("not-valid-base64!!!").left.value shouldBe "invalid_encryption_key"
  }

  it should "reject a key shorter than 32 bytes" in {
    val short = Base64.getEncoder.encodeToString(Array.fill[Byte](16)(1))
    LichessTokenCipher.fromBase64Key(short).left.value shouldBe "invalid_encryption_key"
  }

  it should "reject a key longer than 32 bytes" in {
    val long = Base64.getEncoder.encodeToString(Array.fill[Byte](64)(1))
    LichessTokenCipher.fromBase64Key(long).left.value shouldBe "invalid_encryption_key"
  }

  it should "reject an empty string" in {
    LichessTokenCipher.fromBase64Key("").left.value shouldBe "invalid_encryption_key"
  }

  // ── Roundtrip ─────────────────────────────────────────────────────────────

  "encrypt / decrypt" should "roundtrip a token" in {
    val cipher     = LichessTokenCipher.fromBase64Key(key32()).value
    val cipherText = cipher.encrypt("secret-token").value
    cipher.decrypt(cipherText).value shouldBe "secret-token"
  }

  it should "roundtrip a token containing special characters" in {
    val cipher     = LichessTokenCipher.fromBase64Key(key32()).value
    val token      = "Bearer lich_abc123/+?#=&"
    val cipherText = cipher.encrypt(token).value
    cipher.decrypt(cipherText).value shouldBe token
  }

  // ── Random IV ─────────────────────────────────────────────────────────────

  it should "produce a different ciphertext on each call (random IV)" in {
    val cipher = LichessTokenCipher.fromBase64Key(key32()).value
    val ct1    = cipher.encrypt("secret-token").value
    val ct2    = cipher.encrypt("secret-token").value
    ct1 should not equal ct2
    cipher.decrypt(ct1).value shouldBe "secret-token"
    cipher.decrypt(ct2).value shouldBe "secret-token"
  }

  // ── Wrong key ─────────────────────────────────────────────────────────────

  it should "fail to decrypt with a different key" in {
    val cipherA = LichessTokenCipher.fromBase64Key(key32(fill = 1)).value
    val cipherB = LichessTokenCipher.fromBase64Key(key32(fill = 2)).value
    val ct      = cipherA.encrypt("secret-token").value
    cipherB.decrypt(ct).left.value shouldBe "decryption_failed"
  }

  // ── Malformed ciphertext ──────────────────────────────────────────────────

  it should "return invalid_ciphertext for an empty byte array" in {
    val cipher = LichessTokenCipher.fromBase64Key(key32()).value
    cipher.decrypt(Array.emptyByteArray).left.value shouldBe "invalid_ciphertext"
  }

  it should "return invalid_ciphertext for a byte array shorter than the 12-byte IV" in {
    val cipher = LichessTokenCipher.fromBase64Key(key32()).value
    cipher.decrypt(Array.fill[Byte](8)(0)).left.value shouldBe "invalid_ciphertext"
  }

  it should "return invalid_ciphertext for a byte array exactly 12 bytes (IV only, no ciphertext)" in {
    val cipher = LichessTokenCipher.fromBase64Key(key32()).value
    cipher.decrypt(Array.fill[Byte](12)(0)).left.value shouldBe "invalid_ciphertext"
  }

  it should "return decryption_failed for random bytes longer than the IV" in {
    val cipher  = LichessTokenCipher.fromBase64Key(key32()).value
    val garbage = Array.fill[Byte](40)(7)
    cipher.decrypt(garbage).left.value shouldBe "decryption_failed"
  }
