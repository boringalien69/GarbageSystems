package com.garbagesys.engine.faucet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * FaucetManager — 3-tier autonomous bootstrap
 * Tier 1: Direct HTTP faucet claims (5 sources, cooldown tracked)
 * Tier 2: Airdrop RSS scanner (airdrops.io Polygon feed)
 * Tier 3: Self-healing discovery (finds replacement faucets when one dies)
 */
class FaucetManager(private val context: Context) {

    private val TAG = "FaucetManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class FaucetResult(
        val source: String,
        val success: Boolean,
        val amount: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class FaucetSource(
        val name: String,
        val url: String,
        val paramName: String,
        val expectedAmount: String,
        val cooldownMs: Long,
        val extraParams: Map<String, String> = emptyMap()
    )

    // Cooldown tracking (in-memory + SharedPrefs for persistence across restarts)
    private fun getLastClaim(name: String): Long =
        context.getSharedPreferences("gs_faucet_cd", Context.MODE_PRIVATE)
            .getLong(name, 0L)

    private fun setLastClaim(name: String) =
        context.getSharedPreferences("gs_faucet_cd", Context.MODE_PRIVATE)
            .edit().putLong(name, System.currentTimeMillis()).apply()

    // ── Tier 1: Direct faucet sources ────────────────────────────────────────
    private val faucetSources = listOf(
        FaucetSource(
            name = "Polygon Faucet (stakely.io)",
            url = "https://stakely.io/faucet/polygon-matic",
            paramName = "address",
            expectedAmount = "0.001 MATIC",
            cooldownMs = 24 * 60 * 60 * 1000L
        ),
        FaucetSource(
            name = "Polygon Faucet (faucet.polygon.technology)",
            url = "https://faucet.polygon.technology/",
            paramName = "address",
            expectedAmount = "0.001 MATIC",
            cooldownMs = 24 * 60 * 60 * 1000L
        ),
        FaucetSource(
            name = "FreeCryptoFaucet MATIC",
            url = "https://freecryptofaucet.net/claim",
            paramName = "address",
            expectedAmount = "0.001 MATIC",
            cooldownMs = 60 * 60 * 1000L,
            extraParams = mapOf("coin" to "matic", "action" to "claim")
        ),
        FaucetSource(
            name = "Allcoins MATIC",
            url = "https://allcoins.pw/api/faucet/claim",
            paramName = "address",
            expectedAmount = "0.001 MATIC",
            cooldownMs = 60 * 60 * 1000L,
            extraParams = mapOf("currency" to "matic")
        ),
        FaucetSource(
            name = "Cointiply (micro earnings)",
            url = "https://cointiply.com/faucet",
            paramName = "address",
            expectedAmount = "micro BTC",
            cooldownMs = 60 * 60 * 1000L
        )
    )

    suspend fun claimAll(walletAddress: String): List<FaucetResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FaucetResult>()
        val now = System.currentTimeMillis()

        // Tier 1: Faucet claims
        for (faucet in faucetSources) {
            val lastClaim = getLastClaim(faucet.name)
            if (now - lastClaim < faucet.cooldownMs) {
                val remainingMin = (faucet.cooldownMs - (now - lastClaim)) / 60000
                results.add(FaucetResult(faucet.name, false, "0", "Cooldown: ${remainingMin}m remaining"))
                continue
            }
            try {
                val result = claimFromFaucet(faucet, walletAddress)
                if (result.success) setLastClaim(faucet.name)
                else if (result.message.contains("404") || result.message.contains("410")) {
                    // Dead faucet — try to find replacement
                    tryDiscoverReplacement(results)
                }
                results.add(result)
            } catch (e: Exception) {
                results.add(FaucetResult(faucet.name, false, "0", "Error: ${e.message?.take(60)}"))
            }
            delay(2000)
        }

        // Tier 2: Airdrop RSS scan
        try {
            val request = Request.Builder()
                .url("https://airdrops.io/feed/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val rssBody = client.newCall(request).execute().body?.string() ?: ""
            val polygonCount = rssBody.split("<item>").drop(1).count {
                it.contains("polygon", true) || it.contains("matic", true)
            }
            results.add(FaucetResult(
                source = "Airdrop Scanner",
                success = polygonCount > 0,
                amount = "$polygonCount opportunities",
                message = "📡 Found $polygonCount Polygon airdrops on airdrops.io"
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Airdrop scan failed: ${e.message}")
        }

        results
    }

    private suspend fun claimFromFaucet(faucet: FaucetSource, walletAddress: String): FaucetResult {
        return try {
            val formBuilder = FormBody.Builder().add(faucet.paramName, walletAddress)
            faucet.extraParams.forEach { (k, v) -> formBuilder.add(k, v) }
            val request = Request.Builder()
                .url(faucet.url)
                .post(formBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (Android 12; Mobile)")
                .header("Accept", "application/json, text/html")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val success = response.isSuccessful &&
                (body.contains("success", true) || body.contains("sent", true) || body.contains("claim", true))
            FaucetResult(
                source = faucet.name,
                success = success,
                amount = if (success) faucet.expectedAmount else "0",
                message = if (success) "Claimed ${faucet.expectedAmount}" else "Failed: ${body.take(80)}"
            )
        } catch (e: Exception) {
            FaucetResult(faucet.name, false, "0", "Network error: ${e.message?.take(60)}")
        }
    }

    // ── Tier 3: Self-healing faucet discovery ────────────────────────────────
    private suspend fun tryDiscoverReplacement(results: MutableList<FaucetResult>) {
        try {
            val request = Request.Builder()
                .url("https://faucetpay.io/page/network-faucet-list/matic")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val body = client.newCall(request).execute().body?.string() ?: return
            val liveCount = Regex("href=\"(https://[^\"]+)\"[^>]*>Claim").findAll(body).count()
            if (liveCount > 0) {
                results.add(FaucetResult(
                    source = "Self-Heal Discovery",
                    success = true,
                    amount = "$liveCount faucets",
                    message = "🔍 Found $liveCount live MATIC faucets on FaucetPay"
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Self-heal discovery failed: ${e.message}")
        }
    }
}
