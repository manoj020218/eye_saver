package com.faceproximity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlin.math.max
import kotlin.math.min

@Suppress("DEPRECATION")
class FaceProximityModule(
  private val context: ReactApplicationContext
) : ReactContextBaseJavaModule(context), SensorEventListener, LifecycleEventListener {

  companion object {
    const val NAME = "FaceProximity"
    private const val EVENT_MEASUREMENT = "faceProximityMeasurement"
    private const val EVENT_THRESHOLD = "faceProximityThreshold"
    private const val MIN_INTERVAL_MS = 100L
    private const val MAX_INTERVAL_MS = 60_000L
  }

  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var requested = false
  @Volatile private var registered = false
  @Volatile private var appInForeground = true
  private var pauseInBackground = true
  private var intervalMs = 500L
  private var thresholdCm = proximitySensor?.maximumRange ?: 5f
  private var hysteresisCm = 0.5f
  private var lastEmissionAt = 0L
  private var latestMeasurement: WritableMap? = null
  private var previousTooClose: Boolean? = null

  init {
    context.addLifecycleEventListener(this)
  }

  override fun getName(): String = NAME

  @ReactMethod
  fun getCapabilities(promise: Promise) {
    val sensor = proximitySensor
    val result = Arguments.createMap()
    result.putBoolean("available", sensor != null)
    if (sensor != null) {
      result.putDouble("maxRangeCm", sensor.maximumRange.toDouble())
      result.putDouble("resolutionCm", sensor.resolution.toDouble())
      result.putDouble("powerMilliAmp", sensor.power.toDouble())
      result.putString("name", sensor.name)
      result.putString("vendor", sensor.vendor)
      result.putInt("version", sensor.version)
      result.putBoolean("isWakeUpSensor", sensor.isWakeUpSensor)
      result.putString("reportingMode", reportingModeName(sensor.reportingMode))
      result.putBoolean("isLikelyBinary", isLikelyBinary(sensor))
    }
    promise.resolve(result)
  }

  @ReactMethod
  fun start(options: ReadableMap?, promise: Promise) {
    val sensor = proximitySensor
    if (sensor == null) {
      promise.reject("E_NO_PROXIMITY_SENSOR", "This Android device has no proximity sensor")
      return
    }

    // Re-register so changed sampling options take effect on continuous sensors.
    unregisterSensor()

    intervalMs = options.longValue("intervalMs", 500L).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    thresholdCm = options.doubleValue("thresholdCm", sensor.maximumRange.toDouble())
      .toFloat().coerceIn(0.01f, sensor.maximumRange)
    hysteresisCm = options.doubleValue("hysteresisCm", 0.5)
      .toFloat().coerceIn(0f, sensor.maximumRange)
    pauseInBackground = options.booleanValue("pauseInBackground", true)

    requested = true
    previousTooClose = null
    lastEmissionAt = 0L
    registerIfAllowed()

    val result = Arguments.createMap()
    result.putBoolean("monitoring", registered)
    result.putDouble("thresholdCm", thresholdCm.toDouble())
    result.putDouble("intervalMs", intervalMs.toDouble())
    result.putBoolean("pauseInBackground", pauseInBackground)
    result.putBoolean("isLikelyBinary", isLikelyBinary(sensor))
    promise.resolve(result)
  }

  @ReactMethod
  fun stop(promise: Promise) {
    requested = false
    unregisterSensor()
    previousTooClose = null
    promise.resolve(null)
  }

  @ReactMethod
  fun getLatestMeasurement(promise: Promise) {
    promise.resolve(latestMeasurement?.copy())
  }

  // Required by NativeEventEmitter; subscriptions themselves live on the JS side.
  @ReactMethod fun addListener(eventName: String) = Unit
  @ReactMethod fun removeListeners(count: Double) = Unit

  override fun onSensorChanged(event: SensorEvent) {
    if (!requested || event.sensor.type != Sensor.TYPE_PROXIMITY) return
    val now = System.currentTimeMillis()
    val distance = event.values.firstOrNull() ?: return
    val wasTooClose = previousTooClose
    val exitThreshold = min(proximitySensor?.maximumRange ?: Float.MAX_VALUE, thresholdCm + hysteresisCm)
    val isTooClose = when (wasTooClose) {
      true -> distance < exitThreshold
      else -> distance < thresholdCm
    }
    val measurement = measurementMap(distance, isTooClose, now, event.accuracy)
    latestMeasurement = measurement.copy()

    // A brief dangerous transition must not be lost to normal event throttling.
    if (wasTooClose == null || wasTooClose != isTooClose) {
      previousTooClose = isTooClose
      val transition = measurement.copy()
      transition.putString("transition", if (isTooClose) "entered" else "exited")
      emit(EVENT_THRESHOLD, transition)
    }
    if (now - lastEmissionAt >= intervalMs) {
      lastEmissionAt = now
      emit(EVENT_MEASUREMENT, measurement)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
  override fun onHostResume() { appInForeground = true; registerIfAllowed() }
  override fun onHostPause() { appInForeground = false; if (pauseInBackground) unregisterSensor() }
  override fun onHostDestroy() { requested = false; unregisterSensor() }

  override fun invalidate() {
    requested = false
    unregisterSensor()
    context.removeLifecycleEventListener(this)
    super.invalidate()
  }

  @Synchronized
  private fun registerIfAllowed() {
    val sensor = proximitySensor ?: return
    if (!requested || registered || (pauseInBackground && !appInForeground)) return
    val samplingPeriodUs = min(intervalMs * 1_000L, Int.MAX_VALUE.toLong()).toInt()
    registered = sensorManager.registerListener(
      this, sensor, max(samplingPeriodUs, SensorManager.SENSOR_DELAY_NORMAL), mainHandler
    )
  }

  @Synchronized
  private fun unregisterSensor() {
    if (!registered) return
    sensorManager.unregisterListener(this, proximitySensor)
    registered = false
  }

  private fun measurementMap(distance: Float, isTooClose: Boolean, timestampMs: Long, accuracy: Int): WritableMap =
    Arguments.createMap().apply {
      putDouble("distanceCm", distance.toDouble())
      putBoolean("isTooClose", isTooClose)
      putDouble("thresholdCm", thresholdCm.toDouble())
      putDouble("timestampMs", timestampMs.toDouble())
      putInt("accuracy", accuracy)
    }

  private fun emit(eventName: String, payload: WritableMap) {
    if (!context.hasActiveCatalystInstance()) return
    context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(eventName, payload)
  }

  private fun isLikelyBinary(sensor: Sensor): Boolean =
    sensor.resolution <= 0f || sensor.maximumRange / sensor.resolution <= 2f

  private fun reportingModeName(mode: Int): String = when (mode) {
    Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
    Sensor.REPORTING_MODE_ON_CHANGE -> "on-change"
    Sensor.REPORTING_MODE_ONE_SHOT -> "one-shot"
    Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special-trigger"
    else -> "unknown"
  }

  private fun ReadableMap?.longValue(key: String, fallback: Long): Long =
    if (this != null && hasKey(key) && !isNull(key)) getDouble(key).toLong() else fallback
  private fun ReadableMap?.doubleValue(key: String, fallback: Double): Double =
    if (this != null && hasKey(key) && !isNull(key)) getDouble(key) else fallback
  private fun ReadableMap?.booleanValue(key: String, fallback: Boolean): Boolean =
    if (this != null && hasKey(key) && !isNull(key)) getBoolean(key) else fallback
}
