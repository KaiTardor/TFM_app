package com.example.besharp.data.analysis

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

fun interface ProviderModelCatalog {
    suspend fun fetch(credential: String): Result<List<String>>
}

class ProviderModelCatalogService(
    private val client: OkHttpClient = MODEL_CATALOG_HTTP_CLIENT
) : ProviderModelCatalog {
    override suspend fun fetch(credential: String): Result<List<String>> = try {
        require(credential.isNotBlank()) { "Configura primero una API key" }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
            .header("x-goog-api-key", credential)
            .get()
            .build()
        val response = client.newCall(request).awaitBoundedResponse(MAX_CATALOG_BYTES)
        if (!response.isSuccessful) throw providerHttpError("Gemini", response.statusCode)
        val body = response.body.toString(Charsets.UTF_8)
        val models = parseGeminiModels(body)
        check(models.isNotEmpty()) { "El proveedor no devolvio modelos compatibles" }
        Result.success(models)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Throwable) {
        Result.failure(exception)
    }

    internal fun parseGeminiModels(rawBody: String): List<String> {
        val models = JsonParser.parseString(rawBody).asJsonObject.getAsJsonArray("models")
            ?: return emptyList()
        val compatible = models.mapNotNull { element ->
            val model = element.asJsonObject
            val methods = model.getAsJsonArray("supportedGenerationMethods")
                ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                .orEmpty()
            model.get("name")?.asString
                ?.removePrefix("models/")
                ?.takeIf { it.startsWith("gemini-") && "generateContent" in methods }
        }.distinct()
        return curateGeminiModels(compatible)
    }

    private fun curateGeminiModels(models: List<String>): List<String> {
        data class Candidate(val id: String, val version: Int, val tier: String)
        val pattern = Regex(
            "^gemini-(\\d+)(?:\\.(\\d+))?-(pro|flash|flash-lite)(?:-preview)?$"
        )
        val candidates = models.mapNotNull { id ->
            val match = pattern.matchEntire(id) ?: return@mapNotNull null
            val version = match.groupValues[1].toInt() * 100 +
                (match.groupValues[2].toIntOrNull() ?: 0)
            Candidate(id, version, match.groupValues[3])
        }
        if (candidates.isEmpty()) return models.sorted().take(MAX_VISIBLE_MODELS)
        // Mostramos una variante por gama de la generacion mas reciente.
        val newestVersion = candidates.maxOf(Candidate::version)
        return candidates
            .filter { it.version == newestVersion }
            .sortedBy { tierOrder(it.tier) }
            .distinctBy(Candidate::tier)
            .map(Candidate::id)
            .take(MAX_VISIBLE_MODELS)
    }

    private fun tierOrder(tier: String): Int = when (tier) {
        "pro" -> 0
        "flash" -> 1
        "flash-lite" -> 2
        else -> 3
    }

    companion object {
        private const val MAX_CATALOG_BYTES = 1024 * 1024
        private const val MAX_VISIBLE_MODELS = 4
    }
}

private val MODEL_CATALOG_HTTP_CLIENT by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}
