package com.garbagesys.engine.llm

import android.content.Context
import android.app.ActivityManager
import com.garbagesys.data.models.LlmModelInfo
import com.garbagesys.data.models.RECOMMENDED_MODELS
import io.aatricks.llmedge.SmolLM
import io.aatricks.llmedge.InferenceParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LlmEngine wraps llmedge (llama.cpp JNI bindings) for on-device GGUF inference.
 * Detects available RAM and recommends appropriate model.
 * Used by AgentOrchestrator to make trading decisions.
 */
class LlmEngine(private val context: Context) {

    private var smolLM: SmolLM? = null
    private var loadedModelId: String? = null
    private val modelDir: File get() = File(context.filesDir, "models")

    // ── RAM Detection ──
    fun getAvailableRamGb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalRamMb = info.totalMem / (1024 * 1024)
        // Use 60% of total RAM as "usable for model"
        val usableRamMb = (totalRamMb * 0.6).toInt()
        return usableRamMb / 1024
    }

    fun recommendModel(): LlmModelInfo {
        val availableGb = getAvailableRamGb()
        // Find best model that fits in available RAM
        return RECOMMENDED_MODELS
            .filter { it.minRamGb <= availableGb }
            .maxByOrNull { it.minRamGb }
            ?: RECOMMENDED_MODELS.first() // fallback to smallest
    }

    fun getModelFile(modelId: String): File {
        val model = RECOMMENDED_MODELS.find { it.id == modelId }
            ?: RECOMMENDED_MODELS.first()
        return File(modelDir, model.filename)
    }

    fun isModelDownloaded(modelId: String): Boolean {
        return getModelFile(modelId).exists()
    }

    // ── Load model ──
    suspend fun loadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (loadedModelId == modelId && smolLM != null) return@withContext true
            smolLM?.close()
            smolLM = null
            val modelFile = getModelFile(modelId)
            if (!modelFile.exists()) return@withContext false
            val llm = SmolLM()
            llm.loadFromPath(modelFile.absolutePath, InferenceParams(contextSize = 4096))
            smolLM = llm
            loadedModelId = modelId
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── Inference ──
    /**
     * Ask the LLM to analyze a trading situation and return a decision.
     * Returns the full text response.
     */
    suspend fun analyze(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val llm = smolLM ?: return@withContext "ERROR: Model not loaded"
        try {
            val sb = StringBuilder()
            val fullPrompt = buildPrompt(systemPrompt, userPrompt)
            llm.generateText(
                prompt = fullPrompt,
                params = InferenceParams(
                    maxNewTokens = 512,
                    temperature = 0.1f,   // low temp for consistent decisions
                    topP = 0.9f,
                    repeatPenalty = 1.1f
                )
            ) { token ->
                sb.append(token)
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * Ask a yes/no or structured decision. Returns the LLM's reasoning + verdict.
     */
    suspend fun decide(
        context_description: String,
        question: String
    ): LlmDecision = withContext(Dispatchers.IO) {
        val systemPrompt = """You are GarbageSys, an autonomous trading AI.
You analyze prediction markets and make disciplined trading decisions.
Always respond in this exact JSON format:
{"verdict": "YES" or "NO" or "SKIP", "confidence": 0.0-1.0, "reasoning": "brief explanation"}
Be concise. Never deviate from the JSON format."""

        val userPrompt = """Context: $context_description
Question: $question
Respond only with the JSON object."""

        val response = analyze(systemPrompt, userPrompt)
        parseLlmDecision(response)
    }

    private fun parseLlmDecision(response: String): LlmDecision {
        return try {
            // Extract JSON from response
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                return LlmDecision("SKIP", 0.0, "Failed to parse response")
            }
            val json = response.substring(jsonStart, jsonEnd)
            val verdict = Regex(""""verdict"\s*:\s*"(\w+)"""").find(json)?.groupValues?.get(1) ?: "SKIP"
            val confidence = Regex(""""confidence"\s*:\s*([\d.]+)""").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val reasoning = Regex(""""reasoning"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
            LlmDecision(verdict, confidence, reasoning)
        } catch (e: Exception) {
            LlmDecision("SKIP", 0.0, "Parse error: ${e.message}")
        }
    }

    private fun buildPrompt(system: String, user: String): String {
        // Llama 3 / Instruct chat template
        return """<|begin_of_text|><|start_header_id|>system<|end_header_id|>
$system<|eot_id|><|start_header_id|>user<|end_header_id|>
$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>
"""
    }

    fun close() {
        smolLM?.close()
        smolLM = null
        loadedModelId = null
    }
}

data class LlmDecision(
    val verdict: String,     // YES, NO, SKIP
    val confidence: Double,
    val reasoning: String
)
