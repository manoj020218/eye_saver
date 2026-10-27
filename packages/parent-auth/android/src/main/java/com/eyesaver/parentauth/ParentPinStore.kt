package com.eyesaver.parentauth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

data class PinVerification(val success: Boolean, val remainingAttempts: Int, val lockedUntilMs: Long)

class ParentPinStore(context: Context) {
  companion object {
    const val MAX_ATTEMPTS = 5
    private const val PREFS = "eye_saver_parent_auth"
    private const val KEY_ALIAS = "eye_saver_parent_pin_key_v1"
    private const val ITERATIONS = 210_000
    private const val LOCKOUT_MS = 30_000L
  }

  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun setPin(pin: String) {
    require(pin.matches(Regex("^[0-9]{4,12}$"))) { "PIN must contain 4 to 12 digits" }
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val stored = prefs.edit()
      .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
      .putString("digest", encrypt(derive(pin, salt)))
      .putInt("failures", 0)
      .putLong("lockedUntilMs", 0L)
      .commit()
    check(stored) { "Android did not persist the PIN" }
  }

  fun verify(pin: String): PinVerification {
    val now = System.currentTimeMillis()
    val lockedUntil = prefs.getLong("lockedUntilMs", 0L)
    if (lockedUntil > now) return PinVerification(false, 0, lockedUntil)
    val saltText = prefs.getString("salt", null) ?: error("Parent PIN has not been configured")
    val digestText = prefs.getString("digest", null) ?: error("Parent PIN has not been configured")
    val expected = decrypt(digestText)
    val actual = derive(pin, Base64.decode(saltText, Base64.NO_WRAP))
    if (MessageDigest.isEqual(expected, actual)) {
      prefs.edit().putInt("failures", 0).putLong("lockedUntilMs", 0L).apply()
      return PinVerification(true, MAX_ATTEMPTS, 0L)
    }
    val failures = prefs.getInt("failures", 0) + 1
    val newLockout = if (failures >= MAX_ATTEMPTS) now + LOCKOUT_MS else 0L
    prefs.edit().putInt("failures", if (newLockout > 0L) 0 else failures).putLong("lockedUntilMs", newLockout).apply()
    return PinVerification(false, if (newLockout > 0L) 0 else MAX_ATTEMPTS - failures, newLockout)
  }

  fun isConfigured() = prefs.contains("digest") && prefs.contains("salt")
  fun lockedUntilMs() = prefs.getLong("lockedUntilMs", 0L)
  fun clear() = prefs.edit().clear().apply()

  private fun derive(pin: String, salt: ByteArray): ByteArray {
    val algorithm = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
    val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
    return try { SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded } finally { spec.clearPassword() }
  }

  private fun encrypt(cleartext: ByteArray): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
    val encrypted = cipher.doFinal(cleartext)
    return Base64.encodeToString(ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
      .put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array(), Base64.NO_WRAP)
  }

  private fun decrypt(encoded: String): ByteArray {
    val buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
    val ivSize = buffer.get().toInt() and 0xff
    require(ivSize in 12..32 && buffer.remaining() > ivSize) { "Stored PIN is malformed" }
    val iv = ByteArray(ivSize).also { buffer.get(it) }
    val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return Cipher.getInstance("AES/GCM/NoPadding").run {
      init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
      doFinal(encrypted)
    }
  }

  private fun secretKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build())
      generateKey()
    }
  }
}
