package com.eyesaver.facedistance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

class FaceDistanceService : Service(), LifecycleOwner {
  companion object {
    const val ACTION_CALIBRATE = "com.eyesaver.facedistance.CALIBRATE"
    const val ACTION_MONITOR = "com.eyesaver.facedistance.MONITOR"
    const val ACTION_STOP = "com.eyesaver.facedistance.STOP"
    const val EVENT_ACTION = "com.eyesaver.facedistance.EVENT"
    const val PREFS = "eye_saver_face_distance"
    private const val CHANNEL = "eye_saver_face_distance_camera"
    private const val NOTIFICATION_ID = 8161

    fun stateMap(context: Context, eventType: String? = null, message: String? = null): WritableMap {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val distance = prefs.getFloat("lastDistanceCm", 0f)
      return Arguments.createMap().apply {
        putString("eventType", eventType ?: "state")
        putString("mode", prefs.getString("mode", "IDLE")?.lowercase() ?: "idle")
        putBoolean("active", prefs.getBoolean("active", false))
        putString("profileId", prefs.getString("profileId", null))
        if (distance > 0f) putDouble("distanceCm", distance.toDouble()) else putNull("distanceCm")
        putDouble("thresholdCm", prefs.getFloat("thresholdCm", 25f).toDouble())
        putDouble("clearThresholdCm", prefs.getFloat("clearThresholdCm", 30f).toDouble())
        putBoolean("tooClose", prefs.getBoolean("tooClose", false))
        putInt("samplesCollected", prefs.getInt("samplesCollected", 0))
        putInt("samplesRequired", prefs.getInt("samplesRequired", 0))
        putDouble("lastAlertAtMs", prefs.getLong("lastAlertAtMs", 0L).toDouble())
        putDouble("timestampMs", System.currentTimeMillis().toDouble())
        if (!message.isNullOrBlank()) putString("message", message)
      }
    }
  }

