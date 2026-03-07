package com.garbagesys.engine.strategies

import com.garbagesys.GarbageSysApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class PolyMarket(
    val conditionId: String,
    val question: String,
    val yesPrice: Double,
    val noPrice: Double,
    val volume: Double,
    val endDate: String,
    val active: Boolean
)

data class PolyOrderResult(
    val success: Boolean,
    val orderId: String? = null,
    val error: String? = null
)

class PolymarketClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // ── Fetch active markets (no auth) ──
    suspend fun getActiveMarkets(limit: Int = 100): List<PolyMarket> = withContext(Dispatchers.IO) {
        try {
            val url = "${GarbageSysApp.POLYMARKET_GAMMA_BASE}/markets" +
                "?active=true&closed=false&limit=$limit&order=volume&ascending=false"
            val request = Request.Builder().url(url)
                .header("Accept", "application/json").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = Json.parseToJsonElement(body).jsonArray
            arr.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val conditionId = obj["conditionId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val question = obj["question"]?.jsonPrimitive?.content ?: ""
                    val outcomePrices = obj["outcomePrices"]?.jsonPrimitive?.content
                        ?.let { Json.parseToJsonElement(it).jsonArray }
                    val yesPrice = outcomePrices?.get(0)?.jsonPrimitive?.doubleOrNull ?: 0.5
                    val noPrice = outcomePrices?.get(1)?.jsonPrimitive?.doubleOrNull ?: 0.5
                    val volume = obj["volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val endDate = obj["endDateIso"]?.jsonPrimitive?.content ?: ""
                    val active = obj["active"]?.jsonPrimitive?.booleanOrNull ?: false
                    PolyMarket(conditionId, question, yesPrice, noPrice, volume, endDate, active)
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Fetch weather-related markets ──
    suspend fun getWeatherMarkets(): List<PolyMarket> {
        val all = getActiveMarkets(200)
        val weatherKeywords = listOf(
            "temperature", "temp", "rain", "snow", "hurricane", "storm",
            "precipitation", "weather", "forecast", "high of", "low of",
            "degrees", "celsius", "fahrenheit", "humid", "wind"
        )
        return all.filter { market ->
            weatherKeywords.any { keyword ->
                market.question.lowercase().contains(keyword)
            }
        }
    }

    // ── Fetch crypto up/down markets (5-15 min) ──
    suspend fun getCryptoFastMarkets(): List<PolyMarket> {
        val all = getActiveMarkets(200)
        val cryptoKeywords = listOf("btc", "eth", "sol", "bitcoin", "ethereum", "solana",
            "up or down", "above", "below", "price", "crypto", "15 min", "5 min")
        return all.filter { market ->
            cryptoKeywords.any { keyword ->
                market.question.lowercase().contains(keyword)
            }
        }
    }

    // ── Get current best bid/ask for a market ──
    suspend fun getMarketOrderBook(tokenId: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val url = "${GarbageSysApp.POLYMARKET_CLOB_BASE}/book?token_id=$tokenId&side=BUY"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val obj = Json.parseToJsonElement(body).jsonObject
            val bids = obj["bids"]?.jsonArray
            val asks = obj["asks"]?.jsonArray
            val bestBid = bids?.firstOrNull()?.jsonObject?.get("price")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val bestAsk = asks?.firstOrNull()?.jsonObject?.get("price")?.jsonPrimitive?.doubleOrNull ?: 1.0
            Pair(bestBid, bestAsk)
        } catch (e: Exception) {
            null
        }
    }

    // ── Get top whale wallets by P&L from Polymarket leaderboard ──
    suspend fun getTopWallets(limit: Int = 10): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val url = "${GarbageSysApp.POLYMARKET_GAMMA_BASE}/leaderboard?limit=$limit&window=ALL"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = Json.parseToJsonElement(body).jsonArray
            arr.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    mapOf(
                        "address" to (obj["proxyWallet"]?.jsonPrimitive?.content ?: ""),
                        "pnl" to (obj["pnl"]?.jsonPrimitive?.doubleOrNull ?: 0.0),
                        "volume" to (obj["volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Get wallet's recent positions ──
    suspend fun getWalletPositions(address: String): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val url = "${GarbageSysApp.POLYMARKET_GAMMA_BASE}/positions?user=$address&limit=20"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = Json.parseToJsonElement(body).jsonArray
            arr.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    mapOf(
                        "market" to (obj["market"]?.jsonPrimitive?.content ?: ""),
                        "outcome" to (obj["outcome"]?.jsonPrimitive?.content ?: ""),
                        "size" to (obj["size"]?.jsonPrimitive?.doubleOrNull ?: 0.0),
                        "avgPrice" to (obj["avgPrice"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Get market implied probability (crowd sentiment) ──
    fun getImpliedProbability(market: PolyMarket): Pair<Double, Double> {
        // YES price = implied probability of YES outcome
        // NO price = implied probability of NO outcome
        // They should sum to ~1.0 (minus spread)
        return Pair(market.yesPrice, market.noPrice)
    }
}
