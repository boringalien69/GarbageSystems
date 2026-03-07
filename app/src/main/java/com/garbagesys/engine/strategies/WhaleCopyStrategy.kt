package com.garbagesys.engine.strategies

import com.garbagesys.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * WhaleCopyStrategy:
 * 1. Auto-discovers top 10 wallets by P&L from Polymarket leaderboard
 * 2. Scores them by profitability, recency, win rate
 * 3. Copies their recent positions proportionally
 * 4. Future-proof: re-discovers new whales every cycle
 */
class WhaleCopyStrategy(
    private val polyClient: PolymarketClient,
    private val config: StrategyConfig
) {

    private var cachedWhales: List<WhaleWallet> = emptyList()
    private var lastWhaleUpdate: Long = 0L
    private val WHALE_CACHE_MS = 30 * 60 * 1000L // refresh every 30 min

    suspend fun updateWhaleList(): List<WhaleWallet> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedWhales.isNotEmpty() && now - lastWhaleUpdate < WHALE_CACHE_MS) {
            return@withContext cachedWhales
        }

        val topWallets = polyClient.getTopWallets(20)
        val whales = topWallets.mapNotNull { wallet ->
            try {
                val address = wallet["address"] as? String ?: return@mapNotNull null
                val pnl = wallet["pnl"] as? Double ?: 0.0
                val volume = wallet["volume"] as? Double ?: 0.0
                if (address.isEmpty() || pnl <= 0) return@mapNotNull null

                // Score: weighted by P&L, volume, recency
                val score = (pnl * 0.6) + (volume * 0.0001 * 0.4)

                WhaleWallet(
                    address = address,
                    label = "Whale #${address.takeLast(6)}",
                    totalPnlUsdc = pnl,
                    winRate = 0.0, // would need more data
                    tradeCount = 0,
                    lastActive = now,
                    score = score
                )
            } catch (e: Exception) { null }
        }.sortedByDescending { it.score }.take(5) // top 5

        cachedWhales = whales
        lastWhaleUpdate = now
        whales
    }

    suspend fun findSignals(bankrollUsdc: Double): List<MarketSignal> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()

        val whales = updateWhaleList()
        if (whales.isEmpty()) return@withContext emptyList()

        val signals = mutableListOf<MarketSignal>()

        for (whale in whales.take(3)) {
            try {
                val positions = polyClient.getWalletPositions(whale.address)
                for (position in positions.take(5)) {
                    val marketId = position["market"] as? String ?: continue
                    val outcome = position["outcome"] as? String ?: continue
                    val avgPrice = position["avgPrice"] as? Double ?: continue

                    if (avgPrice <= 0.0 || avgPrice >= 1.0) continue

                    // Find the market to get current price
                    val allMarkets = polyClient.getActiveMarkets(50)
                    val market = allMarkets.firstOrNull { it.conditionId == marketId } ?: continue

                    val side = if (outcome.uppercase() == "YES") TradeSide.YES else TradeSide.NO
                    val marketPrice = if (side == TradeSide.YES) market.yesPrice else market.noPrice

                    // Only copy if whale got a better price than current (they may have early edge)
                    if (avgPrice < marketPrice - 0.05) continue // skip if market has moved a lot already

                    val trueProbEstimate = avgPrice * 1.05 // slight edge assumption from whale knowledge
                    val edge = BayesianEngine.calculateEdge(trueProbEstimate, marketPrice)
                    if (edge < config.minEdgePercent) continue

                    val sizeUsdc = BayesianEngine.calculatePositionSize(
                        bankrollUsdc, trueProbEstimate, marketPrice,
                        config.kellyFraction, config.maxPositionUsdc
                    )
                    if (sizeUsdc < 0.5) continue

                    signals.add(MarketSignal(
                        marketId = marketId,
                        question = market.question,
                        strategy = StrategyType.WHALE_COPY,
                        side = side,
                        estimatedTrueProb = trueProbEstimate,
                        marketImpliedProb = marketPrice,
                        edge = edge,
                        suggestedSizeUsdc = sizeUsdc,
                        confidence = 0.6,
                        reasoning = "Copying ${whale.label} (P&L: \$${whale.totalPnlUsdc.toInt()}). " +
                            "Whale avg entry: ${(avgPrice * 100).toInt()}¢, current: ${(marketPrice * 100).toInt()}¢",
                        timestamp = System.currentTimeMillis()
                    ))
                }
            } catch (e: Exception) {
                continue
            }
        }

        signals.distinctBy { it.marketId }.take(config.maxOpenPositions)
    }
}
