package com.garbagesys.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garbagesys.data.db.PreferencesRepository
import com.garbagesys.data.models.*
import com.garbagesys.engine.agent.AgentOrchestrator
import com.garbagesys.engine.faucet.FaucetManager
import com.garbagesys.engine.llm.LlmEngine
import com.garbagesys.engine.llm.ModelDownloadManager
import com.garbagesys.engine.wallet.WalletManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesRepository(application)
    val walletManager = WalletManager(application)
    val llmEngine = LlmEngine(application)
    val downloadManager = ModelDownloadManager(application)
    val orchestrator = AgentOrchestrator(application)
    private val faucetManager = FaucetManager(application)

    val setupState: StateFlow<AppSetupState> = prefs.setupStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSetupState())

    val walletState: StateFlow<WalletState> = prefs.walletStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WalletState())

    val cycleLogs: StateFlow<List<AgentCycleLog>> = prefs.cycleLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tradeHistory: StateFlow<List<TradeRecord>> = prefs.tradeHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyEarnings: StateFlow<List<DailyEarnings>> = prefs.dailyEarningsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val strategyConfigs: StateFlow<AllStrategyConfigs> = prefs.strategyConfigsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AllStrategyConfigs())

    val engineRunning: StateFlow<Boolean> = prefs.engineRunningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val whaleWallets: StateFlow<List<WhaleWallet>> = prefs.whaleWalletsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Airdrop opportunities ──
    private val _airdropOpportunities = MutableStateFlow<List<AirdropOpportunity>>(emptyList())
    val airdropOpportunities: StateFlow<List<AirdropOpportunity>> = _airdropOpportunities

    init {
        _airdropOpportunities.value = faucetManager.loadAirdrops()
    }

    private val _downloadProgress = MutableStateFlow(0.0)
    val downloadProgress: StateFlow<Double> = _downloadProgress

    private val _downloadMessage = MutableStateFlow("")
    val downloadMessage: StateFlow<String> = _downloadMessage

    val availableRamGb: Int get() = llmEngine.getAvailableRamGb()
    val recommendedModel: LlmModelInfo get() = llmEngine.recommendModel()

    fun completeSetup(modelId: String, userWalletAddress: String) {
        viewModelScope.launch {
            val current = prefs.setupStateFlow.firstOrNull() ?: AppSetupState()
            prefs.saveSetupState(current.copy(
                isInitialized = true,
                selectedModelId = modelId,
                userWalletSet = userWalletAddress.isNotEmpty(),
                setupCompletedAt = System.currentTimeMillis()
            ))
            val walletState = prefs.walletStateFlow.firstOrNull() ?: WalletState()
            prefs.saveWalletState(walletState.copy(userWalletAddress = userWalletAddress))
        }
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            val model = RECOMMENDED_MODELS.find { it.id == modelId } ?: return@launch
            downloadManager.downloadModel(model) { progress, message ->
                _downloadProgress.value = progress
                _downloadMessage.value = message
            }
            if (_downloadProgress.value >= 1.0) {
                val current = prefs.setupStateFlow.firstOrNull() ?: AppSetupState()
                prefs.saveSetupState(current.copy(modelDownloaded = true, selectedModelId = modelId))
            }
        }
    }

    fun saveUserWallet(address: String) {
        viewModelScope.launch {
            val current = prefs.walletStateFlow.firstOrNull() ?: WalletState()
            prefs.saveWalletState(current.copy(userWalletAddress = address))
            val setupCurrent = prefs.setupStateFlow.firstOrNull() ?: AppSetupState()
            prefs.saveSetupState(setupCurrent.copy(userWalletSet = true))
        }
    }

    fun updateStrategyConfig(configs: AllStrategyConfigs) {
        viewModelScope.launch { prefs.saveStrategyConfigs(configs) }
    }

    fun runCycleNow() {
        viewModelScope.launch {
            orchestrator.runCycle()
            _airdropOpportunities.value = faucetManager.loadAirdrops()
        }
    }

    fun refreshWallet() {
        viewModelScope.launch {
            val creds = walletManager.getOrCreateWallet()
            walletManager.refreshWalletState(creds)
        }
    }

    fun scanAirdrops() {
        viewModelScope.launch {
            val wallet = prefs.walletStateFlow.firstOrNull() ?: WalletState()
            if (wallet.address.isEmpty()) return@launch
            faucetManager.claimAll(wallet.address)
            _airdropOpportunities.value = faucetManager.loadAirdrops()
        }
    }

    private suspend fun <T> Flow<T>.firstOrNull(): T? {
        return try { first() } catch (e: Exception) { null }
    }
}
