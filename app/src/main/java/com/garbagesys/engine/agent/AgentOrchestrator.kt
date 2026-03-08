package com.garbagesys.engine.agent

import android.content.Context
import com.garbagesys.data.db.PreferencesRepository
import com.garbagesys.data.models.*
import com.garbagesys.engine.bootstrap.TelegramFarmer
import com.garbagesys.engine.faucet.FaucetManager
import com.garbagesys.engine.llm.LlmEngine
import com.garbagesys.engine.strategies.*
import com.garbagesys.engine.wallet.WalletManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.*

class AgentOrchestrator(private val context: Context) {

    private val prefs = PreferencesRepository(context)
    private val walletManager = WalletManager(context)
    private val llmEngine = LlmEngine(context)
    private val polyClient = PolymarketClient()
    private val faucetManager = FaucetManager(context)
    private val telegramFarmer = TelegramFarmer(context)

    suspend fun runCycle() = coroutineScope {
        log("🔄 Starting agent cycle...")
        try {
            val setupState = prefs.setupStateFlow.firstOrNull() ?: return@coroutineScope
            if (!setupState.isInitialized || !setupState.modelDownloaded) {
                log("⏳ Setup not complete. Skipping cycle.")
                return@coroutineScope
            }

            val configs = prefs.strategyConfigsFlow.firstOrNull() ?: AllStrategyConfigs()
            val credentials = walletManager.getOrCreateWallet()
            walletManager.refreshWalletState(credentials)

            val walletState = prefs.walletStateFlow.firstOrNull() ?: WalletState()
            val usdcBalance = walletState.usdcBalance
            val maticBalance = walletState.maticBalance

            log("💰 Wallet: \$${String.format("%.2f", usdcBalance)} USDC | ${String.format("%.4f", maticBalance)} MATIC")

            // ── Phase 1: Bootstrap if needed ──────────────────────────────────
            if (usdcBalance < 1.0) {
                log("🪣 Balance low — running full bootstrap...", StrategyType.FAUCET_BOOTSTRAP)

                // Tier A: Telegram MTProto farming (real user account)
                if (telegramFarmer.hasSessionString()) {
                    log("📱 Starting Telegram MTProto farming cycle...", StrategyType.FAUCET_BOOTSTRAP)
                    val farmResults = telegramFarmer.runFarmingCycle(credentials.address)
                    val successCount = farmResults.count { it.success }
                    farmResults.forEach { result ->
                        log(result.message, StrategyType.FAUCET_BOOTSTRAP, result.message.contains("Error") || result.message.contains("failed", true))
                    }
                    log("📊 Telegram farming: $successCount/${farmResults.size} bots claimed", StrategyType.FAUCET_BOOTSTRAP)
                } else {
                    log("📱 Telegram farming inactive — add session string in Settings", StrategyType.FAUCET_BOOTSTRAP)
                }

                // Tier B: HTTP faucets + airdrop scanner
                val faucetResults = faucetManager.claimAll(credentials.address)
                faucetResults.forEach { result ->
                    log(
                        if (result.success) "✅ ${result.source} → ${result.amount}"
                        else "⚠️ ${result.source}: ${result.message}",
                        StrategyType.FAUCET_BOOTSTRAP
                    )
                }

                walletManager.refreshWalletState(credentials)
            }

            // ── Phase 2: Load LLM ──────────────────────────────────────────────
            if (setupState.selectedModelId.isNotEmpty()) {
                val loaded = llmEngine.loadModel(setupState.selectedModelId)
                if (loaded) log("🧠 LLM loaded: ${setupState.selectedModelId}")
                else log("⚠️ LLM not loaded — using rule-based decisions", StrategyType.FAUCET_BOOTSTRAP)
            }

            // ── Phase 3: Find signals ──────────────────────────────────────────
            log("🔍 Scanning markets for signals...")
            val allSignals = mutableListOf<MarketSignal>()

            val weatherJob = async { WeatherStrategy(polyClient, configs.weather).findSignals(usdcBalance) }
            val whaleJob = async { WhaleCopyStrategy(polyClient, configs.whaleCopy).findSignals(usdcBalance) }
            val contraJob = async { CrowdContraStrategy(polyClient, configs.crowdContra).findSignals(usdcBalance) }
            val latencyJob = async { LatencyArbStrategy(polyClient, configs.latencyArb).findSignals(usdcBalance) }

            allSignals += weatherJob.await()
            allSignals += whaleJob.await()
            allSignals += contraJob.await()
            allSignals += latencyJob.await()

            log("📊 Found ${allSignals.size} potential signals")

            // ── Phase 4: LLM validation ────────────────────────────────────────
            val topSignals = allSignals.sortedByDescending { it.edge }.take(5)
            val approvedSignals = mutableListOf<MarketSignal>()

            for (signal in topSignals) {
                val decision = llmEngine.decide(
                    context_description = """
                        Market: ${signal.question}
                        Strategy: ${signal.strategy.name}
                        Our estimated probability: ${(signal.estimatedTrueProb * 100).toInt()}%
                        Market implied probability: ${(signal.marketImpliedProb * 100).toInt()}%
                        Edge: ${(signal.edge * 100).toInt()}%
                        Suggested bet: \$${String.format("%.2f", signal.suggestedSizeUsdc)} USDC
                        Direction: ${signal.side}
                        Reasoning: ${signal.reasoning}
                        Current wallet: \$${String.format("%.2f", usdcBalance)} USDC
                    """.trimIndent(),
                    question = "Should we place this trade?"
                )

                if (decision.verdict == "YES" && decision.confidence >= 0.5) {
                    approvedSignals.add(signal)
                    log("✅ Approved: ${signal.question.take(50)}... [${decision.reasoning.take(60)}]", signal.strategy)
                } else {
                    log("❌ Skipped: ${signal.question.take(50)}... [${decision.reasoning.take(60)}]", signal.strategy)
                }
            }

            // ── Phase 5: Execute trades ────────────────────────────────────────
            if (approvedSignals.isNotEmpty() && usdcBalance >= 1.0) {
                log("⚡ Executing ${approvedSignals.size} approved trades...")
                for (signal in approvedSignals.take(3)) {
                    executeTrade(signal, credentials.address)
                }
            } else if (usdcBalance < 1.0) {
                log("💸 Insufficient balance for trading — bootstrap still running")
            }

            // ── Phase 6: Daily earnings send ───────────────────────────────────
            checkAndSendDailyEarnings(credentials, walletState)

            log("✅ Cycle complete.")
            prefs.setEngineRunning(true)

        } catch (e: Exception) {
            log("💥 Cycle error: ${e.message}", isError = true)
        }
    }

