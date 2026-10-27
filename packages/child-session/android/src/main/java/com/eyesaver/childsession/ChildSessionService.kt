package com.eyesaver.childsession

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.util.UUID
import kotlin.math.max

class ChildSessionService : Service() {
  companion object {
    const val ACTION_START = "com.eyesaver.childsession.START"
    const val ACTION_PAUSE = "com.eyesaver.childsession.PAUSE"
    const val ACTION_RESUME = "com.eyesaver.childsession.RESUME"
    const val ACTION_STOP = "com.eyesaver.childsession.STOP"
    const val ACTION_RESTORE = "com.eyesaver.childsession.RESTORE"
    const val EVENT_ACTION = "com.eyesaver.childsession.EVENT"
    private const val PREFS = "eye_saver_child_session"
    private const val ACTIVE_CHANNEL = "eye_saver_child_session_active"
    private const val ALERT_CHANNEL = "eye_saver_child_session_alert"
    private const val ACTIVE_NOTIFICATION = 8141
    private const val ALERT_NOTIFICATION = 8142

    fun shouldRestore(context: Context): Boolean {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val state = prefs.getString("state", "IDLE")
      return state == "ACTIVE" || (state == "EXPIRED" && prefs.getBoolean("blockOnExpire", false))
    }

    fun stateMap(context: Context, eventType: String? = null): WritableMap {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val state = prefs.getString("state", "IDLE") ?: "IDLE"
      var used = prefs.getLong("usedMs", 0L)
      val screenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
      if (state == "ACTIVE" && screenOn) {
        val since = prefs.getLong("activeSinceElapsed", 0L)
        if (since > 0L) used += max(0L, SystemClock.elapsedRealtime() - since)
      }
      val duration = prefs.getLong("durationMs", 0L)
      return Arguments.createMap().apply {
        putString("eventType", eventType ?: "state")
        putString("state", state.lowercase())
        putString("sessionId", prefs.getString("sessionId", null))
        putDouble("durationMs", duration.toDouble())
        putDouble("usedMs", used.coerceAtMost(duration).toDouble())
        putDouble("remainingMs", max(0L, duration - used).toDouble())
        putDouble("startedAtMs", prefs.getLong("startedAtMs", 0L).toDouble())
        putDouble("timestampMs", System.currentTimeMillis().toDouble())
        putBoolean("isScreenOn", screenOn)
        putBoolean("blockOnExpire", prefs.getBoolean("blockOnExpire", false))
      }
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
  private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
  private var screenReceiverRegistered = false
  private val warningRunnable = Runnable { emitWarningIfNeeded() }
  private val expiryRunnable = Runnable { expireIfNeeded() }
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        Intent.ACTION_SCREEN_OFF -> checkpoint("screen_off")
        Intent.ACTION_SCREEN_ON -> resumeClock("screen_on")
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    createChannels()
    val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
    else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
    screenReceiverRegistered = true
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> startNew(
        intent.getLongExtra("durationMs", 20 * 60_000L),
        intent.getLongExtra("warningBeforeMs", 2 * 60_000L),
        intent.getBooleanExtra("blockOnExpire", false)
      )
      ACTION_PAUSE -> pauseSession()
      ACTION_RESUME -> resumeSession()
      ACTION_STOP -> stopSession()
      ACTION_RESTORE -> restoreSession()
      else -> restoreSession()
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    if (screenReceiverRegistered) try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
    super.onDestroy()
  }

  private fun startNew(durationMs: Long, warningBeforeMs: Long, blockOnExpire: Boolean) {
    prefs.edit()
      .putString("state", "ACTIVE")
      .putString("sessionId", UUID.randomUUID().toString())
      .putLong("durationMs", durationMs)
      .putLong("warningBeforeMs", warningBeforeMs)
      .putLong("usedMs", 0L)
      .putLong("activeSinceElapsed", if (power.isInteractive) SystemClock.elapsedRealtime() else 0L)
      .putLong("startedAtMs", System.currentTimeMillis())
      .putBoolean("warned", false)
      .putBoolean("blockOnExpire", blockOnExpire)
      .apply()
    startForeground(ACTIVE_NOTIFICATION, activeNotification())
    emit("started")
    schedule()
  }

  private fun restoreSession() {
    if (prefs.getString("state", "IDLE") == "ACTIVE") {
      checkpoint(null)
      startForeground(ACTIVE_NOTIFICATION, activeNotification())
      if (power.isInteractive) prefs.edit().putLong("activeSinceElapsed", SystemClock.elapsedRealtime()).apply()
      schedule()
    } else if (prefs.getString("state", "IDLE") == "EXPIRED" && prefs.getBoolean("blockOnExpire", false)) {
      startForeground(ACTIVE_NOTIFICATION, blockingNotification())
      emit("expired")
    } else stopSelf()
  }

  private fun pauseSession() {
    if (prefs.getString("state", "IDLE") != "ACTIVE") return
    checkpoint(null)
    prefs.edit().putString("state", "PAUSED").putLong("activeSinceElapsed", 0L).apply()
    handler.removeCallbacksAndMessages(null)
    getSystemService(NotificationManager::class.java).notify(ACTIVE_NOTIFICATION, activeNotification())
    emit("paused")
  }

  private fun resumeSession() {
    if (prefs.getString("state", "IDLE") != "PAUSED") return
    prefs.edit().putString("state", "ACTIVE")
      .putLong("activeSinceElapsed", if (power.isInteractive) SystemClock.elapsedRealtime() else 0L).apply()
    startForeground(ACTIVE_NOTIFICATION, activeNotification())
    emit("resumed")
    schedule()
  }

  private fun stopSession() {
    checkpoint(null)
    prefs.edit().putString("state", "IDLE").putLong("activeSinceElapsed", 0L).putBoolean("blockOnExpire", false).apply()
    handler.removeCallbacksAndMessages(null)
    emit("stopped")
    stopForegroundCompat()
    stopSelf()
  }

  private fun checkpoint(event: String?) {
    if (prefs.getString("state", "IDLE") != "ACTIVE") return
    val since = prefs.getLong("activeSinceElapsed", 0L)
    if (since > 0L) {
      val used = prefs.getLong("usedMs", 0L) + max(0L, SystemClock.elapsedRealtime() - since)
      prefs.edit().putLong("usedMs", used).putLong("activeSinceElapsed", 0L).apply()
    }
    handler.removeCallbacksAndMessages(null)
    if (event != null) emit(event)
  }

  private fun resumeClock(event: String?) {
    if (prefs.getString("state", "IDLE") != "ACTIVE") return
    prefs.edit().putLong("activeSinceElapsed", SystemClock.elapsedRealtime()).apply()
    if (event != null) emit(event)
    schedule()
  }

  private fun currentUsed(): Long {
    var used = prefs.getLong("usedMs", 0L)
    val since = prefs.getLong("activeSinceElapsed", 0L)
    if (since > 0L) used += max(0L, SystemClock.elapsedRealtime() - since)
    return used
  }

  private fun schedule() {
    handler.removeCallbacks(warningRunnable)
    handler.removeCallbacks(expiryRunnable)
    if (prefs.getString("state", "IDLE") != "ACTIVE" || !power.isInteractive) return
    val duration = prefs.getLong("durationMs", 0L)
    val remaining = duration - currentUsed()
    if (remaining <= 0L) { expireIfNeeded(); return }
    val warningBefore = prefs.getLong("warningBeforeMs", 0L)
    if (!prefs.getBoolean("warned", false) && warningBefore > 0L) {
      val warningDelay = remaining - warningBefore
      if (warningDelay <= 0L) emitWarningIfNeeded() else handler.postDelayed(warningRunnable, warningDelay)
    }
    handler.postDelayed(expiryRunnable, remaining)
  }

  private fun emitWarningIfNeeded() {
    if (prefs.getString("state", "IDLE") != "ACTIVE" || prefs.getBoolean("warned", false)) return
    prefs.edit().putBoolean("warned", true).apply()
    emit("warning")
    getSystemService(NotificationManager::class.java).notify(ACTIVE_NOTIFICATION, activeNotification())
  }

  private fun expireIfNeeded() {
    if (prefs.getString("state", "IDLE") != "ACTIVE") return
    if (currentUsed() < prefs.getLong("durationMs", 0L)) { schedule(); return }
    checkpoint(null)
    prefs.edit().putString("state", "EXPIRED").putLong("activeSinceElapsed", 0L).apply()
    emit("expired")
    getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION, alertNotification())
    if (prefs.getBoolean("blockOnExpire", false)) {
      startForeground(ACTIVE_NOTIFICATION, blockingNotification())
    } else {
      stopForegroundCompat()
      stopSelf()
    }
  }

