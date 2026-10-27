package com.eyesaver.deviceguard

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class BreakOverlayService : Service() {
  companion object {
    private const val ACTION_SHOW = "com.eyesaver.deviceguard.SHOW_BREAK_OVERLAY"
    private const val ACTION_DISMISS = "com.eyesaver.deviceguard.DISMISS_BREAK_OVERLAY"
    private const val PREFS = "eye_saver_break_overlay"
    @Volatile private var instance: BreakOverlayService? = null

    fun showIntent(context: Context, message: String?, timestampMs: Long) =
      Intent(context, BreakOverlayService::class.java)
        .setAction(ACTION_SHOW)
        .putExtra("message", message)
        .putExtra("timestampMs", timestampMs)

    fun dismiss(context: Context) {
      instance?.dismissOverlay() ?: try {
        context.startService(Intent(context, BreakOverlayService::class.java).setAction(ACTION_DISMISS))
      } catch (_: Exception) { Unit }
    }

  }

  private val handler = Handler(Looper.getMainLooper())
  private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
  private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
  private var overlay: View? = null
  private var active = false
  private var message = "Time for an eye and screen break"
  private var timestampMs = 0L

  override fun onCreate() {
    super.onCreate()
    instance = this
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_DISMISS -> { dismissOverlay(); return START_NOT_STICKY }
      ACTION_SHOW -> {
        message = intent.getStringExtra("message") ?: "Time for an eye and screen break"
        timestampMs = intent.getLongExtra("timestampMs", System.currentTimeMillis())
        prefs.edit().putBoolean("active", true).putString("message", message).putLong("timestampMs", timestampMs).apply()
      }
      else -> {
        if (!prefs.getBoolean("active", false)) { stopSelf(); return START_NOT_STICKY }
        message = prefs.getString("message", null) ?: "Time for an eye and screen break"
        timestampMs = prefs.getLong("timestampMs", System.currentTimeMillis())
      }
    }
    active = true
    showOverlay()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    removeOverlay()
    if (instance === this) instance = null
    super.onDestroy()
  }

  private fun showOverlay() {
    if (!active || overlay != null || !Settings.canDrawOverlays(this)) return
    val view = buildOverlay()
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      type,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_FULLSCREEN,
      PixelFormat.OPAQUE
    ).apply {
      gravity = Gravity.CENTER
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    try { windowManager.addView(view, params); overlay = view }
    catch (_: Exception) { overlay = null }
  }

  private fun buildOverlay(): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setPadding(dp(32), dp(48), dp(32), dp(48))
    setBackgroundColor(Color.BLACK)
    addView(TextView(context).apply {
      text = message
      setTextColor(Color.WHITE)
      textSize = 30f
      gravity = Gravity.CENTER
    })
    addView(TextView(context).apply {
      text = "Limit reached at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs))}"
      setTextColor(Color.LTGRAY)
      textSize = 16f
      gravity = Gravity.CENTER
      setPadding(0, dp(24), 0, dp(40))
    })
    val status = TextView(context).apply {
      setTextColor(Color.rgb(255, 155, 145))
      textSize = 14f
      gravity = Gravity.CENTER
      setPadding(0, dp(12), 0, dp(8))
    }
    val pinInput = EditText(context).apply {
      hint = "Parent PIN"
      setHintTextColor(Color.GRAY)
      setTextColor(Color.WHITE)
      textSize = 20f
      gravity = Gravity.CENTER
      inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
      maxLines = 1
    }
    addView(pinInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
    addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    addView(Button(context).apply {
      text = "Verify parent PIN and end break"
      setOnClickListener { verifyParentPin(pinInput, status, this) }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
  }

  private fun verifyParentPin(input: EditText, status: TextView, button: Button) {
    val pin = input.text.toString()
    if (!pin.matches(Regex("^[0-9]{4,12}$"))) {
      status.text = "Enter the 4-12 digit Eye Saver parent PIN"
      return
    }
    button.isEnabled = false
    status.text = "Checking..."
    Thread {
      try {
        val uri = android.net.Uri.parse("content://$packageName.eye_saver_parent_auth")
        val result = contentResolver.call(uri, "verifyPin", pin, null)
          ?: error("Parent PIN verifier is unavailable")
        val success = result.getBoolean("success", false)
        val remaining = result.getInt("remainingAttempts", 0)
        val lockedUntil = result.getLong("lockedUntilMs", 0L)
        handler.post {
          if (!active) return@post
          if (success) endChildSession()
          else {
            input.text.clear()
            status.text = if (lockedUntil > System.currentTimeMillis())
              "Too many attempts. Try again in ${((lockedUntil - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)} seconds."
            else "Incorrect PIN. $remaining attempts remaining."
            button.isEnabled = true
          }
        }
      } catch (error: Exception) {
        handler.post {
          if (!active) return@post
          status.text = error.message ?: "Could not verify the parent PIN"
          button.isEnabled = true
        }
      }
    }.start()
  }

  private fun endChildSession() {
    try {
      startService(Intent()
        .setComponent(ComponentName(packageName, "com.eyesaver.childsession.ChildSessionService"))
        .setAction("com.eyesaver.childsession.STOP"))
    } catch (_: Exception) { Unit }
    dismissOverlay()
  }

  private fun dismissOverlay() {
    active = false
    prefs.edit().clear().apply()
    handler.removeCallbacksAndMessages(null)
    removeOverlay()
    stopSelf()
  }

  private fun removeOverlay() {
    overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) { Unit } }
    overlay = null
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
