package com.eyesaver.facedistance

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/** Parent-assisted, live calibration. Frames are analysed in memory and never saved. */
class FaceCalibrationActivity : Activity(), LifecycleOwner {
  companion object {
    const val EXTRA_PROFILE_ID = "profileId"
    const val EXTRA_KNOWN_DISTANCE_CM = "knownDistanceCm"
    const val EXTRA_SAMPLE_COUNT = "sampleCount"
    @Volatile var isRunning = false
      private set
  }

  private val lifecycleRegistry = LifecycleRegistry(this)
  override val lifecycle: Lifecycle get() = lifecycleRegistry
  private val mainHandler = Handler(Looper.getMainLooper())
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val processing = AtomicBoolean(false)
  private val prefs by lazy { getSharedPreferences(FaceDistanceService.PREFS, Context.MODE_PRIVATE) }
  private val detector by lazy {
    FaceDetection.getClient(FaceDetectorOptions.Builder()
      .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
      .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
      .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
      .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
      .setMinFaceSize(0.10f)
      .build())
  }

  private lateinit var previewView: PreviewView
  private lateinit var guideView: CalibrationGuideView
  private lateinit var instructionView: TextView
  private lateinit var progressView: TextView
  private lateinit var promptView: TextView
  private var cameraProvider: ProcessCameraProvider? = null
  private var profileId = ""
  private var profileKey = ""
  private var knownDistanceCm = 100.0
  private var requiredSamples = 4
  private val samples = mutableListOf<Double>()
  private var lastAnalysisAt = 0L
  private var stableSince = 0L
  private var lastSampleAt = 0L
  private var lastEyeDistance = 0.0
  private var lastCenterX = 0.0
  private var lastCenterY = 0.0
  private var complete = false
  private var promptIndex = 0
  private val prompts = listOf(
    "Bright eyes love small, healthy habits.",
    "Follow the glow—we are nearly finished.",
    "A little distance makes a big difference.",
    "Great teamwork. Keep looking at the pulse."
  )
  private val promptTicker = object : Runnable {
    override fun run() {
      if (isFinishing || isDestroyed) return
      promptIndex = (promptIndex + 1) % prompts.size
      promptView.animate().alpha(0f).translationY(dp(5).toFloat()).setDuration(130).withEndAction {
        promptView.text = prompts[promptIndex]
        promptView.translationY = dp(5).toFloat()
        promptView.animate().alpha(1f).translationY(0f).setDuration(240).start()
      }.start()
      mainHandler.postDelayed(this, 1_300L)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    isRunning = true
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    window.statusBarColor = Color.rgb(3, 22, 17)
    window.navigationBarColor = Color.rgb(3, 22, 17)

    profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
    if (profileId.isBlank()) {
      finishWithError("A child profile is required for calibration")
      return
    }
    profileKey = hash(profileId)
    knownDistanceCm = intent.getDoubleExtra(EXTRA_KNOWN_DISTANCE_CM, 100.0).coerceIn(50.0, 100.0)
    requiredSamples = intent.getIntExtra(EXTRA_SAMPLE_COUNT, 4).coerceIn(3, 6)
    buildUi()

    prefs.edit()
      .putBoolean("active", true)
      .putString("mode", "CALIBRATING")
      .putString("profileId", profileId)
      .putInt("samplesCollected", 0)
      .putInt("samplesRequired", requiredSamples)
      .putBoolean("tooClose", false)
      .apply()
    emit("state", "Parent-assisted calibration opened")
    mainHandler.postDelayed(promptTicker, 1_300L)

    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      finishWithError("Camera permission is not granted")
      return
    }
    startCamera()
  }

  override fun onStart() {
    super.onStart()
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
  }

  override fun onResume() {
    super.onResume()
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
  }

