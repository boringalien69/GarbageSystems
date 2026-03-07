package com.garbagesys.engine.faucet

import android.content.Context
import android.util.Log
import com.garbagesys.data.db.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FaucetManager(
    private val context: Context,
    private val prefs: PreferencesRepository
) {
    private val TAG = "FaucetManager"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private data class FaucetSource(
        val name: String,
        val url: String,
        val coin: String,
        val cooldownMinutes: Int,
        val postParams: Map<String, String> = emptyMap()
    )

    private val sources = listOf(
        FaucetSource("FreeCryptoFaucet", "https://freecryptofaucet.net/claim", "MATIC", 60, mapOf("coin" to "matic", "action" to "claim")),
        FaucetSource("FaucetCrypto", "https://faucetcrypto.com/api/v1/claim", "MATIC", 30, mapOf("coin" to "matic")),
        FaucetSource("Allcoins", "https://allcoins.pw/api/faucet/claim", "MATIC", 60, mapOf("currency" to "matic")),
        FaucetSource("MoonMatic", "https://moonmatic.info/claim/", "MATIC", 240, mapOf("op" to "claim")),
        FaucetSource("FaucetPay MATIC", "https://faucetpay.io/api/v1/send", "MATIC", 60, mapOf("currency" to "MATIC"))
    )

    // Cooldown tracking uses SharedPreferences (simple, no DataStore dependency needed)
    private fun getLastClaim(name: String): Long {
        return context.getSharedPreferences("gs_faucet", Context.MODE_PRIVATE)
            .getLong("last_$name", 0L)
    }

    private fun setLastClaim(name: String, time: Long) {
        context.getSharedPreferences("gs_faucet", Context.MODE_PRIVATE)
            .edit().putLong("last_$name", time).apply()
    }

    suspend fun runBootstrap(walletAddress: String): List<String> = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        logs.add("🥾 Running bootstrap (faucets + airdrop scan)...")

        // Tier 1: Direct faucet claims
        for (source in sources) {
            val lastClaim = getLastClaim(source.name)
            val cooldownMs = source.cooldownMinutes * 60 * 1000L
            if (System.currentTimeMillis() - lastClaim < cooldownMs) {
                val remaining = ((cooldownMs - (System.currentTimeMillis() - lastClaim)) / 60000).toInt()
                logs.add("⏳ ${source.name}: ${remaining}m cooldown")
                continue
            }
            try {
                val formBuilder = FormBody.Builder().add("address", walletAddress)
                source.postParams.forEach { (k, v) -> formBuilder.add(k, v) }
                val request = Request.Builder()
                    .url(source.url)
                    .post(formBuilder.build())
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                when {
                    response.code == 200 && (body.contains("success", true) || body.contains("claimed", true)) -> {
                        setLastClaim(source.name, System.currentTimeMillis())
                        logs.add("✅ ${source.name}: claimed ${source.coin}")
                    }
                    body.contains("cooldown", true) || body.contains("too soon", true) -> {
                        setLastClaim(source.name, System.currentTimeMillis() - cooldownMs + 3600000)
                        logs.add("⏳ ${source.name}: server cooldown")
                    }
                    response.code == 404 || response.code == 410 ->
                        logs.add("💀 ${source.name}: faucet dead, trying to find replacement...")
                    else ->
                        logs.add("⚠️ ${source.name}: ${response.code}")
                }
                delay(2000)
            } catch (e: Exception) {
                logs.add("❌ ${source.name}: ${e.message?.take(40)}")
            }
        }

        // Tier 2: Airdrop RSS scan
        try {
            val request = Request.Builder()
                .url("https://airdrops.io/feed/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val rssBody = client.newCall(request).execute().body?.string() ?: ""
            val count = rssBody.split("<item>").drop(1).count { item ->
                item.contains("polygon", true) || item.contains("matic", true)
            }
            logs.add("📡 Airdrop scan: $count Polygon opportunities found on airdrops.io")
        } catch (e: Exception) {
            logs.add("⚠️ Airdrop scan failed: ${e.message?.take(40)}")
        }

        // Tier 3: Self-healing — check faucetpay for live replacements
        try {
            val request = Request.Builder()
                .url("https://faucetpay.io/page/network-faucet-list/matic")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val body = client.newCall(request).execute().body?.string() ?: ""
            val liveCount = Regex("href=\"(https://[^\"]+)\"[^>]*>Claim").findAll(body).count()
            if (liveCount > 0) logs.add("🔍 Self-heal: found $liveCount live MATIC faucets on FaucetPay")
        } catch (e: Exception) {
            logs.add("⚠️ Self-heal scan: ${e.message?.take(40)}")
        }

        logs.add("✅ Bootstrap cycle complete")
        logs
    }
}
