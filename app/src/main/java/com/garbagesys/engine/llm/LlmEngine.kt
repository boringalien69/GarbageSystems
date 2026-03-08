package com.garbagesys.engine.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.garbagesys.data.models.LlmModelInfo
import com.garbagesys.data.models.RECOMMENDED_MODELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/**
 * LlmEngine — REAL on-device GGUF inference via MediaPipe LLM Inference API
 *
 * Uses Google's MediaPipe Tasks GenAI library which:
 * - Runs GGUF models natively on Android (CPU + GPU acceleration)
 * - No subprocess, no SELinux issues, proper JNI integration
 * - Supports Gemma, Mistral, Llama, DeepSeek GGUF models
 * - Works on arm64 Android 9+ (our target)
 *
 * Falls back to rule-based decisions if model not loaded.
 */
class LlmEngine(private val context: Context) {

    private val TAG = "LlmEngine"
    private val modelDir: File get() = File(context.filesDir, "models")

    private var llmInference: LlmInference? = null
    private var currentModelId: String? = null

    // ── RAM detection ──────────────────────────────────────────────────────────
    fun getAvailableRamGb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalRamMb = info.totalMem / (1024 * 1024)
        val usableRamMb = (totalRamMb * 0.6).toInt()
        return usableRamMb / 1024
    }

    fun recommendModel(): LlmModelInfo {
        val availableGb = getAvailableRamGb()
        return RECOMMENDED_MODELS
            .filter { it.minRamGb <= availableGb }
            .maxByOrNull { it.minRamGb }
            ?: RECOMMENDED_MODELS.first()
    }

    fun getModelFile(modelId: String): File {
        val model = RECOMMENDED_MODELS.find { it.id == modelId } ?: RECOMMENDED_MODELS.first()
        return File(modelDir, model.filename)
    }

    fun isModelDownloaded(modelId: String): Boolean = getModelFile(modelId).exists()

    // ── Model loading ──────────────────────────────────────────────────────────
    suspend fun loadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        if (currentModelId == modelId && llmInference != null) {
            Log.i(TAG, "Model $modelId already loaded")
            return@withContext true
        }

        val modelFile = getModelFile(modelId)
        if (!modelFile.exists()) {
            Log.w(TAG, "Model file not found: ${modelFile.absolutePath}")
            return@withContext false
        }

        try {
            // Close existing instance if any
            llmInference?.close()
            llmInference = null

            val options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setTopK(40)
                .setTemperature(0.1f)        // Low temp for consistent trading decisions
                .setRandomSeed(42)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            currentModelId = modelId
            Log.i(TAG, "✅ Model loaded: $modelId (${modelFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}")
            llmInference = null
            false
        }
    }

    // ── Core inference ─────────────────────────────────────────────────────────
    private suspend fun runInference(prompt: String): String? = withContext(Dispatchers.IO) {
        val engine = llmInference
        if (engine == null) {
            Log.w(TAG, "No model loaded — using rule-based fallback")
            return@withContext null
        }
        try {
            val result = engine.generateResponse(prompt)
            Log.d(TAG, "Inference: ${result.take(150)}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            null
        }
    }

    // ── Trading decision ───────────────────────────────────────────────────────
    suspend fun decide(context_description: String, question: String): LlmDecision =
        withContext(Dispatchers.IO) {

            // Try real LLM inference first
            val engine = llmInference
            if (engine != null) {
                val prompt = buildString {
                    append("<start_of_turn>user\n")
                    append("You are an autonomous prediction market trading AI.\n")
                    append("Analyze this signal and respond with ONLY JSON, no other text.\n")
                    append("Format: {\"verdict\": \"YES\" or \"NO\", \"confidence\": 0.0-1.0, \"reasoning\": \"brief\"}\n\n")
                    append("Signal:\n")
                    append(context_description)
                    append("\n\nQuestion: $question")
                    append("<end_of_turn>\n<start_of_turn>model\n")
                }

                val output = runInference(prompt)
                if (output != null) {
                    try {
                        val jsonStr = extractJson(output)
                        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonStr).jsonObject
                        return@withContext LlmDecision(
                            verdict = json["verdict"]?.jsonPrimitive?.content ?: "NO",
                            confidence = json["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.5,
                            reasoning = json["reasoning"]?.jsonPrimitive?.content ?: "LLM decision"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON parse failed, extracting from text: ${output.take(100)}")
                        // Try extracting verdict from raw text
                        val verdict = when {
                            output.contains("\"YES\"") || output.contains("YES") -> "YES"
                            else -> "NO"
                        }
                        val confMatch = Regex(""""confidence":\s*([\d.]+)""").find(output)
                        val conf = confMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.5
                        return@withContext LlmDecision(verdict, conf, output.take(100))
                    }
                }
            }

            // Rule-based fallback — still makes real decisions
            ruleBasedDecision(context_description)
        }

    // ── Bootstrap evaluation ───────────────────────────────────────────────────
    /**
     * LLM evaluates an airdrop/faucet opportunity and scores it 0-1.
     * Used by FaucetManager to prioritize which opportunities to pursue.
     */
    suspend fun evaluateBootstrapOpportunity(description: String): Pair<Double, String> =
        withContext(Dispatchers.IO) {
            val engine = llmInference
            if (engine != null) {
                val prompt = buildString {
                    append("<start_of_turn>user\n")
                    append("You are a crypto airdrop analyst. Score this opportunity 0.0-1.0.\n")
                    append("High (0.7-1.0): Real token, Polygon chain, auto-claimable, no captcha\n")
                    append("Medium (0.4-0.6): Real token, requires some action\n")
                    append("Low (0.0-0.3): Captcha required, unknown token, likely scam\n")
                    append("Respond ONLY with JSON: {\"score\": 0.0-1.0, \"reasoning\": \"brief\"}\n\n")
                    append("Opportunity: $description")
                    append("<end_of_turn>\n<start_of_turn>model\n")
                }

                val output = runInference(prompt)
                if (output != null) {
                    try {
                        val jsonStr = extractJson(output)
                        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonStr).jsonObject
                        val score = json["score"]?.jsonPrimitive?.doubleOrNull ?: 0.3
                        val reasoning = json["reasoning"]?.jsonPrimitive?.content ?: ""
                        return@withContext Pair(score.coerceIn(0.0, 1.0), reasoning)
                    } catch (e: Exception) {
                        // fall through to rule-based
                    }
                }
            }

            // Rule-based scoring
            val score = when {
                description.contains("polygon", true) && !description.contains("captcha", true) -> 0.8
                description.contains("matic", true) -> 0.7
                description.contains("ton", true) || description.contains("claim", true) -> 0.55
                description.contains("captcha", true) -> 0.15
                description.contains("scam", true) || description.contains("fake", true) -> 0.0
                else -> 0.35
            }
            Pair(score, "Rule-based score (model not loaded)")
        }

    // ── General analysis ───────────────────────────────────────────────────────
    suspend fun analyze(systemPrompt: String, userPrompt: String): String =
        withContext(Dispatchers.IO) {
            val engine = llmInference ?: return@withContext """{"verdict":"SKIP","confidence":0.0,"reasoning":"Model not loaded"}"""

            val prompt = buildString {
                append("<start_of_turn>user\n")
                append(systemPrompt.trim())
                append("\n\n")
                append(userPrompt.trim())
                append("<end_of_turn>\n<start_of_turn>model\n")
            }

            runInference(prompt) ?: """{"verdict":"SKIP","confidence":0.0,"reasoning":"Inference failed"}"""
        }

    // ── Rule-based fallback ────────────────────────────────────────────────────
    private fun ruleBasedDecision(contextDescription: String): LlmDecision {
        val edgeMatch = Regex("""Edge:\s*(\d+)%""").find(contextDescription)
        val edge = edgeMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        val sizeMatch = Regex("""Suggested bet:\s*\$?([\d.]+)""").find(contextDescription)
        val size = sizeMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        val walletMatch = Regex("""wallet:\s*\$?([\d.]+)""").find(contextDescription)
        val wallet = walletMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0

        val positionPercent = if (wallet > 0) (size / wallet) * 100 else 100.0

        return when {
            edge >= 20 && positionPercent <= 20 -> LlmDecision("YES", 0.85, "Strong edge ${edge.toInt()}%, position ${positionPercent.toInt()}% of bankroll")
            edge >= 15 && positionPercent <= 15 -> LlmDecision("YES", 0.70, "Good edge ${edge.toInt()}%, acceptable size")
            edge >= 10 && positionPercent <= 10 -> LlmDecision("YES", 0.55, "Marginal edge ${edge.toInt()}%, small position only")
            edge < 10 -> LlmDecision("NO", 0.80, "Edge too small: ${edge.toInt()}%")
            positionPercent > 25 -> LlmDecision("NO", 0.75, "Position too large: ${positionPercent.toInt()}% of bankroll")
            else -> LlmDecision("NO", 0.60, "Risk/reward not favorable")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) throw IllegalArgumentException("No JSON in: ${text.take(100)}")
        return text.substring(start, end + 1)
    }

    fun close() {
        llmInference?.close()
        llmInference = null
        currentModelId = null
    }
}

data class LlmDecision(
    val verdict: String,
    val confidence: Double,
    val reasoning: String
)
