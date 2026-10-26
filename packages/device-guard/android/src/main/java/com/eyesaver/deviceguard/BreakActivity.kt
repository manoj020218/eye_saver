package com.eyesaver.deviceguard

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class BreakActivity : Activity() {
  companion object { const val ACTION_DISMISS = "com.eyesaver.deviceguard.DISMISS_BREAK" }
  private var registered = false
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == ACTION_DISMISS) finish() }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    val message = intent.getStringExtra("message") ?: "Screen-time break"
    val triggeredAt = intent.getLongExtra("triggeredAtMs", System.currentTimeMillis())
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(48, 48, 48, 48)
      setBackgroundColor(Color.BLACK)
    }
    layout.addView(TextView(this).apply {
      text = message
      setTextColor(Color.WHITE)
      textSize = 30f
      gravity = Gravity.CENTER
    })
    layout.addView(TextView(this).apply {
      text = "Limit reached at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(triggeredAt))}"
      setTextColor(Color.LTGRAY)
      textSize = 16f
      gravity = Gravity.CENTER
      setPadding(0, 28, 0, 48)
    })
    layout.addView(Button(this).apply {
      text = "Parent unlock"
      setOnClickListener {
        packageManager.getLaunchIntentForPackage(packageName)?.also {
          it.putExtra("eyeSaverParentUnlock", true)
          it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
          startActivity(it)
        }
      }
    })
    setContentView(layout)
    val filter = IntentFilter(ACTION_DISMISS)
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    registered = true
  }

  @Deprecated("Back is intentionally disabled during a break")
  override fun onBackPressed() = Unit

  override fun onDestroy() {
    if (registered) try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
    super.onDestroy()
  }
}
