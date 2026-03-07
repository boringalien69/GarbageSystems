package com.garbagesys.engine.strategies

import com.garbagesys.GarbageSysApp
import com.garbagesys.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════════
//  CrowdContraStrategy — Bet against 80%+ overconfident crowds
// ══════════════════════════════════════════════════════════════
class CrowdContraStrategy(
    private val polyClient: PolymarketClient,
    private val config: StrategyConfig
) {
    /**
     * Find markets where crowd is ≥ 80% confident in one outcome.
     * Historical data shows these markets resolve correctly ~72% of the time,
     * meaning the NO side has ~28% chance vs market's implied ~20% → 8% edge.
     */
    suspend fun findSignals(bankrollUsdc: Double): List<MarketSignal> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()

        val allMarkets = polyClient.getActiveMarkets(200)
        val signals = mutableListOf<MarketSignal>()

        for (market in allMarkets) {
            try {
                val yesProb = market.yesPrice
                val noProb  = market.noPrice

                // Check if crowd is overconfident
                val contraNoProb  = BayesianEngine.crowdContraSignal(yesProb)
                val contraYesProb = BayesianEngine.crowdContraSignal(noProb)

                when {
                    contraNoProb != null -> {
                        // Crowd thinks YES is 80%+, we fade with NO
                        val edge = BayesianEngine.calculateEdge(contraNoProb, noProb)
                        if (edge < config.minEdgePercent) continue
                        val size = BayesianEngine.calculatePositionSize(
                            bankrollUsdc, contraNoProb, noProb,
                            config.kellyFraction, config.maxPositionUsdc
                        )
                        if (size < 0.5) continue
                        signals.add(MarketSignal(
                            marketId = market.conditionId,
                            question = market.question,
                            strategy = StrategyType.CROWD_CONTRA,
                            side = TradeSide.NO,
                            estimatedTrueProb = contraNoProb,
                            marketImpliedProb = noProb,
                            edge = edge,
                            suggestedSizeUsdc = size,
                            confidence = 0.55,
                            reasoning = "Crowd at ${(yesProb * 100).toInt()}% YES. " +
                                "Historical accuracy at this level ~72%. Fading with NO. Edge: ${(edge * 100).toInt()}%",
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                    contraYesProb != null -> {
                        // Crowd thinks NO is 80%+, fade with YES
                        val edge = BayesianEngine.calculateEdge(contraYesProb, yesProb)
                        if (edge < config.minEdgePercent) continue
                        val size = BayesianEngine.calculatePositionSize(
                            bankrollUsdc, contraYesProb, yesProb,
                            config.kellyFraction, config.maxPositionUsdc
                        )
                        if (size < 0.5) continue
                        signals.add(MarketSignal(
                            marketId = market.conditionId,
                            question = market.question,
                            strategy = StrategyType.CROWD_CONTRA,
                            side = TradeSide.YES,
                            estimatedTrueProb = contraYesProb,
                            marketImpliedProb = yesProb,
                            edge = edge,
                            suggestedSizeUsdc = size,
                            confidence = 0.55,
                            reasoning = "Crowd at ${(noProb * 100).toInt()}% NO. Fading with YES. Edge: ${(edge * 100).toInt()}%",
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                }
            } catch (e: Exception) { continue }
        }

        signals.sortedByDescending { it.edge }.take(config.maxOpenPositions)
    }
}

// ══════════════════════════════════════════════════════════════
//  LatencyArbStrategy — CEX vs Polymarket price lag
// ══════════════════════════════════════════════════════════════
class LatencyArbStrategy(
    private val polyClient: PolymarketClient,
    private val config: StrategyConfig
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Track last seen prices
    private var lastBtcPrice: Double = 0.0
    private var lastEthPrice: Double = 0.0
    private var lastSolPrice: Double = 0.0
    private var lastPriceCheck: Long = 0L

    /**
     * Fetch real-time crypto prices from CoinGecko (free, no key).
     * Compare to Polymarket implied probabilities.
     */
    suspend fun findSignals(bankrollUsdc: Double): List<MarketSignal> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()

        val prices = getCurrentPrices() ?: return@withContext emptyList()
        val fastMarkets = polyClient.getCryptoFastMarkets()
        if (fastMarkets.isEmpty()) return@withContext emptyList()

        val signals = mutableListOf<MarketSignal>()
        val now = System.currentTimeMillis()

        for (market in fastMarkets.take(20)) {
            try {
                val q = market.question.lowercase()
                val (coin, currentPrice, prevPrice) = when {
                    q.contains("btc") || q.contains("bitcoin") ->
                        Triple("BTC", prices["btc"] ?: continue, lastBtcPrice)
                    q.contains("eth") || q.contains("ethereum") ->
                        Triple("ETH", prices["eth"] ?: continue, lastEthPrice)
                    q.contains("sol") || q.contains("solana") ->
                        Triple("SOL", prices["sol"] ?: continue, lastSolPrice)
                    else -> continue
                }

                if (prevPrice <= 0.0) continue // no prev price yet

                val realDeltaPct = (currentPrice - prevPrice) / prevPrice
                val polyImpliedYesProb = market.yesPrice

                // Parse target price from question if present
                val targetPrice = parseTargetPrice(market.question)

                // Simple signal: if real price moved up >1% and market hasn't repriced
                if (realDeltaPct > 0.01 && polyImpliedYesProb < 0.6) {
                    // Market hasn't caught up to bullish price move
                    val trueProbEstimate = minOf(polyImpliedYesProb + realDeltaPct * 5, 0.90)
                    val edge = BayesianEngine.calculateEdge(trueProbEstimate, polyImpliedYesProb)
                    if (edge >= config.minEdgePercent) {
                        val size = BayesianEngine.calculatePositionSize(
                            bankrollUsdc, trueProbEstimate, polyImpliedYesProb,
                            config.kellyFraction, config.maxPositionUsdc
                        )
                        if (size >= 0.5) {
                            signals.add(MarketSignal(
                                marketId = market.conditionId,
                                question = market.question,
                                strategy = StrategyType.LATENCY_ARB,
                                side = TradeSide.YES,
                                estimatedTrueProb = trueProbEstimate,
                                marketImpliedProb = polyImpliedYesProb,
                                edge = edge,
                                suggestedSizeUsdc = size,
                                confidence = 0.5 + edge,
                                reasoning = "$coin real delta: +${(realDeltaPct * 100).toInt()}%. " +
                                    "Polymarket lagging at ${(polyImpliedYesProb * 100).toInt()}%. Latency arb.",
                                timestamp = now
                            ))
                        }
                    }
                } else if (realDeltaPct < -0.01 && polyImpliedYesProb > 0.4) {
                    // Market hasn't caught up to bearish move
                    val trueProbEstimate = maxOf(polyImpliedYesProb + realDeltaPct * 5, 0.10)
                    val edge = BayesianEngine.calculateEdge(1 - trueProbEstimate, market.noPrice)
                    if (edge >= config.minEdgePercent) {
                        val size = BayesianEngine.calculatePositionSize(
                            bankrollUsdc, 1 - trueProbEstimate, market.noPrice,
                            config.kellyFraction, config.maxPositionUsdc
                        )
                        if (size >= 0.5) {
                            signals.add(MarketSignal(
                                marketId = market.conditionId,
                                question = market.question,
                                strategy = StrategyType.LATENCY_ARB,
                                side = TradeSide.NO,
                                estimatedTrueProb = 1 - trueProbEstimate,
                                marketImpliedProb = market.noPrice,
                                edge = edge,
                                suggestedSizeUsdc = size,
                                confidence = 0.5 + edge,
                                reasoning = "$coin real delta: ${(realDeltaPct * 100).toInt()}%. " +
                                    "Polymarket lagging. Latency arb NO.",
                                timestamp = now
                            ))
                        }
                    }
                }
            } catch (e: Exception) { continue }
        }

        // Update cached prices
        prices["btc"]?.let { lastBtcPrice = it }
        prices["eth"]?.let { lastEthPrice = it }
        prices["sol"]?.let { lastSolPrice = it }
        lastPriceCheck = now

        signals.sortedByDescending { it.edge }.take(config.maxOpenPositions)
    }

    private suspend fun getCurrentPrices(): Map<String, Double>? = withContext(Dispatchers.IO) {
        try {
            val url = "${GarbageSysApp.COINGECKO_BASE}/simple/price" +
                "?ids=bitcoin,ethereum,solana&vs_currencies=usd"
            val request = Request.Builder().url(url)
                .header("Accept", "application/json").build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = Json.parseToJsonElement(body).jsonObject
            mapOf(
                "btc" to (json["bitcoin"]?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0),
                "eth" to (json["ethereum"]?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0),
                "sol" to (json["solana"]?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0)
            )
        } catch (e: Exception) { null }
    }

    private fun parseTargetPrice(question: String): Double? {
        val pattern = Regex("""\$?([\d,]+(?:\.\d+)?)""")
        return pattern.find(question)?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull()
    }
}
