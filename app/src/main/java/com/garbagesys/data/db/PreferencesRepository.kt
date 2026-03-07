package com.garbagesys.data.db

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.garbagesys.data.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "garbagesys_prefs")

class PreferencesRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        val KEY_SETUP_STATE    = stringPreferencesKey("setup_state")
        val KEY_WALLET_STATE   = stringPreferencesKey("wallet_state")
        val KEY_STRATEGY_CFG   = stringPreferencesKey("strategy_configs")
        val KEY_TRADE_HISTORY  = stringPreferencesKey("trade_history")
        val KEY_CYCLE_LOGS     = stringPreferencesKey("cycle_logs")
        val KEY_DAILY_EARNINGS = stringPreferencesKey("daily_earnings")
        val KEY_WHALE_WALLETS  = stringPreferencesKey("whale_wallets")
        val KEY_ENCRYPTED_KEY  = stringPreferencesKey("enc_wallet_key") // encrypted by AndroidKeystore
        val KEY_ENGINE_RUNNING = booleanPreferencesKey("engine_running")
        val KEY_LAST_DAILY_SEND = longPreferencesKey("last_daily_send")
    }

    // ── Setup State ──
    val setupStateFlow: Flow<AppSetupState> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY_SETUP_STATE]?.let { json.decodeFromString(it) } ?: AppSetupState()
        }

    suspend fun saveSetupState(state: AppSetupState) {
        context.dataStore.edit { it[KEY_SETUP_STATE] = json.encodeToString(state) }
    }

    // ── Wallet State ──
    val walletStateFlow: Flow<WalletState> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY_WALLET_STATE]?.let { json.decodeFromString(it) } ?: WalletState()
        }

    suspend fun saveWalletState(state: WalletState) {
        context.dataStore.edit { it[KEY_WALLET_STATE] = json.encodeToString(state) }
    }

    // ── Strategy Configs ──
    val strategyConfigsFlow: Flow<AllStrategyConfigs> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY_STRATEGY_CFG]?.let { json.decodeFromString(it) } ?: AllStrategyConfigs()
        }

    suspend fun saveStrategyConfigs(cfg: AllStrategyConfigs) {
        context.dataStore.edit { it[KEY_STRATEGY_CFG] = json.encodeToString(cfg) }
    }

    // ── Trade History ──
    val tradeHistoryFlow: Flow<List<TradeRecord>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY_TRADE_HISTORY]?.let { json.decodeFromString(it) } ?: emptyList()
        }

    suspend fun appendTrade(trade: TradeRecord) {
        context.dataStore.edit { prefs ->
            val current: List<TradeRecord> = prefs[KEY_TRADE_HISTORY]
                ?.let { json.decodeFromString(it) } ?: emptyList()
            val updated = (current + trade).takeLast(500) // keep last 500
            prefs[KEY_TRADE_HISTORY] = json.encodeToString(updated)
        }
    }

    suspend fun updateTrade(trade: TradeRecord) {
        context.dataStore.edit { prefs ->
            val current: List<TradeRecord> = prefs[KEY_TRADE_HISTORY]
                ?.let { json.decodeFromString(it) } ?: emptyList()
            val updated = current.map { if (it.id == trade.id) trade else it }
            prefs[KEY_TRADE_HISTORY] = json.encodeToString(updated)
        }
    }

    // ── Cycle Logs ──
    val cycleLogsFlow: Flow<List<AgentCycleLog>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY_CYCLE_LOGS]?.let { json.decodeFromString(it) } ?: emptyList()
        }

    suspend fun appendLog(log: AgentCycleLog) {
        context.dataStore.edit { prefs ->
            val current: List<AgentCycleLog> = prefs[KEY_CYCLE_LOGS]
                ?.let { json.decodeFromString(it) } ?: emptyList()
            val updated = (current + log).takeLast(200)
            prefs[KEY_CYCLE_LOGS] = json.encodeToString(updated)
        }
    }

    // ── Daily Earnings ──
    val dailyEarningsFlow: Flow<List<DailyEarnings>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY_DAILY_EARNINGS]?.let { json.decodeFromString(it) } ?: emptyList()
        }

    suspend fun upsertDailyEarnings(entry: DailyEarnings) {
        context.dataStore.edit { prefs ->
            val current: List<DailyEarnings> = prefs[KEY_DAILY_EARNINGS]
                ?.let { json.decodeFromString(it) } ?: emptyList()
            val updated = current.filter { it.date != entry.date } + entry
            prefs[KEY_DAILY_EARNINGS] = json.encodeToString(updated.takeLast(90))
        }
    }

    // ── Whale Wallets ──
    val whaleWalletsFlow: Flow<List<WhaleWallet>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY_WHALE_WALLETS]?.let { json.decodeFromString(it) } ?: emptyList()
        }

    suspend fun saveWhaleWallets(wallets: List<WhaleWallet>) {
        context.dataStore.edit { it[KEY_WHALE_WALLETS] = json.encodeToString(wallets) }
    }

    // ── Engine Running ──
    val engineRunningFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_ENGINE_RUNNING] ?: false }

    suspend fun setEngineRunning(running: Boolean) {
        context.dataStore.edit { it[KEY_ENGINE_RUNNING] = running }
    }

    // ── Last Daily Send Timestamp ──
    val lastDailySendFlow: Flow<Long> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_LAST_DAILY_SEND] ?: 0L }

    suspend fun setLastDailySend(timestamp: Long) {
        context.dataStore.edit { it[KEY_LAST_DAILY_SEND] = timestamp }
    }
}
