package com.garbagesys.data.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.garbagesys.engine.faucet.AirdropInfo
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "garbagesys_prefs")

class PreferencesRepository(private val context: Context) {
    companion object {
        val KEY_WALLET_ENCRYPTED   = stringPreferencesKey("wallet_encrypted")
        val KEY_WALLET_IV          = stringPreferencesKey("wallet_iv")
        val KEY_WALLET_ADDRESS     = stringPreferencesKey("wallet_address")
        val KEY_RECEIVING_WALLET   = stringPreferencesKey("receiving_wallet")
        val KEY_SETUP_COMPLETE     = booleanPreferencesKey("setup_complete")
        val KEY_SELECTED_MODEL     = stringPreferencesKey("selected_model")
        val KEY_TOTAL_EARNED       = doublePreferencesKey("total_earned")
        val KEY_TOTAL_SENT         = doublePreferencesKey("total_sent")
        val KEY_LAST_CYCLE         = longPreferencesKey("last_cycle")
        val KEY_LAST_DAILY_SEND    = longPreferencesKey("last_daily_send")
        val KEY_AGENT_LOGS         = stringPreferencesKey("agent_logs")
        val KEY_TRADE_HISTORY      = stringPreferencesKey("trade_history")
        val KEY_FAUCET_COOLDOWNS   = stringPreferencesKey("faucet_cooldowns")
        val KEY_DISCOVERED_FAUCETS = stringPreferencesKey("discovered_faucets")
        val KEY_PENDING_AIRDROPS   = stringPreferencesKey("pending_airdrops")
        val KEY_SUBMITTED_AIRDROPS = stringPreferencesKey("submitted_airdrops")
    }

