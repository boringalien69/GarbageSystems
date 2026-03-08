package com.garbagesys.engine.bootstrap

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TelegramFarmer — Real Mini App farming via direct HTTP
 *
 * Each farming bot is a Telegram Mini App. Their backends accept
 * HTTP requests authenticated with the user's initData string.
 *
 * Flow:
 * 1. User opens each bot once in Telegram, copies the initData query string
 *    from the bot's WebApp URL (shown in Settings guide)
 * 2. App stores initData per bot in SharedPreferences
 * 3. Each farming cycle posts to each bot's real API endpoints
 * 4. No Python, no Termux, no MTProto — pure OkHttp
 *
 * initData format (URL-encoded):
 * query_id=AAH...&user=%7B%22id%22%3A7951767421...%7D&auth_date=1234567890&hash=abc123...
 */
class TelegramFarmer(private val context: Context) {

    private val TAG = "TelegramFarmer"
    private val prefs = context.getSharedPreferences("gs_tg", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // ── Storage ────────────────────────────────────────────────────────────────
    fun saveBotToken(token: String) = prefs.edit().putString("bot_token", token.trim()).apply()
    fun getBotToken(): String? = prefs.getString("bot_token", null)
    fun hasBotToken(): Boolean = !getBotToken().isNullOrEmpty()

    fun saveInitData(botKey: String, initData: String) =
        prefs.edit().putString("initdata_$botKey", initData.trim()).apply()

    fun getInitData(botKey: String): String? =
        prefs.getString("initdata_$botKey", null)

    fun hasAnyInitData(): Boolean =
        FARM_BOTS.any { !getInitData(it.key).isNullOrEmpty() }

    // Also keep session string support for future use
    fun saveSessionString(session: String) = prefs.edit().putString("session_string", session.trim()).apply()
    fun hasSessionString(): Boolean = !prefs.getString("session_string", null).isNullOrEmpty()

    // ── Bot definitions ────────────────────────────────────────────────────────
    data class FarmBot(
        val key: String,
        val displayName: String,
        val telegramLink: String,
        val cooldownHours: Int
    )

    data class FarmResult(
        val target: String,
        val success: Boolean,
        val message: String,
        val reward: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    companion object {
        val FARM_BOTS = listOf(
            FarmBot("tomarket",  "Tomarket 🍅",    "https://t.me/Tomarket_ai_bot/app?startapp=000041bn", 8),
            FarmBot("paws",      "PAWS 🐾",         "https://t.me/PAWSOG_bot/PAWS?startapp=pHJCKGh4",    24),
            FarmBot("boinker",   "Boinker 🪀",      "https://t.me/boinker_bot/boink?startapp=boink",      1),
            FarmBot("major",     "Major ⭐",         "https://t.me/major/start?startapp=start",            24),
            FarmBot("notpixel",  "NotPixel 🎨",     "https://t.me/notpixel/app?startapp=f7951767421",     24),
        )
    }

    // ── Main cycle ─────────────────────────────────────────────────────────────
    suspend fun runFarmingCycle(walletAddress: String): List<FarmResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<FarmResult>()

            if (!hasAnyInitData()) {
                return@withContext listOf(
                    FarmResult("Telegram Farming", false,
                        "No initData saved. Go to Settings → Telegram Farming → add query strings for each bot.")
                )
            }

            for (bot in FARM_BOTS) {
                val initData = getInitData(bot.key)
                if (initData.isNullOrEmpty()) {
                    results.add(FarmResult(bot.displayName, false, "Not configured — add initData in Settings"))
                    continue
                }

                // Check cooldown
                val lastClaim = prefs.getLong("farm_cd_${bot.key}", 0L)
                val cooldownMs = bot.cooldownHours * 3600 * 1000L
                val now = System.currentTimeMillis()
                if (now - lastClaim < cooldownMs) {
                    val remH = ((cooldownMs - (now - lastClaim)) / 3600000)
                    results.add(FarmResult(bot.displayName, false, "Cooldown: ${remH}h remaining"))
                    continue
                }

                try {
                    val result = when (bot.key) {
                        "tomarket" -> farmTomarket(initData, bot.displayName)
                        "paws"     -> farmPaws(initData, bot.displayName)
                        "boinker"  -> farmBoinker(initData, bot.displayName)
                        "major"    -> farmMajor(initData, bot.displayName)
                        "notpixel" -> farmNotPixel(initData, bot.displayName)
                        else -> FarmResult(bot.displayName, false, "Unknown bot")
                    }
                    if (result.success) {
                        prefs.edit().putLong("farm_cd_${bot.key}", now).apply()
                    }
                    results.add(result)
                } catch (e: Exception) {
                    Log.e(TAG, "${bot.key} error: ${e.message}")
                    results.add(FarmResult(bot.displayName, false, "Error: ${e.message?.take(60)}"))
                }

                delay(2000L)
            }

            results
        }

    // ── Tomarket ───────────────────────────────────────────────────────────────
    private suspend fun farmTomarket(initData: String, name: String): FarmResult =
        withContext(Dispatchers.IO) {
            val base = "https://api-gw.tomarket.ai/tomarket-game-v1"

            // Step 1: login to get token
            val loginBody = """{"init_data":"$initData"}""".toRequestBody(JSON_TYPE)
            val loginResp = client.newCall(
                Request.Builder().url("$base/user/login")
                    .post(loginBody)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            val loginJson = loginResp.body?.string() ?: return@withContext FarmResult(name, false, "Login failed")
            val token = json.parseToJsonElement(loginJson)
                .jsonObject["data"]?.jsonObject?.get("access_token")?.jsonPrimitive?.content
                ?: return@withContext FarmResult(name, false, "No token: ${loginJson.take(80)}")

            val authHeader = "Bearer $token"

            // Step 2: daily check-in
            client.newCall(
                Request.Builder().url("$base/daily/claim")
                    .post("{}".toRequestBody(JSON_TYPE))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()

            // Step 3: claim farm
            client.newCall(
                Request.Builder().url("$base/farm/claim")
                    .post("{}".toRequestBody(JSON_TYPE))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()

            // Step 4: start next farm
            client.newCall(
                Request.Builder().url("$base/farm/start")
                    .post("{}".toRequestBody(JSON_TYPE))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()

            // Step 5: get balance
            val balResp = client.newCall(
                Request.Builder().url("$base/user/balance")
                    .post("{}".toRequestBody(JSON_TYPE))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            val balJson = balResp.body?.string() ?: ""
            val balance = json.parseToJsonElement(balJson)
                .jsonObject["data"]?.jsonObject?.get("available_balance")?.jsonPrimitive?.content ?: "?"

            FarmResult(name, true, "✅ Claimed + farming started", "Balance: $balance TOMATO")
        }

    // ── PAWS ───────────────────────────────────────────────────────────────────
    private suspend fun farmPaws(initData: String, name: String): FarmResult =
        withContext(Dispatchers.IO) {
            val base = "https://api.paws.community/v1"

            // Auth
            val authBody = """{"data":"$initData","referralCode":"pHJCKGh4"}""".toRequestBody(JSON_TYPE)
            val authResp = client.newCall(
                Request.Builder().url("$base/user/auth")
                    .post(authBody)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            val authJson = authResp.body?.string() ?: return@withContext FarmResult(name, false, "Auth failed")
            val token = json.parseToJsonElement(authJson)
                .jsonObject["data"]?.jsonArray?.get(0)?.jsonPrimitive?.content
                ?: return@withContext FarmResult(name, false, "No token: ${authJson.take(80)}")

            val authHeader = "Bearer $token"

            // Claim daily
            val claimResp = client.newCall(
                Request.Builder().url("$base/quests/claim")
                    .post("""{"questId":"daily"}""".toRequestBody(JSON_TYPE))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            val claimOk = claimResp.isSuccessful

            // Get balance
            val userResp = client.newCall(
                Request.Builder().url("$base/user")
                    .get()
                    .header("Authorization", authHeader)
                    .build()
            ).execute()
            val userJson = userResp.body?.string() ?: ""
            val balance = try {
                json.parseToJsonElement(userJson)
                    .jsonObject["data"]?.jsonObject?.get("gameData")
                    ?.jsonObject?.get("balance")?.jsonPrimitive?.content ?: "?"
            } catch (e: Exception) { "?" }

            FarmResult(name, claimOk, if (claimOk) "✅ Daily claimed" else "Already claimed today", "Balance: $balance PAWS")
        }

    // ── Boinker ────────────────────────────────────────────────────────────────
    private suspend fun farmBoinker(initData: String, name: String): FarmResult =
        withContext(Dispatchers.IO) {
            val base = "https://boink.astronomica.io/public"

            val loginBody = """{"telegramInitData":"$initData"}""".toRequestBody(JSON_TYPE)
            val loginResp = client.newCall(
                Request.Builder().url("$base/users/loginByTelegram?p=android")
                    .post(loginBody)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            val loginJson = loginResp.body?.string() ?: return@withContext FarmResult(name, false, "Login failed")
            val token = json.parseToJsonElement(loginJson)
                .jsonObject["token"]?.jsonPrimitive?.content
                ?: return@withContext FarmResult(name, false, "No token: ${loginJson.take(80)}")

            val authHeader = "Bearer $token"

            // Tap (boing)
            val tapBody = """{"nonce":${System.currentTimeMillis()}}""".toRequestBody(JSON_TYPE)
            val tapResp = client.newCall(
                Request.Builder().url("$base/boinkers/boing")
                    .post(tapBody)
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()

            val tapJson = tapResp.body?.string() ?: ""
            val reward = try {
                json.parseToJsonElement(tapJson)
                    .jsonObject["pointsGained"]?.jsonPrimitive?.content ?: "?"
            } catch (e: Exception) { "?" }

            FarmResult(name, tapResp.isSuccessful, if (tapResp.isSuccessful) "✅ Tapped!" else "Tap failed", "Gained: $reward pts")
        }

    // ── Major ──────────────────────────────────────────────────────────────────
    private suspend fun farmMajor(initData: String, name: String): FarmResult =
        withContext(Dispatchers.IO) {
            val base = "https://major.bot/api"

            val authBody = """{"init_data":"$initData"}""".toRequestBody(JSON_TYPE)
            val authResp = client.newCall(
                Request.Builder().url("$base/auth/tg/")
                    .post(authBody)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()
            val authJson = authResp.body?.string() ?: return@withContext FarmResult(name, false, "Auth failed")
            val token = json.parseToJsonElement(authJson)
                .jsonObject["access_token"]?.jsonPrimitive?.content
                ?: return@withContext FarmResult(name, false, "No token: ${authJson.take(80)}")

            val authHeader = "Bearer $token"

            // Daily visit
            val visitResp = client.newCall(
                Request.Builder().url("$base/user-visits/visit/")
                    .post("{}".toRequestBody(JSON_TYPE))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .build()
            ).execute()

            val visitJson = visitResp.body?.string() ?: ""
            val streak = try {
                json.parseToJsonElement(visitJson)
                    .jsonObject["streak"]?.jsonPrimitive?.content ?: "?"
            } catch (e: Exception) { "?" }

            FarmResult(name, visitResp.isSuccessful,
                if (visitResp.isSuccessful) "✅ Daily visit done" else "Already visited",
                "Streak: $streak days")
        }

    // ── NotPixel ───────────────────────────────────────────────────────────────
    private suspend fun farmNotPixel(initData: String, name: String): FarmResult =
        withContext(Dispatchers.IO) {
            val base = "https://notpx.app/api/v1"

            val authHeader = "initData $initData"

            // Get status
            val statusResp = client.newCall(
                Request.Builder().url("$base/mining/status")
                    .get()
                    .header("Authorization", authHeader)
                    .build()
            ).execute()
            val statusJson = statusResp.body?.string() ?: return@withContext FarmResult(name, false, "Status failed")

            val balance = try {
                json.parseToJsonElement(statusJson)
                    .jsonObject["userBalance"]?.jsonPrimitive?.content ?: "?"
            } catch (e: Exception) { "?" }

            // Claim mining rewards
            val claimResp = client.newCall(
                Request.Builder().url("$base/mining/claim")
                    .get()
                    .header("Authorization", authHeader)
                    .build()
            ).execute()
            val claimJson = claimResp.body?.string() ?: ""
            val claimed = try {
                json.parseToJsonElement(claimJson)
                    .jsonObject["claimed"]?.jsonPrimitive?.content ?: "0"
            } catch (e: Exception) { "0" }

            FarmResult(name, claimResp.isSuccessful,
                if (claimResp.isSuccessful) "✅ Mining claimed" else "Nothing to claim",
                "Claimed: $claimed | Balance: $balance PX")
        }
}