  private fun emit(type: String) {
    sendBroadcast(Intent(EVENT_ACTION).setPackage(packageName)
      .putExtra("eventType", type)
      .putExtra("timestampMs", System.currentTimeMillis()))
  }

  private fun createChannels() {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(ACTIVE_CHANNEL, "Child session", NotificationManager.IMPORTANCE_LOW))
    manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Break reminders", NotificationManager.IMPORTANCE_HIGH))
  }

  private fun activeNotification(): Notification {
    val remainingMinutes = (stateMap(this).getDouble("remainingMs") / 60_000.0).toInt().coerceAtLeast(0)
    return notificationBuilder(ACTIVE_CHANNEL)
      .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle("Child mode is active")
      .setContentText("About $remainingMinutes minutes of screen time remaining")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(appPendingIntent())
      .build()
  }

  private fun alertNotification(): Notification =
    notificationBuilder(ALERT_CHANNEL)
      .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_alert)
      .setContentTitle("Screen-time break")
      .setContentText("The child session limit has been reached")
      .setAutoCancel(true)
      .setContentIntent(appPendingIntent())
      .build()

  private fun blockingNotification(): Notification =
    notificationBuilder(ACTIVE_CHANNEL)
      .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_alert)
      .setContentTitle("Screen-time break is active")
      .setContentText("Parent PIN is required to end child mode")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(appPendingIntent())
      .build()

  private fun notificationBuilder(channelId: String): Notification.Builder =
    if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
    else @Suppress("DEPRECATION") Notification.Builder(this)

  private fun appPendingIntent(): PendingIntent? {
    val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
    else @Suppress("DEPRECATION") stopForeground(true)
  }
}
