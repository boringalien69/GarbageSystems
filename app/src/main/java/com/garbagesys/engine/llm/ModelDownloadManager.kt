package com.garbagesys.engine.llm

import android.content.Context
import com.garbagesys.data.models.LlmModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private val modelDir: File get() = File(context.filesDir, "models").also { it.mkdirs() }

    /**
     * Download a GGUF model from HuggingFace.
     * Falls back to alternative mirrors if primary fails.
     * Progress: 0.0–1.0
     */
    suspend fun downloadModel(
        model: LlmModelInfo,
        onProgress: (Double, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val destFile = File(modelDir, model.filename)
        if (destFile.exists() && destFile.length() > 1_000_000) {
            onProgress(1.0, "Already downloaded")
            return@withContext true
        }
        val tempFile = File(modelDir, "${model.filename}.tmp")

        // Primary + fallback URLs
        val urls = listOf(
            "https://huggingface.co/${model.huggingFaceRepo}/resolve/main/${model.filename}",
            "https://hf-mirror.com/${model.huggingFaceRepo}/resolve/main/${model.filename}"
        )

        for (url in urls) {
            try {
                onProgress(0.0, "Connecting to mirror...")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue

                val contentLength = response.body?.contentLength() ?: -1L
                var downloaded = 0L

                response.body?.byteStream()?.use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (contentLength > 0) {
                                val progress = downloaded.toDouble() / contentLength.toDouble()
                                val mb = downloaded / (1024 * 1024)
                                val totalMb = contentLength / (1024 * 1024)
                                onProgress(progress, "Downloaded ${mb}MB / ${totalMb}MB")
                            }
                        }
                    }
                }

                // Validate size
                if (tempFile.length() < 1_000_000) {
                    tempFile.delete()
                    continue
                }

                tempFile.renameTo(destFile)
                onProgress(1.0, "Model ready!")
                return@withContext true
            } catch (e: Exception) {
                tempFile.delete()
                onProgress(0.0, "Trying next mirror...")
                continue
            }
        }
        false
    }

    fun getModelSize(model: LlmModelInfo): Long {
        return File(modelDir, model.filename).length()
    }

    fun deleteModel(model: LlmModelInfo) {
        File(modelDir, model.filename).delete()
    }
}
