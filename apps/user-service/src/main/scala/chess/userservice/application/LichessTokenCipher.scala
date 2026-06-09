package chess.userservice.application

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

trait LichessTokenCipher:
  def encrypt(plainText: String): Either[String, Array[Byte]]
  def decrypt(cipherText: Array[Byte]): Either[String, String]

object LichessTokenCipher:
  private val IvBytes      = 12
  private val TagBits      = 128
  private val KeyBytes     = 32
  private val KeyAlgorithm = "AES"
  private val CipherSpec   = "AES/GCM/NoPadding"

  /** Build a cipher from a base64-encoded 32-byte AES-256 key.
    * Returns Left("invalid_encryption_key") if the key is missing, not valid base64, or not exactly 32 bytes.
    */
  def fromBase64Key(base64Key: String): Either[String, LichessTokenCipher] =
    try
      val decoded = Base64.getDecoder.decode(base64Key)
      if decoded.length != KeyBytes then Left("invalid_encryption_key")
      else Right(AesGcmCipher(decoded))
    catch
      case _: IllegalArgumentException => Left("invalid_encryption_key")

  private final class AesGcmCipher(keyBytes: Array[Byte]) extends LichessTokenCipher:
    private val rng = new SecureRandom()

    def encrypt(plainText: String): Either[String, Array[Byte]] =
      try
        val iv = new Array[Byte](IvBytes)
        rng.nextBytes(iv)
        val cipher = Cipher.getInstance(CipherSpec)
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, KeyAlgorithm), new GCMParameterSpec(TagBits, iv))
        val encrypted = cipher.doFinal(plainText.getBytes("UTF-8"))
        val result    = new Array[Byte](IvBytes + encrypted.length)
        System.arraycopy(iv, 0, result, 0, IvBytes)
        System.arraycopy(encrypted, 0, result, IvBytes, encrypted.length)
        Right(result)
      catch
        case _: Exception => Left("encryption_failed")

    def decrypt(cipherText: Array[Byte]): Either[String, String] =
      if cipherText.length <= IvBytes then Left("invalid_ciphertext")
      else
        try
          val iv        = cipherText.take(IvBytes)
          val encrypted = cipherText.drop(IvBytes)
          val cipher    = Cipher.getInstance(CipherSpec)
          cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, KeyAlgorithm), new GCMParameterSpec(TagBits, iv))
          Right(new String(cipher.doFinal(encrypted), "UTF-8"))
        catch
          case _: Exception => Left("decryption_failed")