    suspend fun saveWalletEncrypted(encrypted: String, iv: String, address: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WALLET_ENCRYPTED] = encrypted
            prefs[KEY_WALLET_IV] = iv
            prefs[KEY_WALLET_ADDRESS] = address
        }
    }
    suspend fun getWalletEncrypted(): Pair<String,String>? {
        val prefs = context.dataStore.data.first()
        val enc = prefs[KEY_WALLET_ENCRYPTED] ?: return null
        val iv = prefs[KEY_WALLET_IV] ?: return null
        return Pair(enc,iv)
    }
    suspend fun getWalletAddress(): String = context.dataStore.data.first()[KEY_WALLET_ADDRESS] ?: ""
    suspend fun getReceivingWallet(): String = context.dataStore.data.first()[KEY_RECEIVING_WALLET] ?: ""
    suspend fun setReceivingWallet(address: String) { context.dataStore.edit { it[KEY_RECEIVING_WALLET] = address } }
    suspend fun isSetupComplete(): Boolean = context.dataStore.data.first()[KEY_SETUP_COMPLETE] ?: false
    suspend fun setSetupComplete(complete: Boolean) { context.dataStore.edit { it[KEY_SETUP_COMPLETE] = complete } }
    suspend fun getSelectedModel(): String = context.dataStore.data.first()[KEY_SELECTED_MODEL] ?: ""
    suspend fun setSelectedModel(modelId: String) { context.dataStore.edit { it[KEY_SELECTED_MODEL] = modelId } }
    suspend fun getTotalEarned(): Double = context.dataStore.data.first()[KEY_TOTAL_EARNED] ?: 0.0
    suspend fun addEarnings(amount: Double) { context.dataStore.edit { prefs -> prefs[KEY_TOTAL_EARNED] = (prefs[KEY_TOTAL_EARNED] ?: 0.0) + amount } }
    suspend fun getTotalSent(): Double = context.dataStore.data.first()[KEY_TOTAL_SENT] ?: 0.0
    suspend fun addSent(amount: Double) { context.dataStore.edit { prefs -> prefs[KEY_TOTAL_SENT] = (prefs[KEY_TOTAL_SENT] ?: 0.0) + amount } }
    suspend fun getLastDailySend(): Long = context.dataStore.data.first()[KEY_LAST_DAILY_SEND] ?: 0L
    suspend fun setLastDailySend(time: Long) { context.dataStore.edit { it[KEY_LAST_DAILY_SEND] = time } }
    suspend fun getLastCycle(): Long = context.dataStore.data.first()[KEY_LAST_CYCLE] ?: 0L
    suspend fun setLastCycle(time: Long) { context.dataStore.edit { it[KEY_LAST_CYCLE] = time } }

    suspend fun addAgentLog(entry: String) {
        context.dataStore.edit { prefs ->
            val arr = try { JSONArray(prefs[KEY_AGENT_LOGS] ?: "[]") } catch (e: Exception) { JSONArray() }
            while (arr.length() >= 200) arr.remove(0)
            arr.put(JSONObject().apply { put("time", System.currentTimeMillis()); put("msg", entry) })
            prefs[KEY_AGENT_LOGS] = arr.toString()
        }
    }
    suspend fun getAgentLogs(): List<Pair<Long,String>> {
        val raw = context.dataStore.data.first()[KEY_AGENT_LOGS] ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Pair(it.getLong("time"), it.getString("msg")) } }.reversed()
        } catch (e: Exception) { emptyList() }
    }
    suspend fun addTradeRecord(trade: String) {
        context.dataStore.edit { prefs ->
            val arr = try { JSONArray(prefs[KEY_TRADE_HISTORY] ?: "[]") } catch (e: Exception) { JSONArray() }
            while (arr.length() >= 100) arr.remove(0)
            arr.put(trade)
            prefs[KEY_TRADE_HISTORY] = arr.toString()
        }
    }
    suspend fun getTradeHistory(): List<String> {
        val raw = context.dataStore.data.first()[KEY_TRADE_HISTORY] ?: return emptyList()
        return try { val arr = JSONArray(raw); (0 until arr.length()).map { arr.getString(it) }.reversed() } catch (e: Exception) { emptyList() }
    }
    suspend fun getFaucetLastClaim(faucetName: String): Long {
        val raw = context.dataStore.data.first()[KEY_FAUCET_COOLDOWNS] ?: return 0L
        return try { JSONObject(raw).optLong(faucetName, 0L) } catch (e: Exception) { 0L }
    }
    suspend fun setFaucetLastClaim(faucetName: String, time: Long) {
        context.dataStore.edit { prefs ->
            val obj = try { JSONObject(prefs[KEY_FAUCET_COOLDOWNS] ?: "{}") } catch (e: Exception) { JSONObject() }
            obj.put(faucetName, time)
            prefs[KEY_FAUCET_COOLDOWNS] = obj.toString()
        }
    }
    suspend fun addDiscoveredFaucets(urls: List<String>) {
        context.dataStore.edit { prefs ->
            val arr = try { JSONArray(prefs[KEY_DISCOVERED_FAUCETS] ?: "[]") } catch (e: Exception) { JSONArray() }
            urls.forEach { url -> if ((0 until arr.length()).none { arr.getString(it) == url }) arr.put(url) }
            prefs[KEY_DISCOVERED_FAUCETS] = arr.toString()
        }
    }
    suspend fun getDiscoveredFaucets(): List<String> {
        val raw = context.dataStore.data.first()[KEY_DISCOVERED_FAUCETS] ?: return emptyList()
        return try { val arr = JSONArray(raw); (0 until arr.length()).map { arr.getString(it) } } catch (e: Exception) { emptyList() }
    }
    suspend fun addPendingAirdrop(airdrop: AirdropInfo) {
        context.dataStore.edit { prefs ->
            val arr = try { JSONArray(prefs[KEY_PENDING_AIRDROPS] ?: "[]") } catch (e: Exception) { JSONArray() }
            val exists = (0 until arr.length()).any { try { arr.getJSONObject(it).getString("id") == airdrop.id } catch (e: Exception) { false } }
            if (!exists) arr.put(JSONObject().apply { put("id",airdrop.id); put("name",airdrop.name); put("description",airdrop.description); put("url",airdrop.url); put("network",airdrop.network) })
            prefs[KEY_PENDING_AIRDROPS] = arr.toString()
        }
    }
    suspend fun getPendingAirdrops(): List<String> {
        val raw = context.dataStore.data.first()[KEY_PENDING_AIRDROPS] ?: return emptyList()
        return try { val arr = JSONArray(raw); (0 until arr.length()).map { arr.getJSONObject(it).let { o -> "${o.getString("name")}: ${o.getString("description")}" } } } catch (e: Exception) { emptyList() }
    }
    suspend fun isAirdropSubmitted(id: String): Boolean {
        val raw = context.dataStore.data.first()[KEY_SUBMITTED_AIRDROPS] ?: return false
        return try { val arr = JSONArray(raw); (0 until arr.length()).any { arr.getString(it) == id } } catch (e: Exception) { false }
    }
    suspend fun markAirdropSubmitted(id: String) {
        context.dataStore.edit { prefs ->
            val arr = try { JSONArray(prefs[KEY_SUBMITTED_AIRDROPS] ?: "[]") } catch (e: Exception) { JSONArray() }
            arr.put(id)
            prefs[KEY_SUBMITTED_AIRDROPS] = arr.toString()
        }
    }
}
