package com.eyesaver.facedistance

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.security.MessageDigest

@Suppress("DEPRECATION")
class FaceDistanceModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  companion object { const val NAME = "EyeSaverFaceDistance" }

  private val eventReceiver = object : BroadcastReceiver() {
    override fun onReceive(receiverContext: Context?, intent: Intent?) {
      if (intent?.action != FaceDistanceService.EVENT_ACTION || !context.hasActiveCatalystInstance()) return
      context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("faceDistanceEvent", FaceDistanceService.stateMap(context, intent.getStringExtra("eventType"), intent.getStringExtra("message")))
    }
  }
  private var receiverRegistered = false

  init { registerReceiver() }

  override fun getName(): String = NAME

  @ReactMethod
  fun getCapabilities(promise: Promise) {
    val result = Arguments.createMap().apply {
      putBoolean("androidSupported", true)
      putBoolean("frontCameraAvailable", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
      putBoolean("cameraPermissionGranted", hasCameraPermission())
      putBoolean("overlayPermissionGranted", Settings.canDrawOverlays(context))
      putInt("minimumSdk", 24)
      putString("estimationModel", "calibrated-inter-eye-ratio")
    }
    promise.resolve(result)
  }

  @ReactMethod
  fun getState(promise: Promise) {
    val prefs = context.getSharedPreferences(FaceDistanceService.PREFS, Context.MODE_PRIVATE)
    if (prefs.getString("mode", "IDLE") == "CALIBRATING" && !FaceCalibrationActivity.isRunning) {
      prefs.edit()
        .putBoolean("active", false)
        .putString("mode", "IDLE")
        .putInt("samplesCollected", 0)
        .putInt("samplesRequired", 0)
        .apply()
    }
    promise.resolve(FaceDistanceService.stateMap(context))
  }

  @ReactMethod
  fun getCalibration(profileId: String, promise: Promise) {
    try {
      val key = profileKey(profileId)
      val prefs = context.getSharedPreferences(FaceDistanceService.PREFS, Context.MODE_PRIVATE)
      val reference = prefs.getFloat("calibration_reference_$key", 0f)
      promise.resolve(Arguments.createMap().apply {
        putBoolean("calibrated", reference > 0f)
        putString("profileId", profileId)
        putDouble("knownDistanceCm", prefs.getFloat("calibration_distance_$key", 0f).toDouble())
        putDouble("referenceEyeDistancePx", reference.toDouble())
        putDouble("calibratedAtMs", prefs.getLong("calibration_at_$key", 0L).toDouble())
      })
    } catch (error: IllegalArgumentException) {
      promise.reject("E_INVALID_PROFILE", error.message, error)
    }
  }

  @ReactMethod
  fun clearCalibration(profileId: String, promise: Promise) {
    try {
      val key = profileKey(profileId)
      context.getSharedPreferences(FaceDistanceService.PREFS, Context.MODE_PRIVATE).edit()
        .remove("calibration_reference_$key")
        .remove("calibration_distance_$key")
        .remove("calibration_at_$key")
        .apply()
      promise.resolve(true)
    } catch (error: IllegalArgumentException) {
      promise.reject("E_INVALID_PROFILE", error.message, error)
    }
  }

  @ReactMethod
  fun startCalibration(options: ReadableMap?, promise: Promise) {
    try {
      requireReady(showOverlay = false)
      val activity = context.currentActivity ?: throw IllegalStateException("Keep Eye Saver visible while opening calibration")
      val profileId = options.stringValue("profileId")
      profileKey(profileId)
      val knownDistance = options.doubleValue("knownDistanceCm", 100.0).coerceIn(50.0, 100.0)
      val sampleCount = options.intValue("sampleCount", 4).coerceIn(3, 6)
      activity.startActivity(Intent(activity, FaceCalibrationActivity::class.java)
        .putExtra(FaceCalibrationActivity.EXTRA_PROFILE_ID, profileId)
        .putExtra(FaceCalibrationActivity.EXTRA_KNOWN_DISTANCE_CM, knownDistance)
        .putExtra(FaceCalibrationActivity.EXTRA_SAMPLE_COUNT, sampleCount))
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("E_CALIBRATION_START", error.message ?: "Could not start face-distance calibration", error)
    }
  }

  @ReactMethod
  fun startMonitoring(options: ReadableMap?, promise: Promise) {
    try {
      val showOverlay = options.boolValue("showOverlay", true)
      requireReady(showOverlay)
      val profileId = options.stringValue("profileId")
      val profileKey = profileKey(profileId)
      val prefs = context.getSharedPreferences(FaceDistanceService.PREFS, Context.MODE_PRIVATE)
      if (prefs.getFloat("calibration_reference_$profileKey", 0f) <= 0f) {
        promise.reject("E_CALIBRATION_REQUIRED", "Calibrate this face at a known distance before monitoring")
        return
      }
      val threshold = options.doubleValue("thresholdCm", 25.0).coerceIn(15.0, 60.0)
      val clearThreshold = options.doubleValue("clearThresholdCm", threshold + 5.0)
        .coerceIn(threshold + 2.0, 80.0)
      startService(Intent(context, FaceDistanceService::class.java)
        .setAction(FaceDistanceService.ACTION_MONITOR)
        .putExtra("profileId", profileId)
        .putExtra("thresholdCm", threshold)
        .putExtra("clearThresholdCm", clearThreshold)
        .putExtra("intervalMs", options.longValue("intervalMs", 1_500L).coerceIn(500L, 10_000L))
        .putExtra("consecutiveNearSamples", options.intValue("consecutiveNearSamples", 3).coerceIn(1, 10))
        .putExtra("smoothingWindow", options.intValue("smoothingWindow", 5).coerceIn(1, 9))
        .putExtra("showOverlay", showOverlay))
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("E_MONITOR_START", error.message ?: "Could not start face-distance monitoring", error)
    }
  }

  @ReactMethod
  fun stop(promise: Promise) {
    try {
      context.startService(Intent(context, FaceDistanceService::class.java).setAction(FaceDistanceService.ACTION_STOP))
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("E_MONITOR_STOP", "Could not stop face-distance monitoring", error)
    }
  }

  @ReactMethod fun addListener(eventName: String) = Unit
  @ReactMethod fun removeListeners(count: Double) = Unit

  override fun invalidate() {
    if (receiverRegistered) {
      try { context.unregisterReceiver(eventReceiver) } catch (_: IllegalArgumentException) {}
      receiverRegistered = false
    }
    super.invalidate()
  }

  private fun startService(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
  }

  private fun registerReceiver() {
    val filter = IntentFilter(FaceDistanceService.EVENT_ACTION)
    ContextCompat.registerReceiver(context, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    receiverRegistered = true
  }

  private fun requireReady(showOverlay: Boolean) {
    require(context.currentActivity?.hasWindowFocus() == true) { "Keep Eye Saver visible while starting camera monitoring" }
    require(hasCameraPermission()) { "Camera permission is required" }
    require(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) { "A front camera is required" }
    if (showOverlay) require(Settings.canDrawOverlays(context)) { "Display-over-other-apps permission is required for distance alerts" }
  }

  private fun hasCameraPermission() =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

  private fun profileKey(profileId: String): String {
    require(profileId.isNotBlank()) { "profileId cannot be blank" }
    return MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
  }

  private fun ReadableMap?.stringValue(key: String): String {
    require(this != null && hasKey(key) && !isNull(key)) { "$key is required" }
    return getString(key)?.trim().orEmpty()
  }

  private fun ReadableMap?.doubleValue(key: String, fallback: Double): Double =
    if (this != null && hasKey(key) && !isNull(key)) getDouble(key) else fallback

  private fun ReadableMap?.longValue(key: String, fallback: Long): Long = doubleValue(key, fallback.toDouble()).toLong()
  private fun ReadableMap?.intValue(key: String, fallback: Int): Int = doubleValue(key, fallback.toDouble()).toInt()
  private fun ReadableMap?.boolValue(key: String, fallback: Boolean): Boolean =
    if (this != null && hasKey(key) && !isNull(key)) getBoolean(key) else fallback
}
