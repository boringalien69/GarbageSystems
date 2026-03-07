package com.garbagesys.engine.agent

import android.content.Context
import android.util.Log
import com.garbagesys.data.db.PreferencesRepository
import com.garbagesys.engine.faucet.FaucetManager
import com.garbagesys.engine.llm.LlmEngine
import com.garbagesys.engine.strategies.*
import com.garbagesys.engine.wallet.WalletManager
import kotlinx.coroutines.*
import org.json.JSONObject

class AgentOrchestrator(private val context: Context) {
    private val TAG = "AgentOrchestrator"
    private val prefs = PreferencesRepository(context)
    private val walletManager = WalletManager(context)
    private val llmEngine = LlmEngine(context)
    private val faucetManager = FaucetManager(context, prefs)
    private val polymarketClient = PolymarketClient()
    private val weatherStrategy = WeatherStrategy(polymarketClient)
    private val whaleCopyStrategy = WhaleCopyStrategy(polymarketClient)
    private val crowdContraStrategy = CrowdContraStrategy(polymarketClient)
    private val latencyArbStrategy = LatencyArbStrategy(polymarketClient)

    suspend fun runCycle(): CycleResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        var tradesExecuted = 0
        var cycleEarnings = 0.0
        try {
            logs.add("🔄 Starting agent cycle...")
            if (!prefs.isSetupComplete()) {
                logs.add("⏳ Setup not complete. Skipping cycle.")
                return@withContext CycleResult(false, 0.0, 0, logs)
            }
            val walletAddress = prefs.getWalletAddress()
            if (walletAddress.isEmpty()) {
                logs.add("⚠️ No wallet address found")
                return@withContext CycleResult(false, 0.0, 0, logs)
            }
            val usdcBalance = try { walletManager.getUsdcBalance(walletAddress) } catch (e: Exception) { logs.add("⚠️ Balance check failed: ${e.message?.take(40)}"); 0.0 }
            val maticBalance = try { walletManager.getMaticBalance(walletAddress) } catch (e: Exception) { 0.0 }
            logs.add("💰 Balance: $${String.format("%.4f", usdcBalance)} USDC, ${String.format("%.4f", maticBalance)} MATIC")

            if (usdcBalance < 1.0) {
                logs.add("🥾 Balance low. Running 3-tier bootstrap...")
                logs.addAll(faucetManager.runBootstrap(walletAddress))
            }

            if (maticBalance < 0.001 && usdcBalance < 0.5) {
                logs.add("⛽ Insufficient gas. Waiting for bootstrap...")
                prefs.setLastCycle(System.currentTimeMillis())
                for (log in logs) prefs.addAgentLog(log)
                return@withContext CycleResult(true, 0.0, 0, logs)
            }

            logs.add("📊 Scanning markets for signals...")
            val signals = mutableListOf<TradeSignal>()
            listOf(
                async { runCatching { weatherStrategy.analyze() }.getOrNull() ?: emptyList() },
                async { runCatching { whaleCopyStrategy.analyze() }.getOrNull() ?: emptyList() },
                async { runCatching { crowdContraStrategy.analyze() }.getOrNull() ?: emptyList() },
                async { runCatching { latencyArbStrategy.analyze() }.getOrNull() ?: emptyList() }
            ).awaitAll().forEach { signals.addAll(it) }

            val topSignals = signals.filter { it.expectedValue > 0.03 }.sortedByDescending { it.expectedValue }.take(5)
            logs.add("📈 Found ${topSignals.size} potential signals")

            if (topSignals.isEmpty()) {
                logs.add("😴 No actionable signals this cycle")
            } else {
                val approvedSignals = mutableListOf<TradeSignal>()
                for (signal in topSignals.take(3)) {
                    try {
                        val decision = llmEngine.decide(
                            "Market: ${signal.marketQuestion}\nStrategy: ${signal.strategy}\nEdge: ${String.format("%.1f", signal.expectedValue * 100)}%\nConfidence: ${String.format("%.1f", signal.confidence * 100)}%\nBalance: $usdcBalance",
                            "Should we place this trade?"
                        )
                        if (decision.verdict == "YES" && decision.confidence >= 0.6) {
                            approvedSignals.add(signal)
                            logs.add("✅ Approved: ${signal.marketQuestion.take(40)}")
                        } else {
                            logs.add("⏭️ Skipped: ${signal.marketQuestion.take(40)}")
                        }
                    } catch (e: Exception) {
                        if (signal.confidence > 0.7 && signal.expectedValue > 0.05) {
                            approvedSignals.add(signal)
                            logs.add("✅ Strategy approved (LLM offline): ${signal.marketQuestion.take(40)}")
                        }
                    }
                }

                for (signal in approvedSignals.take(3)) {
                    try {
                        val p = signal.ourEstimate
                        val b = (1.0 / signal.currentPrice) - 1.0
                        val kelly = ((b * p - (1.0 - p)) / b) * 0.25
                        val positionSize = minOf(kelly * usdcBalance, usdcBalance * 0.20).coerceAtLeast(0.0)
                        if (positionSize < 0.10) { logs.add("💸 Too small to trade"); continue }

                        val tradeResult = walletManager.executeTrade(signal.marketId, signal.outcomeIndex, positionSize, signal.currentPrice * 1.02)
                        if (tradeResult.success) {
                            tradesExecuted++
                            cycleEarnings += tradeResult.estimatedPnl
                            logs.add("🎯 Trade: ${signal.marketQuestion.take(40)} | $${String.format("%.3f", positionSize)}")
                            prefs.addTradeRecord(JSONObject().apply {
                                put("time", System.currentTimeMillis()); put("market", signal.marketQuestion)
                                put("strategy", signal.strategy); put("amount", positionSize); put("txHash", tradeResult.txHash)
                            }.toString())
                            prefs.addEarnings(tradeResult.estimatedPnl)
                        } else { logs.add("❌ Trade failed: ${tradeResult.error}") }
                    } catch (e: Exception) { logs.add("❌ Trade error: ${e.message?.take(50)}") }
                    delay(2000)
                }
            }

            checkAndSendDailyEarnings(logs)
            logs.add("✅ Cycle complete. Trades: $tradesExecuted")
            prefs.setLastCycle(System.currentTimeMillis())
        } catch (e: Exception) {
            logs.add("💥 Cycle error: ${e.message}")
            Log.e(TAG, "Cycle error", e)
        }
        for (log in logs) prefs.addAgentLog(log)
        CycleResult(true, cycleEarnings, tradesExecuted, logs)
    }

    private suspend fun checkAndSendDailyEarnings(logs: MutableList<String>) {
        if (System.currentTimeMillis() - prefs.getLastDailySend() < 86400000L) return
        val receivingWallet = prefs.getReceivingWallet()
        if (receivingWallet.isEmpty()) { logs.add("⚠️ No receiving wallet set"); return }
        val balance = try { walletManager.getUsdcBalance(prefs.getWalletAddress()) } catch (e: Exception) { return }
        val sendAmount = balance * 0.50
        if (sendAmount < 0.50) { logs.add("📊 Daily P&L: $${String.format("%.2f",balance)} — too small to send (min $0.50)"); return }
        try {
            val result = walletManager.sendUsdc(receivingWallet, sendAmount)
            if (result.success) {
                prefs.addSent(sendAmount); prefs.setLastDailySend(System.currentTimeMillis())
                logs.add("💸 Sent 50% ($${String.format("%.4f",sendAmount)}) to your wallet!")
            } else { logs.add("⚠️ Daily send failed: ${result.error}") }
        } catch (e: Exception) { logs.add("⚠️ Daily send error: ${e.message?.take(40)}") }
    }
}

data class CycleResult(val success: Boolean, val earnings: Double, val tradesExecuted: Int, val logs: List<String>)
