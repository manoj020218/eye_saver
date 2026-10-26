package com.eyesaver.parentauth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
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

class ParentAuthModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  companion object {
    const val NAME = "EyeSaverParentAuth"
    private const val PREFS = "eye_saver_parent_auth"
    private const val KEY_ALIAS = "eye_saver_parent_pin_key_v1"
    private const val ITERATIONS = 210_000
    private const val MAX_ATTEMPTS = 5
    private const val LOCKOUT_MS = 30_000L
  }
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  override fun getName(): String = NAME

  @ReactMethod
  fun setPin(pin: String, promise: Promise) {
    if (!pin.matches(Regex("^[0-9]{4,12}$"))) {
      promise.reject("E_INVALID_PIN", "PIN must contain 4 to 12 digits")
      return
    }
    try {
      val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
      val digest = derive(pin, salt)
      val stored = prefs.edit()
        .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        .putString("digest", encrypt(digest))
        .putInt("failures", 0)
        .putLong("lockedUntilMs", 0L)
        .commit()
      if (!stored) throw IllegalStateException("Android did not persist the PIN")
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("E_AUTH_STORAGE", "Could not securely save the parent PIN", error)
    }
  }

  @ReactMethod
  fun verifyPin(pin: String, promise: Promise) {
    val now = System.currentTimeMillis()
    val lockedUntil = prefs.getLong("lockedUntilMs", 0L)
    if (lockedUntil > now) { promise.resolve(result(false, 0, lockedUntil)); return }
    val saltText = prefs.getString("salt", null)
    val digestText = prefs.getString("digest", null)
    if (saltText == null || digestText == null) { promise.reject("E_NOT_CONFIGURED", "Parent PIN has not been configured"); return }
    try {
      val expected = decrypt(digestText)
      val actual = derive(pin, Base64.decode(saltText, Base64.NO_WRAP))
      if (MessageDigest.isEqual(expected, actual)) {
        prefs.edit().putInt("failures", 0).putLong("lockedUntilMs", 0L).apply()
        promise.resolve(result(true, MAX_ATTEMPTS, 0L))
        return
      }
      val failures = prefs.getInt("failures", 0) + 1
      val newLockout = if (failures >= MAX_ATTEMPTS) now + LOCKOUT_MS else 0L
      prefs.edit().putInt("failures", if (newLockout > 0L) 0 else failures).putLong("lockedUntilMs", newLockout).apply()
      promise.resolve(result(false, if (newLockout > 0L) 0 else MAX_ATTEMPTS - failures, newLockout))
    } catch (error: Exception) {
      promise.reject("E_AUTH_STORAGE", "Could not verify the parent PIN", error)
    }
  }

  @ReactMethod
  fun getStatus(promise: Promise) {
    promise.resolve(Arguments.createMap().apply {
      putBoolean("configured", prefs.contains("digest") && prefs.contains("salt"))
      putDouble("lockedUntilMs", prefs.getLong("lockedUntilMs", 0L).toDouble())
      putInt("maxAttempts", MAX_ATTEMPTS)
    })
  }

  @ReactMethod fun clear(promise: Promise) { prefs.edit().clear().apply(); promise.resolve(true) }

  private fun result(success: Boolean, remaining: Int, lockedUntil: Long) = Arguments.createMap().apply {
    putBoolean("success", success)
    putInt("remainingAttempts", remaining)
    putDouble("lockedUntilMs", lockedUntil.toDouble())
    putDouble("timestampMs", System.currentTimeMillis().toDouble())
  }

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
