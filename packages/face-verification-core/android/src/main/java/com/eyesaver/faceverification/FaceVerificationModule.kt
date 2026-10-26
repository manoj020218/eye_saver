package com.eyesaver.faceverification

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

class FaceVerificationModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  companion object {
    const val NAME = "EyeSaverFaceVerification"
    private const val PREFS = "eye_saver_encrypted_face_templates"
    private const val KEY_ALIAS = "eye_saver_face_template_key_v1"
    private const val MIN_DIMENSION = 16
    private const val MAX_DIMENSION = 4096
  }
  private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

  override fun getName(): String = NAME

  @ReactMethod
  fun enroll(subjectId: String, embedding: ReadableArray, promise: Promise) {
    try {
      val vector = normalized(embedding)
      val payload = ByteBuffer.allocate(4 + vector.size * 4).putInt(vector.size).apply { vector.forEach { putFloat(it) } }.array()
      prefs.edit()
        .putString(templateKey(subjectId), encrypt(payload))
        .putLong(timestampKey(subjectId), System.currentTimeMillis())
        .apply()
      promise.resolve(Arguments.createMap().apply {
        putInt("dimension", vector.size)
        putDouble("enrolledAtMs", prefs.getLong(timestampKey(subjectId), 0L).toDouble())
      })
    } catch (error: IllegalArgumentException) {
      promise.reject("E_INVALID_EMBEDDING", error.message, error)
    } catch (error: Exception) {
      promise.reject("E_CRYPTO", "Could not encrypt the face template", error)
    }
  }

  @ReactMethod
  fun verify(subjectId: String, embedding: ReadableArray, threshold: Double, promise: Promise) {
    try {
      val stored = prefs.getString(templateKey(subjectId), null)
      if (stored == null) { promise.reject("E_NO_ENROLLMENT", "No face template is enrolled for this subject"); return }
      val candidate = normalized(embedding)
      val buffer = ByteBuffer.wrap(decrypt(stored))
      val dimension = buffer.int
      if (dimension !in MIN_DIMENSION..MAX_DIMENSION || buffer.remaining() != dimension * 4) throw IllegalStateException("Stored template is invalid")
      if (candidate.size != dimension) {
        promise.reject("E_DIMENSION_MISMATCH", "Expected $dimension values but received ${candidate.size}")
        return
      }
      var similarity = 0.0
      repeat(dimension) { similarity += buffer.float * candidate[it] }
      val safeThreshold = threshold.coerceIn(-1.0, 1.0)
      promise.resolve(Arguments.createMap().apply {
        putBoolean("match", similarity >= safeThreshold)
        putDouble("similarity", similarity)
        putDouble("threshold", safeThreshold)
        putInt("dimension", dimension)
        putDouble("timestampMs", System.currentTimeMillis().toDouble())
      })
    } catch (error: IllegalArgumentException) {
      promise.reject("E_INVALID_EMBEDDING", error.message, error)
    } catch (error: Exception) {
      promise.reject("E_CRYPTO", "Could not decrypt or compare the face template", error)
    }
  }

  @ReactMethod
  fun isEnrolled(subjectId: String, promise: Promise) {
    try { promise.resolve(prefs.contains(templateKey(subjectId))) }
    catch (error: IllegalArgumentException) { promise.reject("E_INVALID_SUBJECT", error.message, error) }
  }

  @ReactMethod
  fun remove(subjectId: String, promise: Promise) {
    try {
      prefs.edit().remove(templateKey(subjectId)).remove(timestampKey(subjectId)).apply()
      promise.resolve(true)
    } catch (error: IllegalArgumentException) {
      promise.reject("E_INVALID_SUBJECT", error.message, error)
    }
  }

  @ReactMethod
  fun clearAll(promise: Promise) { prefs.edit().clear().apply(); promise.resolve(true) }

  private fun normalized(input: ReadableArray): FloatArray {
    require(input.size() in MIN_DIMENSION..MAX_DIMENSION) { "Embedding dimension must be between $MIN_DIMENSION and $MAX_DIMENSION" }
    val values = FloatArray(input.size())
    var squared = 0.0
    for (index in values.indices) {
      val value = input.getDouble(index)
      require(value.isFinite()) { "Embedding contains a non-finite value" }
      values[index] = value.toFloat()
      squared += value * value
    }
    val norm = sqrt(squared)
    require(norm > 1e-12) { "Embedding cannot be a zero vector" }
    for (index in values.indices) values[index] = (values[index] / norm).toFloat()
    return values
  }

  private fun encrypt(cleartext: ByteArray): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val ciphertext = cipher.doFinal(cleartext)
    return Base64.encodeToString(ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
      .put(cipher.iv.size.toByte()).put(cipher.iv).put(ciphertext).array(), Base64.NO_WRAP)
  }

  private fun decrypt(encoded: String): ByteArray {
    val packed = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
    val ivSize = packed.get().toInt() and 0xff
    require(ivSize in 12..32 && packed.remaining() > ivSize) { "Encrypted template is malformed" }
    val iv = ByteArray(ivSize).also { packed.get(it) }
    val ciphertext = ByteArray(packed.remaining()).also { packed.get(it) }
    return Cipher.getInstance("AES/GCM/NoPadding").run {
      init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
      doFinal(ciphertext)
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

  private fun templateKey(subjectId: String) = "template_${subjectHash(subjectId)}"
  private fun timestampKey(subjectId: String) = "timestamp_${subjectHash(subjectId)}"
  private fun subjectHash(subjectId: String): String {
    require(subjectId.isNotBlank()) { "subjectId cannot be blank" }
    return MessageDigest.getInstance("SHA-256").digest(subjectId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}
