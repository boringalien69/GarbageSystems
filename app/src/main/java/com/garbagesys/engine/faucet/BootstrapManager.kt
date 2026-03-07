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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

class BootstrapManager(
    private val context: Context,
    private val prefs: PreferencesRepository
) {
    private val TAG = "BootstrapManager"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val faucetPaySources = listOf(
        FaucetSource("FreeCryptoFaucet MATIC","https://freecryptofaucet.net/claim","MATIC",60,ClaimMethod.HTTP_POST,mapOf("coin" to "matic","action" to "claim")),
        FaucetSource("CryptoFaucet.net USDC","https://cryptofaucet.net/api/claim","USDC",60,ClaimMethod.HTTP_POST,mapOf("currency" to "usdc","network" to "polygon")),
        FaucetSource("FaucetCrypto MATIC","https://faucetcrypto.com/api/v1/claim","MATIC",30,ClaimMethod.HTTP_GET,mapOf("coin" to "matic")),
        FaucetSource("Moon Faucet MATIC","https://moonmatic.info/claim/","MATIC",240,ClaimMethod.HTTP_POST,mapOf("op" to "claim")),
        FaucetSource("Allcoins MATIC","https://allcoins.pw/api/faucet/claim","MATIC",60,ClaimMethod.HTTP_POST,mapOf("currency" to "matic"))
    )

    suspend fun runTier1Claims(walletAddress: String): BootstrapResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        var totalClaimed = 0.0
        for (source in faucetPaySources) {
            val lastClaim = prefs.getFaucetLastClaim(source.name)
            val cooldownMs = source.cooldownMinutes * 60 * 1000L
            if (System.currentTimeMillis() - lastClaim < cooldownMs) {
                val remaining = ((cooldownMs - (System.currentTimeMillis() - lastClaim)) / 60000).toInt()
                results.add("⏳ ${source.name}: cooldown ${remaining}m")
                continue
            }
            try {
                val result = claimFaucet(source, walletAddress)
                if (result.success) {
                    prefs.setFaucetLastClaim(source.name, System.currentTimeMillis())
                    totalClaimed += result.amount
                    results.add("✅ ${source.name}: +${result.amount} ${source.coin}")
                } else {
                    results.add("⚠️ ${source.name}: ${result.message}")
                    if (result.isDead) tryDiscoverReplacement(source)
                }
                delay(2000)
            } catch (e: Exception) {
                results.add("❌ ${source.name}: ${e.message?.take(50)}")
            }
        }
        BootstrapResult(1, totalClaimed, results)
    }

    private suspend fun claimFaucet(source: FaucetSource, wallet: String): ClaimResult {
        return try {
            val response = when (source.method) {
                ClaimMethod.HTTP_POST -> {
                    val formBuilder = FormBody.Builder().add("address", wallet)
                    source.params.forEach { (k, v) -> formBuilder.add(k, v) }
                    client.newCall(Request.Builder().url(source.claimUrl).post(formBuilder.build()).header("User-Agent","Mozilla/5.0").build()).execute()
                }
                ClaimMethod.HTTP_GET -> {
                    val url = source.claimUrl + "?" + source.params.entries.joinToString("&") { "${it.key}=${it.value}" } + "&address=$wallet"
                    client.newCall(Request.Builder().url(url).get().header("User-Agent","Mozilla/5.0").build()).execute()
                }
            }
            val body = response.body?.string() ?: ""
            when {
                response.code == 200 && (body.contains("success",true) || body.contains("claimed",true)) -> ClaimResult(true,0.001,"OK")
                response.code == 404 || response.code == 410 -> ClaimResult(false,0.0,"Faucet gone (${response.code})",isDead=true)
                body.contains("cooldown",true) || body.contains("too soon",true) -> ClaimResult(false,0.0,"Cooldown active")
                else -> ClaimResult(false,0.0,"Response: ${response.code}")
            }
        } catch (e: Exception) { ClaimResult(false,0.0,e.message ?: "Error") }
    }

    suspend fun runTier2AirdropScan(walletAddress: String): BootstrapResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        try {
            val request = Request.Builder().url("https://airdrops.io/feed/").header("User-Agent","Mozilla/5.0").build()
            val rssBody = client.newCall(request).execute().body?.string() ?: ""
            val polygonAirdrops = parseAirdropsRss(rssBody)
            results.add("📡 Scanned airdrops.io: ${polygonAirdrops.size} Polygon airdrops found")
            for (airdrop in polygonAirdrops.take(3)) {
                results.add("🪂 ${airdrop.name}: ${airdrop.description}")
                prefs.addPendingAirdrop(airdrop)
            }
        } catch (e: Exception) { results.add("⚠️ Airdrop scan: ${e.message?.take(40)}") }
        BootstrapResult(2, 0.0, results)
    }

    private fun parseAirdropsRss(rss: String): List<AirdropInfo> {
        val airdrops = mutableListOf<AirdropInfo>()
        try {
            for (item in rss.split("<item>").drop(1).take(10)) {
                val title = Regex("<title><!\\[CDATA\\[(.+?)\\]\\]>").find(item)?.groupValues?.get(1) ?: Regex("<title>(.+?)</title>").find(item)?.groupValues?.get(1) ?: continue
                val link = Regex("<link>(.+?)</link>").find(item)?.groupValues?.get(1) ?: continue
                val desc = Regex("<description><!\\[CDATA\\[(.+?)\\]\\]>").find(item)?.groupValues?.get(1) ?: ""
                if (desc.contains("polygon",true) || desc.contains("matic",true) || title.contains("polygon",true)) {
                    airdrops.add(AirdropInfo(link.hashCode().toString(), title.take(50), desc.take(100), link, "polygon"))
                }
            }
        } catch (e: Exception) { Log.w(TAG,"RSS parse: ${e.message}") }
        return airdrops
    }

    private suspend fun tryDiscoverReplacement(deadSource: FaucetSource) = withContext(Dispatchers.IO) {
        try {
            val body = client.newCall(Request.Builder().url("https://faucetpay.io/page/network-faucet-list/matic").header("User-Agent","Mozilla/5.0").build()).execute().body?.string() ?: return@withContext
            val urls = Regex("href=\"(https://[^\"]+)\"[^>]*>Claim").findAll(body).map { it.groupValues[1] }.filter { it != deadSource.claimUrl }.take(3).toList()
            if (urls.isNotEmpty()) prefs.addDiscoveredFaucets(urls)
        } catch (e: Exception) { Log.w(TAG,"Discovery: ${e.message}") }
    }

    suspend fun runFullBootstrap(walletAddress: String): List<BootstrapResult> {
        return listOf(runTier1Claims(walletAddress), runTier2AirdropScan(walletAddress))
    }
}

data class FaucetSource(val name: String, val claimUrl: String, val coin: String, val cooldownMinutes: Int, val method: ClaimMethod, val params: Map<String,String> = emptyMap())
enum class ClaimMethod { HTTP_POST, HTTP_GET }
data class ClaimResult(val success: Boolean, val amount: Double, val message: String, val isDead: Boolean = false)
data class AirdropInfo(val id: String, val name: String, val description: String, val url: String, val network: String, val autoSubmittable: Boolean = false)
data class BootstrapResult(val tier: Int, val totalClaimed: Double, val messages: List<String>)
