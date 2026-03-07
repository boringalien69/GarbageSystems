package com.garbagesys.engine.faucet

import android.content.Context
import android.util.Log
import com.garbagesys.data.models.AirdropOpportunity
import com.garbagesys.data.models.AirdropStatus
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FaucetManager(private val context: Context) {

    private val TAG = "FaucetManager"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val submitter = AirdropSubmitter(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class FaucetResult(val source: String, val success: Boolean, val amount: String, val message: String, val timestamp: Long = System.currentTimeMillis())
    data class FaucetSource(val name: String, val url: String, val paramName: String, val expectedAmount: String, val cooldownMs: Long, val extraParams: Map<String, String> = emptyMap())

    private fun getLastClaim(name: String): Long = context.getSharedPreferences("gs_faucet_cd", Context.MODE_PRIVATE).getLong(name, 0L)
    private fun setLastClaim(name: String) = context.getSharedPreferences("gs_faucet_cd", Context.MODE_PRIVATE).edit().putLong(name, System.currentTimeMillis()).apply()

    fun loadAirdrops(): List<AirdropOpportunity> {
        val raw = context.getSharedPreferences("gs_airdrops", Context.MODE_PRIVATE).getString("list", null) ?: return emptyList()
        return try { json.decodeFromString(raw) } catch (e: Exception) { emptyList() }
    }

    private fun saveAirdrops(list: List<AirdropOpportunity>) {
        context.getSharedPreferences("gs_airdrops", Context.MODE_PRIVATE).edit().putString("list", json.encodeToString(list)).apply()
    }

    private fun upsertAirdrop(airdrop: AirdropOpportunity) {
        val current = loadAirdrops().toMutableList()
        val idx = current.indexOfFirst { it.id == airdrop.id }
        if (idx >= 0) current[idx] = airdrop else current.add(0, airdrop)
        saveAirdrops(current.take(50))
    }

    private val faucetSources = listOf(
        FaucetSource("Polygon Faucet (stakely.io)", "https://stakely.io/faucet/polygon-matic", "address", "0.001 MATIC", 24 * 60 * 60 * 1000L),
        FaucetSource("Polygon Faucet (faucet.polygon.technology)", "https://faucet.polygon.technology/", "address", "0.001 MATIC", 24 * 60 * 60 * 1000L),
        FaucetSource("FreeCryptoFaucet MATIC", "https://freecryptofaucet.net/claim", "address", "0.001 MATIC", 60 * 60 * 1000L, mapOf("coin" to "matic", "action" to "claim")),
        FaucetSource("Allcoins MATIC", "https://allcoins.pw/api/faucet/claim", "address", "0.001 MATIC", 60 * 60 * 1000L, mapOf("currency" to "matic")),
        FaucetSource("Cointiply", "https://cointiply.com/faucet", "address", "micro BTC", 60 * 60 * 1000L)
    )

    suspend fun claimAll(walletAddress: String): List<FaucetResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FaucetResult>()
        val now = System.currentTimeMillis()

        for (faucet in faucetSources) {
            val lastClaim = getLastClaim(faucet.name)
            if (now - lastClaim < faucet.cooldownMs) {
                val rem = (faucet.cooldownMs - (now - lastClaim)) / 60000
                results.add(FaucetResult(faucet.name, false, "0", "Cooldown: ${rem}m remaining"))
                continue
            }
            try {
                val result = claimFromFaucet(faucet, walletAddress)
                if (result.success) setLastClaim(faucet.name)
                results.add(result)
            } catch (e: Exception) {
                results.add(FaucetResult(faucet.name, false, "0", "Error: ${e.message?.take(60)}"))
            }
            delay(2000)
        }

        try {
            val rssBody = client.newCall(Request.Builder().url("https://airdrops.io/feed/").header("User-Agent", "Mozilla/5.0").build()).execute().body?.string() ?: ""
            val found = parseAirdropsFromRss(rssBody)
            results.add(FaucetResult("Airdrop Scanner", found.isNotEmpty(), "${found.size} opportunities", "📡 Found ${found.size} Polygon airdrops"))
            for (airdrop in found) {
                val existing = loadAirdrops().find { it.id == airdrop.id }
                if (existing != null && existing.status != AirdropStatus.PENDING) continue
                upsertAirdrop(airdrop)
                val updated = submitter.trySubmit(airdrop, walletAddress)
                upsertAirdrop(updated)
                val msg = when (updated.status) {
                    AirdropStatus.SUBMITTED -> "✅ Auto-submitted: ${airdrop.name}"
                    AirdropStatus.REQUIRES_ACTION -> "👆 ${airdrop.name}: ${updated.requiresAction}"
                    else -> "⚠️ ${airdrop.name}: ${updated.requiresAction}"
                }
                results.add(FaucetResult(airdrop.name, updated.status == AirdropStatus.SUBMITTED, "", msg))
                delay(3000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Airdrop scan: ${e.message}")
        }

        try {
            val body = client.newCall(Request.Builder().url("https://faucetpay.io/page/network-faucet-list/matic").header("User-Agent", "Mozilla/5.0").build()).execute().body?.string() ?: ""
            val count = Regex("href=\"(https://[^\"]+)\"[^>]*>Claim").findAll(body).count()
            if (count > 0) results.add(FaucetResult("Self-Heal", true, "$count faucets", "🔍 $count live MATIC faucets on FaucetPay"))
        } catch (e: Exception) { Log.w(TAG, "Self-heal: ${e.message}") }

        results
    }

    private fun parseAirdropsFromRss(rss: String): List<AirdropOpportunity> {
        val list = mutableListOf<AirdropOpportunity>()
        for (item in rss.split("<item>").drop(1).take(15)) {
            val title = Regex("<title><!\\[CDATA\\[(.+?)\\]\\]>").find(item)?.groupValues?.get(1) ?: Regex("<title>(.+?)</title>").find(item)?.groupValues?.get(1) ?: continue
            val link = Regex("<link>(.+?)</link>").find(item)?.groupValues?.get(1) ?: continue
            val desc = Regex("<description><!\\[CDATA\\[(.+?)\\]\\]>").find(item)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.take(120) ?: ""
            if (item.contains("polygon", true) || item.contains("matic", true) || item.contains("usdc", true)) {
                list.add(AirdropOpportunity(id = link.hashCode().toString(), name = title.take(60), description = desc, url = link))
            }
        }
        return list
    }

    private suspend fun claimFromFaucet(faucet: FaucetSource, walletAddress: String): FaucetResult {
        return try {
            val fb = FormBody.Builder().add(faucet.paramName, walletAddress)
            faucet.extraParams.forEach { (k, v) -> fb.add(k, v) }
            val resp = client.newCall(Request.Builder().url(faucet.url).post(fb.build()).header("User-Agent", "Mozilla/5.0 (Android 12; Mobile)").build()).execute()
            val body = resp.body?.string() ?: ""
            val ok = resp.isSuccessful && (body.contains("success", true) || body.contains("sent", true) || body.contains("claim", true))
            FaucetResult(faucet.name, ok, if (ok) faucet.expectedAmount else "0", if (ok) "Claimed ${faucet.expectedAmount}" else "Failed: ${body.take(80)}")
        } catch (e: Exception) {
            FaucetResult(faucet.name, false, "0", "Network error: ${e.message?.take(60)}")
        }
    }
}