    private suspend fun executeTrade(signal: MarketSignal, walletAddress: String) {
        val trade = TradeRecord(
            id = UUID.randomUUID().toString(),
            strategy = signal.strategy,
            marketId = signal.marketId,
            marketQuestion = signal.question,
            side = signal.side,
            size = signal.suggestedSizeUsdc,
            entryPrice = signal.marketImpliedProb,
            exitPrice = null,
            pnl = null,
            status = TradeStatus.OPEN,
            timestamp = System.currentTimeMillis()
        )
        prefs.appendTrade(trade)
        log("📝 Trade opened: ${signal.side} ${signal.question.take(40)}... | \$${String.format("%.2f", signal.suggestedSizeUsdc)}", signal.strategy)
    }

    private suspend fun checkAndSendDailyEarnings(
        credentials: org.web3j.crypto.Credentials,
        walletState: WalletState
    ) {
        val lastSend = prefs.lastDailySendFlow.firstOrNull() ?: 0L
        val now = System.currentTimeMillis()
        val twentyFourHours = 24 * 60 * 60 * 1000L
        if (now - lastSend < twentyFourHours) return
        if (walletState.userWalletAddress.isEmpty()) return

        val trades = prefs.tradeHistoryFlow.firstOrNull() ?: emptyList()
        val todayStart = now - twentyFourHours
        val todayTrades = trades.filter { it.timestamp >= todayStart && it.status != TradeStatus.OPEN }
        val dailyPnl = todayTrades.sumOf { it.pnl ?: 0.0 }

        if (dailyPnl <= 0.5) {
            log("📊 Daily P&L: \$${String.format("%.2f", dailyPnl)} — below \$0.50 minimum send threshold")
            return
        }

        val toSend = dailyPnl * 0.50
        log("💸 Sending 50% of \$${String.format("%.2f", dailyPnl)} → \$${String.format("%.2f", toSend)} to your wallet...")

        val txHash = walletManager.transferUsdc(credentials, walletState.userWalletAddress, toSend)
        if (txHash != null) {
            log("✅ Sent \$${String.format("%.2f", toSend)} USDC → ${walletState.userWalletAddress.take(10)}... TX: $txHash")
            prefs.setLastDailySend(now)
            val current = prefs.walletStateFlow.firstOrNull() ?: WalletState()
            prefs.saveWalletState(current.copy(
                totalEarned = current.totalEarned + dailyPnl,
                totalSentToUser = current.totalSentToUser + toSend
            ))
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
            prefs.upsertDailyEarnings(DailyEarnings(
                date = dateStr,
                grossPnl = dailyPnl,
                sentToUser = toSend,
                retained = dailyPnl - toSend,
                tradesCount = todayTrades.size,
                winRate = if (todayTrades.isEmpty()) 0.0 else
                    todayTrades.count { it.status == TradeStatus.CLOSED_WIN }.toDouble() / todayTrades.size
            ))
        } else {
            log("⚠️ Failed to send daily earnings — will retry next cycle", isError = true)
        }
    }

    private suspend fun log(message: String, strategy: StrategyType? = null, isError: Boolean = false) {
        prefs.appendLog(AgentCycleLog(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            phase = strategy?.name ?: "ORCHESTRATOR",
            message = message,
            strategy = strategy,
            isError = isError
        ))
    }
}
