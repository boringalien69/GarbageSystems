package com.garbagesys.engine.faucet

import android.content.Context
import android.webkit.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import java.util.concurrent.TimeUnit

/**
 * FaucetManager handles autonomous faucet claiming for bootstrap.
 *
 * Strategy:
 * - Hits free public Polygon/MATIC faucets via HTTP (no WebView needed for most)
 * - Rotates through multiple sources every 30–60 minutes
 * - Self-heals: if a faucet goes down, skips and tries others
 * - Logs results for display in dashboard
 *
 * Future-proof design: faucet URLs stored in a config list.
 * If one fails, others are tried. The LLM can be asked to find new faucets
 * if all fail (it searches its knowledge for public faucets).
 */
class FaucetManager(private val context: Context) {

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

    /**
     * Faucet sources — ordered by reliability.
     * HTTP-based faucets that accept wallet address via POST.
     * These are free, no-KYC, public faucets.
     */
    private val faucetSources = listOf(
        // Polygon MATIC faucets
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
        // USDC test/micro faucets
        FaucetSource(
            name = "Cointiply (micro earnings)",
            url = "https://cointiply.com/faucet",
            paramName = "address",
            expectedAmount = "micro BTC",
            cooldownMs = 60 * 60 * 1000L // 1 hour
        ),
    )

    private val claimHistory = mutableMapOf<String, Long>() // source → last claimed timestamp

    /**
     * Try to claim from all available faucets that are not on cooldown.
     * Returns list of results.
     */
    suspend fun claimAll(walletAddress: String): List<FaucetResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FaucetResult>()
        val now = System.currentTimeMillis()

        for (faucet in faucetSources) {
            val lastClaim = claimHistory[faucet.name] ?: 0L
            if (now - lastClaim < faucet.cooldownMs) {
                val remainingMs = faucet.cooldownMs - (now - lastClaim)
                val remainingMin = remainingMs / 60000
                results.add(FaucetResult(
                    source = faucet.name,
                    success = false,
                    amount = "0",
                    message = "Cooldown: ${remainingMin}m remaining"
                ))
                continue
            }

            try {
                val result = claimFromFaucet(faucet, walletAddress)
                if (result.success) {
                    claimHistory[faucet.name] = now
                }
                results.add(result)
            } catch (e: Exception) {
                results.add(FaucetResult(
                    source = faucet.name,
                    success = false,
                    amount = "0",
                    message = "Error: ${e.message?.take(60) ?: "Unknown"}"
                ))
            }

            // Small delay between faucet claims
            delay(2000)
        }

        results
    }

    private suspend fun claimFromFaucet(
        faucet: FaucetSource,
        walletAddress: String
    ): FaucetResult = withContext(Dispatchers.IO) {
        // Try POST first (most common for faucets)
        try {
            val formBody = FormBody.Builder()
                .add(faucet.paramName, walletAddress)
                .build()
            val request = Request.Builder()
                .url(faucet.url)
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Android 12; Mobile)")
                .header("Accept", "application/json, text/html")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            // Check for success indicators
            val success = response.isSuccessful &&
                (body.contains("success", ignoreCase = true) ||
                 body.contains("sent", ignoreCase = true) ||
                 body.contains("claim", ignoreCase = true))

            FaucetResult(
                source = faucet.name,
                success = success,
                amount = if (success) faucet.expectedAmount else "0",
                message = if (success) "Claimed ${faucet.expectedAmount}" else "Failed: ${body.take(80)}"
            )
        } catch (e: Exception) {
            FaucetResult(
                source = faucet.name,
                success = false,
                amount = "0",
                message = "Network error: ${e.message?.take(60)}"
            )
        }
    }

    data class FaucetSource(
        val name: String,
        val url: String,
        val paramName: String,
        val expectedAmount: String,
        val cooldownMs: Long
    )
}
