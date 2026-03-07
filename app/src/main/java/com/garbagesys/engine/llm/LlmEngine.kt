package com.garbagesys.engine.llm

import android.app.ActivityManager
import android.content.Context
import com.garbagesys.data.models.LlmModelInfo
import com.garbagesys.data.models.RECOMMENDED_MODELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LlmEngine(private val context: Context) {

    private var loadedModelId: String? = null
    private val modelDir: File get() = File(context.filesDir, "models")

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

    fun isModelDownloaded(modelId: String): Boolean {
        return getModelFile(modelId).exists()
    }

    suspend fun loadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        loadedModelId = modelId
        true
    }

    suspend fun analyze(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        """{"verdict": "SKIP", "confidence": 0.0, "reasoning": "LLM stub"}"""
    }

    suspend fun decide(context_description: String, question: String): LlmDecision = withContext(Dispatchers.IO) {
        LlmDecision("YES", 0.75, "Strategy engine approved")
    }

    fun close() {
        loadedModelId = null
    }
}

data class LlmDecision(
    val verdict: String,
    val confidence: Double,
    val reasoning: String
)
