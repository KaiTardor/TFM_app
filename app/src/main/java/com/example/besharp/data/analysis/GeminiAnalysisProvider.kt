package com.example.besharp.data.analysis

// Consulta Gemini sobre el original sin tratar su respuesta como verificacion independiente.

import android.util.Base64
import com.example.besharp.domain.model.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiAnalysisProvider(
    private val apiKey: String,
    modelId: String = DEFAULT_MODEL,
    private val client: OkHttpClient = SHARED_GEMINI_HTTP_CLIENT
) : OriginalMediaAnalysisProvider {
    override val supportedMimeTypes = setOf("image/png", "image/jpeg", "image/webp")
    override val maxInputBytes: Long = MAX_INPUT_BYTES
    override val descriptor = describe(modelId)

    override suspend fun analyze(input: OriginalMediaInput, executionId: String): AuthenticityResult {
        if (apiKey.isBlank()) {
            throw AnalysisProviderException("Configura una API key de Gemini")
        }

        val startedAt = System.nanoTime()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${descriptor.modelId}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(buildRequestBody(input).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).awaitBoundedResponse()
        } catch (exception: ResponseTooLargeException) {
            throw AnalysisProviderException(
                "Gemini devolvio una respuesta demasiado grande",
                providerName = "Gemini",
                category = AnalysisErrorCategory.RESPONSE_TOO_LARGE,
                cause = exception
            )
        }
        if (!response.isSuccessful) throw providerHttpError("Gemini", response.statusCode)
        val responseBody = response.body.toString(Charsets.UTF_8)

        val modelText = extractModelText(responseBody)

        val parsed = GeminiAnalysisProtocol.parseModelJson(modelText)
        // Una respuesta de Gemini no equivale a una verificacion independiente.
        val hasSyntheticSignal = parsed.evidenceSignals.any { signal ->
            signal.outcome == EvidenceOutcome.DETECTED &&
                (signal.type == EvidenceType.SYNTHID_WATERMARK ||
                    signal.claim == EvidenceClaim.AI_GENERATION ||
                    signal.claim == EvidenceClaim.AI_EDITING)
        }
        return AuthenticityResult(
            provider = descriptor,
            verdict = if (hasSyntheticSignal) {
                AnalysisVerdict.LIKELY_SYNTHETIC
            } else {
                AnalysisVerdict.INCONCLUSIVE
            },
            syntheticProbability = null,
            summary = parsed.summary,
            indicators = parsed.evidenceSignals.map { signal ->
                "${signalLabel(signal.type)}: ${outcomeLabel(signal.outcome)}"
            },
            analyzedAt = System.currentTimeMillis(),
            durationMillis = elapsedMillis(startedAt),
            verificationLevel = VerificationLevel.PROVIDER_REPORTED,
            evidenceSignals = parsed.evidenceSignals
        )
    }

    internal fun extractModelText(responseBody: String): String {
        try {
            val parts = JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonArray("candidates")[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
            parts.forEach { part ->
                val json = part.asJsonObject
                if (json.get("thought")?.asBoolean == true) return@forEach
                val text = json.get("text")
                if (text != null && !text.isJsonNull) return text.asString
            }
        } catch (exception: Exception) {
            throw AnalysisProviderException("Gemini devolvio una respuesta incompleta", cause = exception)
        }
        throw AnalysisProviderException("Gemini no devolvio contenido de texto")
    }

    private fun buildRequestBody(input: OriginalMediaInput): JsonObject = JsonObject().apply {
        add("contents", JsonArray().apply {
            add(JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", GeminiAnalysisProtocol.prompt) })
                    add(JsonObject().apply {
                        add("inline_data", JsonObject().apply {
                            addProperty("mime_type", input.mimeType)
                            addProperty("data", Base64.encodeToString(input.file.readBytes(), Base64.NO_WRAP))
                        })
                    })
                })
            })
        })
        add("generationConfig", JsonObject().apply {
            addProperty("temperature", 0.1)
            addProperty("responseMimeType", "application/json")
        })
    }

    companion object {
        fun describe(modelId: String) = ProviderDescriptor(
            providerId = AnalysisProviderResolver.GEMINI_PROVIDER_ID,
            displayName = "Gemini", modelId = modelId,
            evidenceType = EvidenceType.C2PA_CREDENTIAL, isRemote = true,
            capability = AnalysisCapability.CONTENT_PROVENANCE,
            inputType = AnalysisInputType.ORIGINAL_FILE
        )
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        // Base64 aumenta el tamaño; dejamos margen para el resto de la peticion.
        const val MAX_INPUT_BYTES = 14L * 1024 * 1024
    }
}

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

private val SHARED_GEMINI_HTTP_CLIENT: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
