package com.garbagesys.engine.strategies

import com.garbagesys.GarbageSysApp
import com.garbagesys.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WeatherStrategy:
 * 1. Fetches weather Polymarket markets
 * 2. Pulls NOAA forecast for relevant US cities
 * 3. Uses BayesianEngine to estimate true probability
 * 4. Compares to market price to find edge
 * 5. Returns signals for execution
 */
class WeatherStrategy(
    private val polyClient: PolymarketClient,
    private val config: StrategyConfig
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // NOAA grid points for major US cities
    // Format: city → (gridId, gridX, gridY)
    private val cityGrids = mapOf(
        "New York"    to Triple("OKX", 32, 34),
        "Chicago"     to Triple("LOT", 65, 68),
        "Miami"       to Triple("MFL", 110, 39),
        "Los Angeles" to Triple("LOX", 155, 48),
        "Houston"     to Triple("HGX", 67, 99),
        "Phoenix"     to Triple("PSR", 160, 55),
        "Denver"      to Triple("BOU", 57, 63),
        "Seattle"     to Triple("SEW", 124, 69),
        "Boston"      to Triple("BOX", 64, 55),
        "Atlanta"     to Triple("FFC", 52, 77)
    )

    suspend fun findSignals(bankrollUsdc: Double): List<MarketSignal> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()

        val signals = mutableListOf<MarketSignal>()
        val weatherMarkets = polyClient.getWeatherMarkets()
        if (weatherMarkets.isEmpty()) return@withContext emptyList()

        for (market in weatherMarkets.take(30)) {
            try {
                val signal = analyzeWeatherMarket(market, bankrollUsdc)
                if (signal != null && signal.edge >= config.minEdgePercent) {
                    signals.add(signal)
                }
            } catch (e: Exception) {
                continue
            }
        }

        signals.sortedByDescending { it.edge }.take(config.maxOpenPositions)
    }

    private suspend fun analyzeWeatherMarket(
        market: PolyMarket,
        bankrollUsdc: Double
    ): MarketSignal? {
        val q = market.question.lowercase()
        // Find the relevant city
        val city = cityGrids.keys.firstOrNull { q.contains(it.lowercase()) }
            ?: return null

        // Parse temperature bucket from question
        val bucketRange = parseTemperatureBucket(market.question) ?: return null

        // Get NOAA forecast
        val noaaForecast = getNoaaForecast(city) ?: return null

        // Convert Fahrenheit bucket to Celsius for calculation
        val bucketLowC = (bucketRange.first - 32) * 5.0 / 9.0
        val bucketHighC = (bucketRange.second - 32) * 5.0 / 9.0
        val forecastCenterC = (noaaForecast.first - 32) * 5.0 / 9.0
        val forecastUncertaintyC = noaaForecast.second * 5.0 / 9.0

        // Calculate Bayesian probability from NOAA model
        val trueProbYes = BayesianEngine.weatherBucketProbability(
            forecastCenterC, forecastUncertaintyC, bucketLowC, bucketHighC
        )

        val marketImpliedYes = market.yesPrice
        val edgeYes = BayesianEngine.calculateEdge(trueProbYes, marketImpliedYes)
        val edgeNo  = BayesianEngine.calculateEdge(1 - trueProbYes, market.noPrice)

        val (bestSide, edge, trueProb, marketPrice) = if (edgeYes > edgeNo) {
            listOf(TradeSide.YES, edgeYes, trueProbYes, marketImpliedYes)
        } else {
            listOf(TradeSide.NO, edgeNo, 1 - trueProbYes, market.noPrice)
        }

        @Suppress("UNCHECKED_CAST")
        val side = bestSide as TradeSide
        val edgeD = edge as Double
        val trueProbD = trueProb as Double
        val marketPriceD = marketPrice as Double

        if (edgeD < config.minEdgePercent) return null

        val sizeUsdc = BayesianEngine.calculatePositionSize(
            bankrollUsdc, trueProbD, marketPriceD,
            config.kellyFraction, config.maxPositionUsdc
        )

        if (sizeUsdc < 0.5) return null // minimum bet size

        return MarketSignal(
            marketId = market.conditionId,
            question = market.question,
            strategy = StrategyType.WEATHER_BAYESIAN,
            side = side,
            estimatedTrueProb = trueProbD,
            marketImpliedProb = marketPriceD,
            edge = edgeD,
            suggestedSizeUsdc = sizeUsdc,
            confidence = minOf(edgeD * 3, 0.95),
            reasoning = "NOAA forecast: ${noaaForecast.first}°F ±${noaaForecast.second}°F. " +
                "Model prob: ${(trueProbD * 100).toInt()}%, market: ${(marketPriceD * 100).toInt()}%. " +
                "Edge: ${(edgeD * 100).toInt()}%",
            timestamp = System.currentTimeMillis()
        )
    }

    private fun parseTemperatureBucket(question: String): Pair<Double, Double>? {
        // Match patterns like "between 30-34°F" or "above 70°F" or "below 32°F"
        val rangePattern = Regex("""(\d+)[\s\-–]+(\d+)\s*°?[Ff]""")
        val match = rangePattern.find(question)
        if (match != null) {
            return Pair(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
        }
        val abovePattern = Regex("""above\s+(\d+)\s*°?[Ff]""", RegexOption.IGNORE_CASE)
        val aboveMatch = abovePattern.find(question)
        if (aboveMatch != null) {
            val temp = aboveMatch.groupValues[1].toDouble()
            return Pair(temp, temp + 20)
        }
        val belowPattern = Regex("""below\s+(\d+)\s*°?[Ff]""", RegexOption.IGNORE_CASE)
        val belowMatch = belowPattern.find(question)
        if (belowMatch != null) {
            val temp = belowMatch.groupValues[1].toDouble()
            return Pair(temp - 20, temp)
        }
        return null
    }

    /**
     * Fetch NOAA forecast for a city.
     * Returns (centerTempF, uncertaintyF) or null.
     * NOAA API is free, no key needed.
     */
    private suspend fun getNoaaForecast(city: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val grid = cityGrids[city] ?: return@withContext null
        try {
            val url = "${GarbageSysApp.NOAA_API_BASE}/gridpoints/${grid.first}/${grid.second},${grid.third}/forecast"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GarbageSys/1.0 (autonomous-trading-bot)")
                .header("Accept", "application/geo+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = Json.parseToJsonElement(body).jsonObject
            val periods = json["properties"]?.jsonObject?.get("periods")?.jsonArray
            val firstPeriod = periods?.firstOrNull()?.jsonObject
            val tempF = firstPeriod?.get("temperature")?.jsonPrimitive?.doubleOrNull
                ?: return@withContext null
            // NOAA doesn't directly give uncertainty, use typical ±3°F for <24h
            Pair(tempF, 3.0)
        } catch (e: Exception) {
            null
        }
    }
}
