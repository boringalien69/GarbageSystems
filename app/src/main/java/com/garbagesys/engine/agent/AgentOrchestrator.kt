package com.garbagesys.engine.agent

import android.content.Context
import android.util.Log
import com.garbagesys.data.db.PreferencesRepository
import com.garbagesys.data.models.*
import com.garbagesys.engine.faucet.FaucetManager
import com.garbagesys.engine.llm.LlmEngine
import com.garbagesys.engine.strategies.*
import com.garbagesys.engine.wallet.WalletManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

class AgentOrchestrator(private val context: Context) {
    private val TAG = "AgentOrchestrator"
    private val prefs = PreferencesRepository(context)
    private val walletManager = WalletManager(context)
    private val llmEngine = LlmEngine(context)
    private val faucetManager = FaucetManager(context, prefs)

    suspend fun runCycle() = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        try {
            logs.add("🔄 Starting agent cycle...")

            // Check setup complete
            val setupState = prefs.setupStateFlow.firstOrNull() ?: AppSetupState()
            if (!setupState.isInitialized) {
                logs.add("⏳ Setup not complete. Skipping cycle.")
                saveLogs(logs); return@withContext
            }

            // Get credentials + wallet state
            val credentials = walletManager.getOrCreateWallet()
            walletManager.refreshWalletState(credentials)
            val walletState = prefs.walletStateFlow.firstOrNull() ?: WalletState()
            val usdcBalance = walletState.usdcBalance
            val maticBalance = walletState.maticBalance

            logs.add("💰 Balance: $${String.format("%.4f", usdcBalance)} USDC, ${String.format("%.4f", maticBalance)} MATIC")

            // Bootstrap if low
            if (usdcBalance < 1.0) {
                val bootstrapLogs = faucetManager.runBootstrap(credentials.address)
                logs.addAll(bootstrapLogs)
            }

            // Need gas to trade
            if (maticBalance < 0.001 && usdcBalance < 0.5) {
                logs.add("⛽ Insufficient gas. Waiting for bootstrap...")
                saveLogs(logs); return@withContext
            }

            // Load strategy configs
            val configs = prefs.strategyConfigsFlow.firstOrNull() ?: AllStrategyConfigs()

            // Run strategies in parallel
            logs.add("📊 Scanning markets for signals...")
            val signals = mutableListOf<MarketSignal>()
            listOf(
                async { runCatching { WeatherStrategy(polymarketClient(), configs.weather).findSignals(usdcBalance) }.getOrNull() ?: emptyList() },
                async { runCatching { WhaleCopyStrategy(polymarketClient(), configs.whaleCopy).findSignals(usdcBalance) }.getOrNull() ?: emptyList() },
                async { runCatching { CrowdContraStrategy(polymarketClient(), configs.crowdContra).findSignals(usdcBalance) }.getOrNull() ?: emptyList() },
                async { runCatching { LatencyArbStrategy(polymarketClient(), configs.latencyArb).findSignals(usdcBalance) }.getOrNull() ?: emptyList() }
            ).awaitAll().forEach { signals.addAll(it) }

            val topSignals = signals.filter { it.edge > 0.03 }.sortedByDescending { it.edge }.take(5)
            logs.add("📈 Found ${topSignals.size} potential signals")

            if (topSignals.isEmpty()) {
                logs.add("😴 No actionable signals this cycle")
            } else {
                val approved = mutableListOf<MarketSignal>()
                for (signal in topSignals.take(3)) {
                    try {
                        val decision = llmEngine.decide(
                            "Market: ${signal.question}\nStrategy: ${signal.strategy}\nEdge: ${String.format("%.1f", signal.edge * 100)}%\nConfidence: ${String.format("%.1f", signal.confidence * 100)}%",
                            "Should we place this trade?"
                        )
                        if (decision.verdict == "YES" && decision.confidence >= 0.6) {
                            approved.add(signal)
                            logs.add("✅ Approved: ${signal.question.take(50)}")
                        } else {
                            logs.add("⏭️ Skipped: ${signal.question.take(40)} — ${decision.reasoning.take(30)}")
                        }
                    } catch (e: Exception) {
                        if (signal.confidence > 0.7) { approved.add(signal); logs.add("✅ Strategy approved (LLM offline): ${signal.question.take(40)}") }
                    }
                }

                for (signal in approved.take(3)) {
                    try {
                        val size = signal.suggestedSizeUsdc.coerceAtMost(usdcBalance * 0.20)
                        if (size < 0.10) { logs.add("💸 Position too small, skipping"); continue }
                        val txHash = walletManager.transferUsdc(credentials, GarbageSysApp.POLYMARKET_CLOB_BASE, size)
                        if (txHash != null) {
                            logs.add("🎯 Trade: ${signal.question.take(40)} | $${String.format("%.3f", size)} | tx: ${txHash.take(10)}...")
                            prefs.addTradeRecord(TradeRecord(
                                marketId = signal.marketId,
                                question = signal.question,
                                strategy = signal.strategy.name,
                                side = signal.side.name,
                                amountUsdc = size,
                                txHash = txHash,
                                timestamp = System.currentTimeMillis()
                            ))
                        } else {
                            logs.add("❌ Trade failed for ${signal.question.take(40)}")
                        }
                    } catch (e: Exception) {
                        logs.add("❌ Trade error: ${e.message?.take(50)}")
                    }
                    delay(2000)
                }
            }

            // Daily 50% send
            checkAndSendDailyEarnings(credentials, walletState, logs)

            logs.add("✅ Cycle complete.")

        } catch (e: Exception) {
            logs.add("💥 Cycle error: ${e.message}")
            Log.e(TAG, "Cycle error", e)
        }
        saveLogs(logs)
    }

    private fun polymarketClient() = PolymarketClient()

    private suspend fun checkAndSendDailyEarnings(
        credentials: org.web3j.crypto.Credentials,
        walletState: WalletState,
        logs: MutableList<String>
    ) {
        val setupState = prefs.setupStateFlow.firstOrNull() ?: return
        val lastSend = setupState.lastDailySendAt
        if (System.currentTimeMillis() - lastSend < 86400000L) return
        val userWallet = walletState.userWalletAddress
        if (userWallet.isEmpty()) { logs.add("⚠️ No receiving wallet set"); return }
        val balance = walletState.usdcBalance
        val sendAmount = balance * 0.50
        if (sendAmount < 0.50) { logs.add("📊 Daily P&L: $${String.format("%.2f", balance)} — too small (min $0.50)"); return }
        val txHash = walletManager.transferUsdc(credentials, userWallet, sendAmount)
        if (txHash != null) {
            logs.add("💸 Sent 50% ($${String.format("%.4f", sendAmount)}) to your wallet! tx: ${txHash.take(10)}...")
            prefs.saveSetupState(setupState.copy(lastDailySendAt = System.currentTimeMillis()))
        } else {
            logs.add("⚠️ Daily send failed")
        }
    }

    private suspend fun saveLogs(logs: List<String>) {
        for (log in logs) prefs.addCycleLog(AgentCycleLog(message = log, timestamp = System.currentTimeMillis()))
    }
}
