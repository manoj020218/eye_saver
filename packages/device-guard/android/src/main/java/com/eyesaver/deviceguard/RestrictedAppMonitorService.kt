package com.eyesaver.deviceguard

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

class RestrictedAppMonitorService : Service() {
  companion object {
    const val ACTION_START = "com.eyesaver.deviceguard.START_SELECTIVE_GUARD"
    const val ACTION_STOP = "com.eyesaver.deviceguard.STOP_SELECTIVE_GUARD"
    const val PREFS = "eye_saver_selective_guard"
    const val KEY_PACKAGES = "restrictedPackages"
    private const val CHANNEL = "eye_saver_selective_guard"
    private const val NOTIFICATION_ID = 8151

    fun startIfConfigured(context: Context) {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      if (prefs.getStringSet(KEY_PACKAGES, emptySet()).isNullOrEmpty() ||
        !Settings.canDrawOverlays(context) || !hasUsageAccess(context)) return
      val intent = Intent(context, RestrictedAppMonitorService::class.java).setAction(ACTION_START)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
      else context.startService(intent)
    }

    fun stop(context: Context) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("monitorActive", false).apply()
      RestrictedAppOverlayService.dismiss(context, RestrictedAppOverlayService.REASON_RESTRICTED_APP)
      context.stopService(Intent(context, RestrictedAppMonitorService::class.java))
    }

    fun hasUsageAccess(context: Context): Boolean {
      val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
      val mode = if (Build.VERSION.SDK_INT >= 29)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName)
      else @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName)
      return mode == AppOpsManager.MODE_ALLOWED
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
  private val usage by lazy { getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager }
  private var running = false
  private var intervalMs = 1_500L
  private var lastQueryMs = 0L
  private var foregroundPackage: String? = null
  private var overlaidPackage: String? = null
  private val pollRunnable = object : Runnable {
    override fun run() {
      if (!running) return
      pollForegroundApp()
      handler.postDelayed(this, intervalMs)
    }
  }

  override fun onCreate() {
    super.onCreate()
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> { stopMonitor(); return START_NOT_STICKY }
      ACTION_START -> {
        intervalMs = intent.getLongExtra("intervalMs", prefs.getLong("intervalMs", 1_500L)).coerceIn(750L, 10_000L)
        prefs.edit().putBoolean("monitorActive", true).putLong("intervalMs", intervalMs).apply()
      }
      else -> {
        if (!prefs.getBoolean("monitorActive", false)) { stopSelf(); return START_NOT_STICKY }
        intervalMs = prefs.getLong("intervalMs", 1_500L).coerceIn(750L, 10_000L)
      }
    }
    if (prefs.getStringSet(KEY_PACKAGES, emptySet()).isNullOrEmpty() ||
      !Settings.canDrawOverlays(this) || !hasUsageAccess(this)) {
      stopMonitor()
      return START_NOT_STICKY
    }
    startForeground(NOTIFICATION_ID, notification())
    running = true
    handler.removeCallbacks(pollRunnable)
    pollRunnable.run()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    RestrictedAppOverlayService.dismiss(this, RestrictedAppOverlayService.REASON_RESTRICTED_APP)
    super.onDestroy()
  }

  private fun pollForegroundApp() {
    val now = System.currentTimeMillis()
    val begin = if (lastQueryMs == 0L) now - 15_000L else (lastQueryMs - 250L).coerceAtLeast(now - 15_000L)
    try {
      val events = usage.queryEvents(begin, now)
      val event = UsageEvents.Event()
      var newestAt = Long.MIN_VALUE
      var newestPackage: String? = null
      while (events != null && events.hasNextEvent()) {
        events.getNextEvent(event)
        if ((event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) && event.timeStamp >= newestAt) {
          newestAt = event.timeStamp
          newestPackage = event.packageName
        }
      }
      if (!newestPackage.isNullOrBlank()) foregroundPackage = newestPackage
      lastQueryMs = now
    } catch (_: Exception) { Unit }

    val current = foregroundPackage
    val restricted = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
    if (current != null && current != packageName && restricted.contains(current)) {
      if (overlaidPackage != current) {
        val label = appLabel(current)
        try { startService(RestrictedAppOverlayService.showIntent(this, current, label)) }
        catch (_: Exception) { Unit }
        overlaidPackage = current
      }
    } else if (overlaidPackage != null) {
      RestrictedAppOverlayService.dismiss(this, RestrictedAppOverlayService.REASON_RESTRICTED_APP)
      overlaidPackage = null
    }
  }

  @Suppress("DEPRECATION")
  private fun appLabel(targetPackage: String): String = try {
    val info = packageManager.getApplicationInfo(targetPackage, 0)
    packageManager.getApplicationLabel(info).toString()
  } catch (_: Exception) { targetPackage }

  private fun stopMonitor() {
    running = false
    prefs.edit().putBoolean("monitorActive", false).apply()
    handler.removeCallbacksAndMessages(null)
    RestrictedAppOverlayService.dismiss(this, RestrictedAppOverlayService.REASON_RESTRICTED_APP)
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
    else @Suppress("DEPRECATION") stopForeground(true)
    stopSelf()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Selected app guard", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  private fun notification(): Notification {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val pending = launch?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
      else @Suppress("DEPRECATION") Notification.Builder(this)
    return builder
      .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_lock_lock)
      .setContentTitle("Screen-time break is active")
      .setContentText("Parent-selected apps are blocked until the parent ends child mode")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(pending)
      .build()
  }
}
