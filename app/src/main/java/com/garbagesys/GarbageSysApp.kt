package com.garbagesys

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.*
import com.garbagesys.services.StrategyWorker
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit

class GarbageSysApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        setupBouncyCastle()
        createNotificationChannels()
        scheduleStrategyWorker()
    }

    private fun setupBouncyCastle() {
        // Remove old provider if exists, add fresh one
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ENGINE,
                    "GarbageSys Engine",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Strategy engine status" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Trade Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Trade executions and earnings alerts" }
            )
        }
    }

    private fun scheduleStrategyWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<StrategyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .addTag(TAG_STRATEGY_WORKER)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TAG_STRATEGY_WORKER,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        lateinit var instance: GarbageSysApp
            private set

        const val CHANNEL_ENGINE = "engine_channel"
        const val CHANNEL_ALERTS = "alerts_channel"
        const val TAG_STRATEGY_WORKER = "garbagesys_strategy_worker"
        const val POLYGON_RPC = "https://polygon-rpc.com"
        const val USDC_CONTRACT = "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"
        const val POLYMARKET_CLOB_BASE = "https://clob.polymarket.com"
        const val POLYMARKET_GAMMA_BASE = "https://gamma-api.polymarket.com"
        const val NOAA_API_BASE = "https://api.weather.gov"
        const val COINGECKO_BASE = "https://api.coingecko.com/api/v3"
        const val POLYGON_SCAN_API = "https://api.polygonscan.com/api"
    }
}
