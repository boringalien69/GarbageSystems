package com.garbagesys.data.models

import kotlinx.serialization.Serializable

// ── Wallet State ──
@Serializable
data class WalletState(
    val address: String = "",
    val usdcBalance: Double = 0.0,
    val maticBalance: Double = 0.0,
    val totalEarned: Double = 0.0,
    val totalSentToUser: Double = 0.0,
    val userWalletAddress: String = "",
    val lastUpdated: Long = 0L
)

// ── Trade Record ──
@Serializable
data class TradeRecord(
    val id: String,
    val strategy: StrategyType,
    val marketId: String,
    val marketQuestion: String,
    val side: TradeSide,
    val size: Double,        // USDC
    val entryPrice: Double,  // 0.0–1.0
    val exitPrice: Double?,
    val pnl: Double?,
    val status: TradeStatus,
    val timestamp: Long,
    val txHash: String? = null
)

enum class TradeSide { YES, NO }
enum class TradeStatus { OPEN, CLOSED_WIN, CLOSED_LOSS, CANCELLED }
enum class StrategyType {
    WEATHER_BAYESIAN,
    WHALE_COPY,
    CROWD_CONTRA,        // bet against 80%+ crowd
    LATENCY_ARB,         // CEX vs Polymarket lag
    FAUCET_BOOTSTRAP,
    MANUAL_SEED
}

// ── Strategy Config ──
@Serializable
data class StrategyConfig(
    val enabled: Boolean,
    val maxPositionUsdc: Double,
    val minEdgePercent: Double,    // minimum edge to enter (e.g. 0.15 = 15%)
    val kellyFraction: Double,     // fraction of Kelly to use (0.25 = quarter Kelly, safer)
    val maxOpenPositions: Int,
    val stopLossPercent: Double    // drawdown stop per position
)

// ── Strategy Configs (defaults) ──
@Serializable
data class AllStrategyConfigs(
    val weather: StrategyConfig = StrategyConfig(
        enabled = true, maxPositionUsdc = 5.0, minEdgePercent = 0.15,
        kellyFraction = 0.25, maxOpenPositions = 5, stopLossPercent = 0.5
    ),
    val whaleCopy: StrategyConfig = StrategyConfig(
        enabled = true, maxPositionUsdc = 3.0, minEdgePercent = 0.10,
        kellyFraction = 0.20, maxOpenPositions = 3, stopLossPercent = 0.6
    ),
    val crowdContra: StrategyConfig = StrategyConfig(
        enabled = true, maxPositionUsdc = 2.0, minEdgePercent = 0.20,
        kellyFraction = 0.15, maxOpenPositions = 3, stopLossPercent = 0.5
    ),
    val latencyArb: StrategyConfig = StrategyConfig(
        enabled = true, maxPositionUsdc = 4.0, minEdgePercent = 0.05,
        kellyFraction = 0.30, maxOpenPositions = 2, stopLossPercent = 0.8
    )
)

// ── Agent Cycle Log ──
@Serializable
data class AgentCycleLog(
    val id: String,
    val timestamp: Long,
    val phase: String,
    val message: String,
    val strategy: StrategyType? = null,
    val isError: Boolean = false
)

// ── LLM Model Info ──
@Serializable
data class LlmModelInfo(
    val id: String,
    val name: String,
    val sizeGb: Double,
    val minRamGb: Int,
    val huggingFaceRepo: String,
    val filename: String,
    val description: String,
    val tokens: Int = 4096
)

val RECOMMENDED_MODELS = listOf(
    LlmModelInfo(
        id = "qwen2.5-0.5b",
        name = "Qwen 2.5 0.5B (Ultra-Light)",
        sizeGb = 0.4, minRamGb = 2,
        huggingFaceRepo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        description = "For 2–3GB RAM phones. Fast, minimal reasoning.",
        tokens = 2048
    ),
    LlmModelInfo(
        id = "llama3.2-1b",
        name = "Llama 3.2 1B (Light)",
        sizeGb = 0.7, minRamGb = 3,
        huggingFaceRepo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
        filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        description = "For 3–4GB RAM phones. Good balance.",
        tokens = 4096
    ),
    LlmModelInfo(
        id = "llama3.2-3b",
        name = "Llama 3.2 3B (Balanced)",
        sizeGb = 1.9, minRamGb = 4,
        huggingFaceRepo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
        filename = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        description = "For 4–6GB RAM phones. Sweet spot for mobile.",
        tokens = 4096
    ),
    LlmModelInfo(
        id = "llama3.1-8b",
        name = "Llama 3.1 8B (Full)",
        sizeGb = 4.7, minRamGb = 8,
        huggingFaceRepo = "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
        filename = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        description = "For 8GB+ RAM phones. Best reasoning quality.",
        tokens = 8192
    ),
    LlmModelInfo(
        id = "deepseek-r1-7b",
        name = "DeepSeek R1 7B (Best Reasoning)",
        sizeGb = 4.4, minRamGb = 8,
        huggingFaceRepo = "bartowski/DeepSeek-R1-Distill-Llama-8B-GGUF",
        filename = "DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf",
        description = "For 8GB+ RAM phones. Top-tier reasoning for strategies.",
        tokens = 8192
    ),
    LlmModelInfo(
        id = "mistral-7b",
        name = "Mistral 7B (Premium)",
        sizeGb = 4.1, minRamGb = 12,
        huggingFaceRepo = "bartowski/Mistral-7B-Instruct-v0.3-GGUF",
        filename = "Mistral-7B-Instruct-v0.3-Q5_K_M.gguf",
        description = "For 12GB+ RAM phones. Higher quality at higher quant.",
        tokens = 8192
    )
)

// ── App Setup State ──
@Serializable
data class AppSetupState(
    val isInitialized: Boolean = false,
    val modelDownloaded: Boolean = false,
    val selectedModelId: String = "",
    val walletCreated: Boolean = false,
    val userWalletSet: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val setupCompletedAt: Long = 0L
)

// ── Daily Earnings Summary ──
@Serializable
data class DailyEarnings(
    val date: String,   // YYYY-MM-DD
    val grossPnl: Double,
    val sentToUser: Double,
    val retained: Double,
    val tradesCount: Int,
    val winRate: Double
)

// ── Whale Wallet ──
@Serializable
data class WhaleWallet(
    val address: String,
    val label: String,
    val totalPnlUsdc: Double,
    val winRate: Double,
    val tradeCount: Int,
    val lastActive: Long,
    val score: Double   // composite score for ranking
)

// ── Market Signal ──
@Serializable
data class MarketSignal(
    val marketId: String,
    val question: String,
    val strategy: StrategyType,
    val side: TradeSide,
    val estimatedTrueProb: Double,    // our Bayesian estimate
    val marketImpliedProb: Double,    // what the market says
    val edge: Double,                  // estimatedTrue - marketImplied
    val suggestedSizeUsdc: Double,     // Kelly-sized
    val confidence: Double,
    val reasoning: String,
    val timestamp: Long
)
