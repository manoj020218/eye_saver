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

/**
 * Best-effort protection for a personal phone. Android does not let a regular
 * app disable Force Stop, so this monitor covers the Settings/install surfaces
 * before the child can reach them. Device Owner remains the tamper-proof mode.
 */
class TamperGuardService : Service() {
  companion object {
    const val ACTION_START = "com.eyesaver.deviceguard.START_TAMPER_GUARD"
    const val ACTION_STOP = "com.eyesaver.deviceguard.STOP_TAMPER_GUARD"
    const val PREFS = "eye_saver_tamper_guard"
    const val KEY_ENABLED = "enabled"
    private const val CHANNEL = "eye_saver_tamper_guard"
    private const val NOTIFICATION_ID = 8152

    fun isEnabled(context: Context): Boolean =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun isActive(context: Context): Boolean =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("active", false)

    fun setEnabled(context: Context, enabled: Boolean) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
      if (enabled) startIfAllowed(context) else stop(context)
    }

    fun startIfAllowed(context: Context) {
      val sessionState = context.getSharedPreferences("eye_saver_child_session", Context.MODE_PRIVATE)
        .getString("state", "IDLE")
      if (!isEnabled(context) || sessionState != "ACTIVE" ||
        !Settings.canDrawOverlays(context) || !hasUsageAccess(context)) return
      val intent = Intent(context, TamperGuardService::class.java).setAction(ACTION_START)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
      else context.startService(intent)
    }

    fun stop(context: Context) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", false).apply()
      RestrictedAppOverlayService.dismiss(context, RestrictedAppOverlayService.REASON_TAMPER_PROTECTION)
      context.stopService(Intent(context, TamperGuardService::class.java))
    }

    private fun hasUsageAccess(context: Context): Boolean {
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
  private val protectedPackages by lazy {
    val settingsPackage = packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    setOfNotNull(
      settingsPackage,
      "com.android.settings",
      "com.miui.securitycenter",
      "com.android.packageinstaller",
      "com.google.android.packageinstaller",
      "com.miui.packageinstaller",
      "com.android.permissioncontroller",
      "com.google.android.permissioncontroller",
      "com.android.vending",
      "com.xiaomi.market",
      "com.sec.android.app.samsungapps"
    )
  }
  private var running = false
  private var lastQueryMs = 0L
  private var foregroundPackage: String? = null
  private var overlaidPackage: String? = null
  private val pollRunnable = object : Runnable {
    override fun run() {
      if (!running) return
      pollForegroundApp()
      handler.postDelayed(this, 2_000L)
    }
  }

  override fun onCreate() {
    super.onCreate()
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopMonitor()
      return START_NOT_STICKY
    }
    val sessionState = getSharedPreferences("eye_saver_child_session", Context.MODE_PRIVATE)
      .getString("state", "IDLE")
    if (!isEnabled(this) || sessionState != "ACTIVE" ||
      !Settings.canDrawOverlays(this) || !RestrictedAppMonitorService.hasUsageAccess(this)) {
      stopMonitor()
      return START_NOT_STICKY
    }
    prefs.edit().putBoolean("active", true).apply()
    startForeground(NOTIFICATION_ID, notification())
    running = true
    handler.removeCallbacks(pollRunnable)
    pollRunnable.run()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    running = false
    handler.removeCallbacksAndMessages(null)
    RestrictedAppOverlayService.dismiss(this, RestrictedAppOverlayService.REASON_TAMPER_PROTECTION)
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
    if (current != null && current != packageName && protectedPackages.contains(current)) {
      if (overlaidPackage != current) {
        try {
          startService(RestrictedAppOverlayService.showIntent(
            this,
            current,
            appLabel(current),
            RestrictedAppOverlayService.REASON_TAMPER_PROTECTION
          ))
        } catch (_: Exception) { Unit }
        overlaidPackage = current
      }
    } else if (overlaidPackage != null) {
      RestrictedAppOverlayService.dismiss(this, RestrictedAppOverlayService.REASON_TAMPER_PROTECTION)
      overlaidPackage = null
    }
  }

  @Suppress("DEPRECATION")
  private fun appLabel(targetPackage: String): String = try {
    val info = packageManager.getApplicationInfo(targetPackage, 0)
    packageManager.getApplicationLabel(info).toString()
  } catch (_: Exception) {
    when (targetPackage) {
      "com.android.vending" -> "Play Store"
      else -> "System settings"
    }
  }

  private fun stopMonitor() {
    running = false
    prefs.edit().putBoolean("active", false).apply()
    handler.removeCallbacksAndMessages(null)
    RestrictedAppOverlayService.dismiss(this, RestrictedAppOverlayService.REASON_TAMPER_PROTECTION)
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
    else @Suppress("DEPRECATION") stopForeground(true)
    stopSelf()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Child mode protection", NotificationManager.IMPORTANCE_LOW)
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
      .setContentTitle("Child mode protection is active")
      .setContentText("Settings and app installation require the parent to end child mode")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(pending)
      .build()
  }
}