  override fun onPause() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    super.onPause()
  }

  override fun onStop() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    super.onStop()
  }

  @Deprecated("Deprecated in Android")
  override fun onBackPressed() {
    if (!complete) cancelCalibration()
    else super.onBackPressed()
  }

  override fun onDestroy() {
    isRunning = false
    mainHandler.removeCallbacks(promptTicker)
    try { cameraProvider?.unbindAll() } catch (_: Exception) { Unit }
    try { detector.close() } catch (_: Exception) { Unit }
    cameraExecutor.shutdown()
    if (!complete && prefs.getString("mode", "IDLE") == "CALIBRATING") {
      prefs.edit().putBoolean("active", false).putString("mode", "IDLE").apply()
    }
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    super.onDestroy()
  }

  private fun buildUi() {
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    previewView = PreviewView(this).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    guideView = CalibrationGuideView(this)
    root.addView(guideView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val top = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(20), dp(18), dp(20), dp(14))
      background = rounded(Color.argb(218, 4, 25, 20), 22)
    }
    top.addView(label("PARENT-ASSISTED SETUP", 11f, Color.rgb(248, 224, 170), true))
    top.addView(label("Keep the child about 1 metre away", 20f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, 0) })
    top.addView(label("Let the child look at the pulsing dot. Capture is automatic.", 12f, Color.rgb(202, 225, 216), false).apply { setPadding(0, dp(5), 0, 0) })
    root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
      setMargins(dp(14), dp(14), dp(14), 0)
    })

    val bottom = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(18), dp(15), dp(18), dp(14))
      background = rounded(Color.argb(236, 5, 31, 24), 24)
    }
    instructionView = label("Place the whole face inside the oval", 16f, Color.WHITE, true)
    progressView = label("Finding one face…", 12f, Color.rgb(129, 235, 193), true).apply { setPadding(0, dp(7), 0, 0) }
    promptView = label(prompts.first(), 12f, Color.rgb(248, 224, 170), false).apply { setPadding(0, dp(8), 0, 0) }
    val privacy = label("No photo or video is saved · processing stays on this phone", 10f, Color.rgb(159, 184, 174), false).apply { setPadding(0, dp(8), 0, 0) }
    val cancel = label("Cancel", 13f, Color.WHITE, true).apply {
      gravity = Gravity.CENTER
      setPadding(dp(20), dp(10), dp(20), dp(10))
      background = rounded(Color.rgb(32, 68, 57), 99)
      setOnClickListener { cancelCalibration() }
    }
    bottom.addView(instructionView)
    bottom.addView(progressView)
    bottom.addView(promptView)
    bottom.addView(privacy)
    bottom.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
      setMargins(dp(14), 0, dp(14), dp(16))
    })
    setContentView(root)
  }

  private fun startCamera() {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      try {
        val provider = future.get()
        cameraProvider = provider
        provider.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
        analysis.setAnalyzer(cameraExecutor) { analyze(it) }
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
      } catch (error: Exception) {
        finishWithError("Front camera could not start: ${error.message ?: error.javaClass.simpleName}")
      }
    }, { runnable -> mainHandler.post(runnable) })
  }

  @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
  private fun analyze(imageProxy: ImageProxy) {
    val now = SystemClock.elapsedRealtime()
    if (complete || now - lastAnalysisAt < 140L || !processing.compareAndSet(false, true)) {
      imageProxy.close()
      return
    }
    lastAnalysisAt = now
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
      processing.set(false)
      imageProxy.close()
      return
    }
    val rotation = imageProxy.imageInfo.rotationDegrees
    val uprightWidth = if (rotation == 90 || rotation == 270) imageProxy.height.toDouble() else imageProxy.width.toDouble()
    val uprightHeight = if (rotation == 90 || rotation == 270) imageProxy.width.toDouble() else imageProxy.height.toDouble()
    detector.process(InputImage.fromMediaImage(mediaImage, rotation))
      .addOnSuccessListener(cameraExecutor) { faces -> evaluate(faces, uprightWidth, uprightHeight) }
      .addOnFailureListener(cameraExecutor) { error -> postFeedback("Camera analysis paused: ${error.message ?: "try again"}", false) }
      .addOnCompleteListener(cameraExecutor) {
        processing.set(false)
        imageProxy.close()
      }
  }

  private fun evaluate(faces: List<Face>, imageWidth: Double, imageHeight: Double) {
    if (faces.size != 1) {
      resetStability()
      postFeedback(if (faces.isEmpty()) "Bring the child's face inside the oval" else "Keep only one face in the oval", false)
      return
    }
    val face = faces.first()
    if (abs(face.headEulerAngleX) > 20f || abs(face.headEulerAngleY) > 20f || abs(face.headEulerAngleZ) > 20f) {
      resetStability()
      postFeedback("Ask the child to look at the pulsing dot", false)
      return
    }
    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    if (leftEye == null || rightEye == null) {
      resetStability()
      postFeedback("Keep both eyes clearly visible", false)
      return
    }
    val box = face.boundingBox
    val left = box.left / imageWidth
    val right = box.right / imageWidth
    val top = box.top / imageHeight
    val bottom = box.bottom / imageHeight
    val width = box.width() / imageWidth
    val centerX = (left + right) / 2.0
    val centerY = (top + bottom) / 2.0
    if (width > 0.55) {
      resetStability()
      postFeedback("Move the phone farther—keep about 1 metre", false)
      return
    }
    if (width < 0.10) {
      resetStability()
      postFeedback("Move slightly closer while staying near 1 metre", false)
      return
    }
    if (left < 0.08 || right > 0.92 || top < 0.04 || bottom > 0.94 || centerX !in 0.22..0.78 || centerY !in 0.18..0.72) {
      resetStability()
      postFeedback("Center the whole face inside the oval", false)
      return
    }

    val eyeDistance = hypot((leftEye.x - rightEye.x).toDouble(), (leftEye.y - rightEye.y).toDouble())
    if (eyeDistance < 8.0) {
      resetStability()
      postFeedback("Move slightly closer for a clear face", false)
      return
    }
    val now = SystemClock.elapsedRealtime()
    val moved = lastEyeDistance > 0.0 && (
      abs(eyeDistance - lastEyeDistance) / lastEyeDistance > 0.13 ||
        abs(centerX - lastCenterX) > 0.055 || abs(centerY - lastCenterY) > 0.055)
    lastEyeDistance = eyeDistance
    lastCenterX = centerX
    lastCenterY = centerY
    if (moved) {
      stableSince = now
      samples.clear()
      prefs.edit().putInt("samplesCollected", 0).apply()
      postFeedback("Good—hold the phone steady", true)
      return
    }
    if (stableSince == 0L) stableSince = now
    if (now - stableSince < 550L) {
      postFeedback("Face found—hold steady", true)
      return
    }
    if (now - lastSampleAt < 300L) return
    lastSampleAt = now
    samples.add(eyeDistance)
    prefs.edit().putInt("samplesCollected", samples.size).apply()
    emit("calibration_progress", "Automatic capture ${samples.size}/$requiredSamples")
    postFeedback("Auto capture ${samples.size} of $requiredSamples", true)
    if (samples.size >= requiredSamples) finishCalibration()
  }

  private fun finishCalibration() {
    val reference = median(samples)
    val spread = ((samples.maxOrNull() ?: reference) - (samples.minOrNull() ?: reference)) / reference
    if (spread > 0.18) {
      samples.clear()
      stableSince = 0L
      prefs.edit().putInt("samplesCollected", 0).apply()
      postFeedback("Almost there—hold steady for one more moment", true)
      return
    }
    complete = true
    mainHandler.removeCallbacks(promptTicker)
    prefs.edit()
      .putFloat("calibration_reference_$profileKey", reference.toFloat())
      .putFloat("calibration_distance_$profileKey", knownDistanceCm.toFloat())
      .putLong("calibration_at_$profileKey", System.currentTimeMillis())
      .putBoolean("active", false)
      .putString("mode", "IDLE")
      .apply()
    mainHandler.post {
      cameraProvider?.unbindAll()
      guideView.setProgress(requiredSamples, requiredSamples, true)
      instructionView.text = "Calibration complete"
      progressView.text = "Child distance profile is ready"
      promptView.text = "No photo was saved."
      emit("calibration_complete", "Parent-assisted calibration complete at ${knownDistanceCm.toInt()} cm")
      mainHandler.postDelayed({ finish() }, 850L)
    }
  }

  private fun postFeedback(message: String, ready: Boolean) {
    mainHandler.post {
      if (complete || isFinishing) return@post
      instructionView.text = message
      progressView.text = if (samples.isEmpty()) "Face alignment ${if (ready) "ready" else "in progress"}" else "Automatic capture ${samples.size} of $requiredSamples"
      guideView.setProgress(samples.size, requiredSamples, ready)
    }
  }

  private fun resetStability() {
    stableSince = 0L
    lastEyeDistance = 0.0
    if (samples.isNotEmpty()) {
      samples.clear()
      prefs.edit().putInt("samplesCollected", 0).apply()
    }
  }

  private fun cancelCalibration() {
    prefs.edit().putBoolean("active", false).putString("mode", "IDLE").putInt("samplesCollected", 0).apply()
    emit("stopped", "Calibration cancelled")
    finish()
  }

  private fun finishWithError(message: String) {
    prefs.edit().putBoolean("active", false).putString("mode", "IDLE").putInt("samplesCollected", 0).apply()
    emit("camera_error", message)
    finish()
  }

  private fun emit(type: String, message: String) {
    sendBroadcast(Intent(FaceDistanceService.EVENT_ACTION).setPackage(packageName)
      .putExtra("eventType", type).putExtra("message", message))
  }

  private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    gravity = Gravity.CENTER
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
  }

  private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
  }

  private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
  }

  private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private class CalibrationGuideView(context: Context) : View(context) {
  private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(145, 0, 0, 0); style = Paint.Style.FILL }
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND }
  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 224, 170); style = Paint.Style.FILL }
  private var captured = 0
  private var required = 4
  private var ready = false

  fun setProgress(value: Int, total: Int, isReady: Boolean) {
    captured = value
    required = total.coerceAtLeast(1)
    ready = isReady
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val oval = RectF(width * 0.15f, height * 0.18f, width * 0.85f, height * 0.70f)
    val outside = Path().apply {
      fillType = Path.FillType.EVEN_ODD
      addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
      addOval(oval, Path.Direction.CW)
    }
    canvas.drawPath(outside, dimPaint)
    ringPaint.color = if (ready) Color.rgb(129, 235, 193) else Color.WHITE
    ringPaint.alpha = if (ready) 255 else 190
    canvas.drawOval(oval, ringPaint)
    ringPaint.color = Color.rgb(248, 224, 170)
    ringPaint.alpha = 255
    val inset = dp(7f)
    val progressOval = RectF(oval.left - inset, oval.top - inset, oval.right + inset, oval.bottom + inset)
    canvas.drawArc(progressOval, -90f, 360f * captured / required, false, ringPaint)

    val pulse = 0.78f + 0.22f * kotlin.math.sin(SystemClock.uptimeMillis() / 210.0).toFloat()
    val targetX = oval.centerX()
    val targetY = oval.top + oval.height() * 0.43f
    dotPaint.alpha = (120 + 135 * pulse).toInt()
    canvas.drawCircle(targetX, targetY, dp(15f) * pulse, dotPaint)
    dotPaint.color = Color.rgb(4, 40, 31)
    dotPaint.alpha = 255
    canvas.drawCircle(targetX, targetY, dp(6f), dotPaint)
    dotPaint.color = Color.rgb(248, 224, 170)
    postInvalidateDelayed(16L)
  }

  private fun dp(value: Float) = value * resources.displayMetrics.density
}