  private val lifecycleRegistry = LifecycleRegistry(this)
  override val lifecycle: Lifecycle get() = lifecycleRegistry
  private val mainHandler = Handler(Looper.getMainLooper())
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val processing = AtomicBoolean(false)
  private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
  private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
  private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
  private val detector by lazy {
    FaceDetection.getClient(FaceDetectorOptions.Builder()
      .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
      .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
      .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
      .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
      .setMinFaceSize(0.15f)
      .build())
  }
  private var cameraProvider: ProcessCameraProvider? = null
  private var cameraBound = false
  private var screenReceiverRegistered = false
  private var mode = "IDLE"
  private var profileId = ""
  private var profileKey = ""
  private var knownDistanceCm = 35.0
  private var requiredCalibrationSamples = 15
  private val calibrationSamples = mutableListOf<Double>()
  private var thresholdCm = 25.0
  private var clearThresholdCm = 30.0
  private var analysisIntervalMs = 1_500L
  private var consecutiveSamples = 3
  private var smoothingWindow = 5
  private var showOverlay = true
  private var lastAnalyzedAtElapsed = 0L
  private var lastValidFaceAtElapsed = 0L
  private var lastNoFaceEventAtElapsed = 0L
  private var nearCount = 0
  private var clearCount = 0
  private var tooClose = false
  private val recentDistances = ArrayDeque<Double>()
  private var overlay: View? = null
  private var overlayText: TextView? = null

  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        Intent.ACTION_SCREEN_OFF -> unbindCamera()
        Intent.ACTION_SCREEN_ON -> if (mode != "IDLE") bindCamera()
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    createChannel()
    val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
    else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
    screenReceiverRegistered = true
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopMonitor(emitStopped = true)
        return START_NOT_STICKY
      }
      ACTION_CALIBRATE -> configureCalibration(intent)
      ACTION_MONITOR -> configureMonitor(intent)
      else -> {
        stopMonitor(emitStopped = false)
        return START_NOT_STICKY
      }
    }
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      fail("Camera permission is not granted")
      return START_NOT_STICKY
    }
    startForeground(NOTIFICATION_ID, notification())
    if (power.isInteractive) bindCamera()
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    unbindCamera()
    hideOverlay()
    try { detector.close() } catch (_: Exception) { Unit }
    cameraExecutor.shutdown()
    if (screenReceiverRegistered) try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) { Unit }
    screenReceiverRegistered = false
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    prefs.edit().putBoolean("active", false).putString("mode", "IDLE").putBoolean("tooClose", false).apply()
    super.onDestroy()
  }

  private fun configureCalibration(intent: Intent) {
    resetRuntime()
    mode = "CALIBRATING"
    profileId = intent.getStringExtra("profileId").orEmpty()
    profileKey = hash(profileId)
    knownDistanceCm = intent.getDoubleExtra("knownDistanceCm", 35.0).coerceIn(20.0, 100.0)
    requiredCalibrationSamples = intent.getIntExtra("sampleCount", 15).coerceIn(5, 50)
    analysisIntervalMs = 250L
    prefs.edit()
      .putBoolean("active", true)
      .putString("mode", mode)
      .putString("profileId", profileId)
      .putInt("samplesCollected", 0)
      .putInt("samplesRequired", requiredCalibrationSamples)
      .putBoolean("tooClose", false)
      .apply()
    emit("state", "Hold the phone $knownDistanceCm cm from one forward-facing face")
  }

  private fun configureMonitor(intent: Intent) {
    resetRuntime()
    mode = "MONITORING"
    profileId = intent.getStringExtra("profileId").orEmpty()
    profileKey = hash(profileId)
    thresholdCm = intent.getDoubleExtra("thresholdCm", 25.0).coerceIn(15.0, 60.0)
    clearThresholdCm = intent.getDoubleExtra("clearThresholdCm", thresholdCm + 5.0).coerceAtLeast(thresholdCm + 2.0)
    analysisIntervalMs = intent.getLongExtra("intervalMs", 1_500L).coerceIn(500L, 10_000L)
    consecutiveSamples = intent.getIntExtra("consecutiveNearSamples", 3).coerceIn(1, 10)
    smoothingWindow = intent.getIntExtra("smoothingWindow", 5).coerceIn(1, 9)
    showOverlay = intent.getBooleanExtra("showOverlay", true)
    prefs.edit()
      .putBoolean("active", true)
      .putString("mode", mode)
      .putString("profileId", profileId)
      .putFloat("thresholdCm", thresholdCm.toFloat())
      .putFloat("clearThresholdCm", clearThresholdCm.toFloat())
      .putInt("samplesCollected", 0)
      .putInt("samplesRequired", 0)
      .putBoolean("tooClose", false)
      .apply()
    emit("state", "Face-distance monitoring started")
  }

  private fun resetRuntime() {
    unbindCamera()
    hideOverlay()
    calibrationSamples.clear()
    recentDistances.clear()
    processing.set(false)
    lastAnalyzedAtElapsed = 0L
    lastValidFaceAtElapsed = 0L
    lastNoFaceEventAtElapsed = 0L
    nearCount = 0
    clearCount = 0
    tooClose = false
  }

  private fun bindCamera() {
    if (cameraBound || mode == "IDLE" || !power.isInteractive) return
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      try {
        if (mode == "IDLE" || !power.isInteractive) return@addListener
        val provider = future.get()
        cameraProvider = provider
        provider.unbindAll()
        val analysis = ImageAnalysis.Builder()
          .setTargetResolution(Size(640, 480))
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
        analysis.setAnalyzer(cameraExecutor) { analyze(it) }
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        cameraBound = true
      } catch (error: Exception) {
        fail("Front camera could not start: ${error.message ?: error.javaClass.simpleName}")
      }
    }, { runnable -> mainHandler.post(runnable) })
  }

  private fun unbindCamera() {
    try { cameraProvider?.unbindAll() } catch (_: Exception) { Unit }
    cameraBound = false
    processing.set(false)
  }

  @androidx.camera.core.ExperimentalGetImage
  private fun analyze(imageProxy: ImageProxy) {
    val now = SystemClock.elapsedRealtime()
    if (mode == "IDLE" || now - lastAnalyzedAtElapsed < analysisIntervalMs || !processing.compareAndSet(false, true)) {
      imageProxy.close()
      return
    }
    lastAnalyzedAtElapsed = now
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
      processing.set(false)
      imageProxy.close()
      return
    }
    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    detector.process(input)
      .addOnSuccessListener(cameraExecutor) { faces -> handleFaces(faces) }
      .addOnFailureListener(cameraExecutor) { error ->
        mainHandler.post { fail("Face detector error: ${error.message ?: error.javaClass.simpleName}") }
      }
      .addOnCompleteListener(cameraExecutor) {
        processing.set(false)
        imageProxy.close()
      }
  }

  private fun handleFaces(faces: List<Face>) {
    val face = faces.singleOrNull()
    if (face == null) {
      handleNoFace(if (faces.isEmpty()) "No face detected" else "Keep only one face in view")
      return
    }
    val maxAngle = if (mode == "CALIBRATING") 18f else 28f
    if (abs(face.headEulerAngleX) > maxAngle || abs(face.headEulerAngleY) > maxAngle || abs(face.headEulerAngleZ) > maxAngle) {
      handleNoFace("Look straight at the phone")
      return
    }
    val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    if (left == null || right == null) {
      handleNoFace("Both eyes must be visible")
      return
    }
    val eyeDistancePx = hypot((left.x - right.x).toDouble(), (left.y - right.y).toDouble())
    if (eyeDistancePx < 12.0) {
      handleNoFace("Move the face into clear view")
      return
    }
    lastValidFaceAtElapsed = SystemClock.elapsedRealtime()
    if (mode == "CALIBRATING") collectCalibration(eyeDistancePx)
    else if (mode == "MONITORING") updateDistance(eyeDistancePx)
  }

  private fun collectCalibration(eyeDistancePx: Double) {
    calibrationSamples.add(eyeDistancePx)
    prefs.edit().putInt("samplesCollected", calibrationSamples.size).apply()
    emit("calibration_progress", "Calibration ${calibrationSamples.size}/$requiredCalibrationSamples")
    if (calibrationSamples.size < requiredCalibrationSamples) return
    val reference = median(calibrationSamples)
    mode = "COMPLETING"
    prefs.edit()
      .putFloat("calibration_reference_$profileKey", reference.toFloat())
      .putFloat("calibration_distance_$profileKey", knownDistanceCm.toFloat())
      .putLong("calibration_at_$profileKey", System.currentTimeMillis())
      .apply()
    emit("calibration_complete", "Calibration complete at ${knownDistanceCm.toInt()} cm")
    mainHandler.postDelayed({ stopMonitor(emitStopped = false) }, 250L)
  }

  private fun updateDistance(eyeDistancePx: Double) {
    val reference = prefs.getFloat("calibration_reference_$profileKey", 0f).toDouble()
    val calibrationDistance = prefs.getFloat("calibration_distance_$profileKey", 0f).toDouble()
    if (reference <= 0.0 || calibrationDistance <= 0.0) {
      mainHandler.post { fail("Face-distance calibration is missing") }
      return
    }
    val estimate = (calibrationDistance * reference / eyeDistancePx).coerceIn(5.0, 150.0)
    recentDistances.addLast(estimate)
    while (recentDistances.size > smoothingWindow) recentDistances.removeFirst()
    val smoothed = median(recentDistances.toList())
    prefs.edit().putFloat("lastDistanceCm", smoothed.toFloat()).apply()
    emit("distance", null)

    if (smoothed < thresholdCm) {
      nearCount++
      clearCount = 0
      if (!tooClose && nearCount >= consecutiveSamples) {
        tooClose = true
        val alertAt = System.currentTimeMillis()
        prefs.edit().putBoolean("tooClose", true).putLong("lastAlertAtMs", alertAt).apply()
        if (showOverlay) mainHandler.post { showDistanceOverlay(smoothed) }
        emit("too_close", "Phone is approximately ${smoothed.toInt()} cm from the face")
      } else if (tooClose && showOverlay) {
        mainHandler.post { showDistanceOverlay(smoothed) }
      }
    } else if (smoothed >= clearThresholdCm) {
      nearCount = 0
      clearCount++
      if (tooClose && clearCount >= 2) clearWarning("Safe viewing distance restored")
    } else {
      nearCount = 0
      clearCount = 0
    }
  }

  private fun handleNoFace(message: String) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastNoFaceEventAtElapsed >= 3_000L) {
      lastNoFaceEventAtElapsed = now
      emit("no_face", message)
    }
    if (tooClose && lastValidFaceAtElapsed > 0L && now - lastValidFaceAtElapsed >= 5_000L) {
      clearWarning("Face is no longer in view")
    }
  }

  private fun clearWarning(message: String) {
    tooClose = false
    nearCount = 0
    clearCount = 0
    prefs.edit().putBoolean("tooClose", false).apply()
    mainHandler.post { hideOverlay() }
    emit("cleared", message)
  }

  private fun showDistanceOverlay(distanceCm: Double) {
    if (!Settings.canDrawOverlays(this) || mode != "MONITORING") return
    val label = "Screen is too close\nApprox. ${distanceCm.toInt()} cm  •  Move beyond ${clearThresholdCm.toInt()} cm"
    overlayText?.let { it.text = label; return }
    val textView = TextView(this).apply {
      text = label
      setTextColor(Color.WHITE)
      textSize = 18f
      gravity = Gravity.CENTER
      setPadding(dp(20), dp(18), dp(20), dp(18))
      background = GradientDrawable().apply {
        setColor(Color.rgb(160, 35, 30))
        cornerRadius = dp(18).toFloat()
      }
      elevation = dp(8).toFloat()
    }
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      type,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      x = dp(12)
      y = dp(44)
      width = resources.displayMetrics.widthPixels - dp(24)
    }
    try {
      windowManager.addView(textView, params)
      overlay = textView
      overlayText = textView
    } catch (_: Exception) { Unit }
  }

  private fun hideOverlay() {
    overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) { Unit } }
    overlay = null
    overlayText = null
  }

  private fun fail(message: String) {
    emit("camera_error", message)
    stopMonitor(emitStopped = false)
  }

  private fun stopMonitor(emitStopped: Boolean) {
    val wasActive = mode != "IDLE" || prefs.getBoolean("active", false)
    mode = "IDLE"
    unbindCamera()
    hideOverlay()
    prefs.edit().putBoolean("active", false).putString("mode", mode).putBoolean("tooClose", false).apply()
    if (emitStopped && wasActive) emit("stopped", "Face-distance monitoring stopped")
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
    else @Suppress("DEPRECATION") stopForeground(true)
    stopSelf()
  }

  private fun emit(type: String, message: String?) {
    sendBroadcast(Intent(EVENT_ACTION).setPackage(packageName)
      .putExtra("eventType", type)
      .putExtra("message", message))
  }

  private fun notification(): Notification {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val pending = launch?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
      else @Suppress("DEPRECATION") Notification.Builder(this)
    val text = if (mode == "CALIBRATING") "Hold one face at the instructed distance" else "Estimating viewing distance on this device"
    return builder
      .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_camera)
      .setContentTitle(if (mode == "CALIBRATING") "Face-distance calibration" else "Eye-distance warning is active")
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(pending)
      .build()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Face-distance camera", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
