package com.garbagesys.services

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.garbagesys.GarbageSysApp
import com.garbagesys.R
import com.garbagesys.engine.agent.AgentOrchestrator
import kotlinx.coroutines.runBlocking

/**
 * StrategyWorker — runs via WorkManager every 15 minutes.
 * This is the primary background execution mechanism.
 * WorkManager handles battery/doze mode automatically.
 */
class StrategyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val orchestrator = AgentOrchestrator(applicationContext)
            orchestrator.runCycle()
            Result.success()
        } catch (e: Exception) {
            // Retry on failure with backoff
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/**
 * Foreground Service — optional, keeps engine alive when user wants continuous operation.
 * Started from settings screen. Shows persistent notification.
 */
class StrategyForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, GarbageSysApp.CHANNEL_ENGINE)
            .setContentTitle("GarbageSys Active")
            .setContentText("Scanning markets for opportunities...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }
}

/**
 * BootReceiver — restarts WorkManager scheduling after device reboot.
 * Ensures the engine survives reboots without user interaction.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Re-schedule the strategy worker
            (context.applicationContext as? GarbageSysApp)?.let {
                // Application.onCreate() will reschedule, but we re-trigger manually
                WorkManager.getInstance(context).cancelAllWorkByTag(GarbageSysApp.TAG_STRATEGY_WORKER)
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<StrategyWorker>(
                    15, java.util.concurrent.TimeUnit.MINUTES
                ).setConstraints(constraints)
                    .addTag(GarbageSysApp.TAG_STRATEGY_WORKER)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    GarbageSysApp.TAG_STRATEGY_WORKER,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}

/**
 * FaucetAccessibilityService — allows GarbageSys to interact with browser
 * for faucet claiming via UI automation. User grants once during setup.
 * Only activates when in faucet claiming mode.
 */
class FaucetAccessibilityService : android.accessibilityservice.AccessibilityService() {
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Handled by FaucetManager when needed
    }
    override fun onInterrupt() {}
}
