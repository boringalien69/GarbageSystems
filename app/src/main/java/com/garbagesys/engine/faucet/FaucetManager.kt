package com.garbagesys.engine.faucet

import android.content.Context
import android.util.Log
import com.garbagesys.data.db.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaucetManager(
    private val context: Context,
    private val prefs: PreferencesRepository
) {
    private val TAG = "FaucetManager"
    private val bootstrapManager = BootstrapManager(context, prefs)

    suspend fun runBootstrap(walletAddress: String): List<String> = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        logs.add("🥾 Starting 3-tier bootstrap...")
        try {
            val results = bootstrapManager.runFullBootstrap(walletAddress)
            for (result in results) {
                logs.add("── Tier ${result.tier} ──")
                logs.addAll(result.messages)
                if (result.totalClaimed > 0) logs.add("💰 Tier ${result.tier} claimed: ${result.totalClaimed}")
            }
        } catch (e: Exception) {
            logs.add("❌ Bootstrap error: ${e.message}")
            Log.e(TAG, "Bootstrap error", e)
        }
        logs.add("✅ Bootstrap cycle complete")
        logs
    }

    suspend fun getBootstrapStatus(): String = withContext(Dispatchers.IO) {
        val discovered = prefs.getDiscoveredFaucets()
        val pending = prefs.getPendingAirdrops()
        "Active faucets: 5 (+${discovered.size} discovered)\nPending airdrops: ${pending.size}"
    }
}
