package com.example.besharp.data.analysis

// Comprueba C2PA y SynthID compatibles con OpenAI sobre el archivo original.

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.example.besharp.domain.model.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class OpenAiProvenanceProvider(
    private val apiKey: String,
    private val client: OkHttpClient = SHARED_PROVENANCE_HTTP_CLIENT,
    private val endpointUrl: String = DEFAULT_ENDPOINT
) : OriginalMediaAnalysisProvider {
    override val supportedMimeTypes = setOf("image/png", "image/jpeg", "image/webp")
    override val maxInputBytes: Long = OriginalMediaInput.MAX_SIZE_BYTES
    override val descriptor = describe()

    override suspend fun analyze(input: OriginalMediaInput, executionId: String): AuthenticityResult {
        if (apiKey.isBlank()) {
            throw AnalysisProviderException(
                message = "Configura una API key de OpenAI",
                category = AnalysisErrorCategory.CONFIGURATION
            )
        }
        val startedAt = System.nanoTime()
        // El fichero se envia sin convertirlo ni cargarlo entero en memoria.
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                input.fileName,
                input.file.asRequestBody(input.mimeType.toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Client-Request-Id", executionId)
            .post(requestBody)
            .build()

        val response = try {
            client.newCall(request).awaitBoundedResponse()
        } catch (exception: ResponseTooLargeException) {
            throw AnalysisProviderException(
                message = "OpenAI Provenance devolvio una respuesta demasiado grande",
                category = AnalysisErrorCategory.RESPONSE_TOO_LARGE,
                cause = exception
            )
        }
        if (!response.isSuccessful) throw provenanceHttpError(response)

        val signals = parseResponse(response.body.toString(Charsets.UTF_8))
        val detected = signals.filter { it.outcome == EvidenceOutcome.DETECTED }
        return AuthenticityResult(
            provider = descriptor,
            verdict = if (detected.isNotEmpty()) {
                AnalysisVerdict.LIKELY_SYNTHETIC
            } else {
                AnalysisVerdict.INCONCLUSIVE
            },
            syntheticProbability = null,
            summary = if (detected.isNotEmpty()) {
                "OpenAI detecto ${detected.joinToString(" y ") { signalLabel(it.type) }}."
            } else {
                "OpenAI no detecto señales de procedencia compatibles; esto no demuestra autenticidad."
            },
            indicators = signals.map { signal ->
                "${signalLabel(signal.type)}: ${outcomeLabel(signal.outcome)}"
            },
            analyzedAt = System.currentTimeMillis(),
            durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            verificationLevel = VerificationLevel.REMOTE_VERIFIED,
            evidenceSignals = signals
        )
    }

    internal fun parseResponse(rawBody: String): List<EvidenceSignal> {
        return try {
            parseResultObject(JsonParser.parseString(rawBody).asJsonObject)
        } catch (exception: AnalysisProviderException) {
            throw exception
        } catch (_: Exception) {
            throw invalidResponse("OpenAI Provenance devolvio campos no validos")
        }
    }

    // Rechaza tipos y estados desconocidos y genera IDs por indice para distinguir cada resultado.
    private fun parseResultObject(root: JsonObject): List<EvidenceSignal> {
        if (root.stringOrNull("object") != "content_provenance_check") {
            throw invalidResponse("OpenAI Provenance devolvio un objeto inesperado")
        }
        val results = root.arrayOrNull("results")
            ?: throw invalidResponse("OpenAI Provenance no devolvio resultados")
        if (results.size() == 0) {
            throw invalidResponse("OpenAI Provenance devolvio una lista vacia")
        }
        return results.mapIndexed { index, element ->
            val item = element.asJsonObject
            val rawType = item.requiredString("type")
            val type = when (rawType.lowercase()) {
                "c2pa" -> EvidenceType.C2PA_CREDENTIAL
                "synthid" -> EvidenceType.SYNTHID_WATERMARK
                else -> throw invalidResponse("OpenAI Provenance devolvio un tipo de señal no compatible")
            }
            val outcome = when (item.requiredString("outcome").lowercase()) {
                "detected" -> EvidenceOutcome.DETECTED
                "not_detected" -> EvidenceOutcome.NOT_DETECTED
                else -> throw invalidResponse("OpenAI Provenance devolvio un estado no compatible")
            }
            EvidenceSignal(
                signalId = "openai-$rawType-$index",
                type = type,
                outcome = outcome,
                verificationLevel = VerificationLevel.REMOTE_VERIFIED,
                source = "OpenAI Content Provenance API",
                claim = if (outcome == EvidenceOutcome.DETECTED) {
                    EvidenceClaim.AI_GENERATION
                } else {
                    EvidenceClaim.UNKNOWN
                },
                issuer = item.stringOrNull("issuer"),
                model = item.stringOrNull("model"),
                generatedAt = item.stringOrNull("generated_at")?.let(::parseProvenanceTimestamp),
                explanation = when (outcome) {
                    EvidenceOutcome.DETECTED -> "Se detecto una señal compatible con OpenAI."
                    EvidenceOutcome.NOT_DETECTED ->
                        "No se detecto esta señal; el resultado no demuestra autenticidad."
                    else -> "La señal no pudo comprobarse de forma concluyente."
                },
                validationState = item.stringOrNull("validation_state")
            )
        }
    }

    private fun provenanceHttpError(response: BoundedHttpResponse): AnalysisProviderException {
        val openAiError = openAiHttpError(descriptor.displayName, response)
        if (openAiError.category == AnalysisErrorCategory.QUOTA) return openAiError
        val category = when (response.statusCode) {
            400 -> AnalysisErrorCategory.INVALID_INPUT
            404 -> AnalysisErrorCategory.ACCESS_UNAVAILABLE
            413 -> AnalysisErrorCategory.FILE_TOO_LARGE
            else -> openAiError.category
        }
        return AnalysisProviderException(
            message = "OpenAI Provenance devolvio un error HTTP ${response.statusCode}",
            category = category,
            httpStatus = response.statusCode,
            retryAfterMillis = response.header("Retry-After")?.let(::parseRetryAfterMillis)
        )
    }

    private fun invalidResponse(message: String) =
        AnalysisProviderException(
            message = message,
            category = AnalysisErrorCategory.INVALID_RESPONSE
        )

    private fun JsonObject.requiredString(name: String): String =
        stringOrNull(name)
            ?: throw invalidResponse("OpenAI Provenance omitio el campo $name")

    private fun parseRetryAfterMillis(value: String): Long? {
        value.trim().toLongOrNull()?.let { seconds ->
            return seconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS) * 1_000
        }
        return runCatching {
            val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (retryAt.toEpochMilli() - System.currentTimeMillis()).coerceIn(
                0,
                MAX_RETRY_AFTER_SECONDS * 1_000
            )
        }.getOrNull()
    }

    companion object {
        fun describe() = ProviderDescriptor(
            providerId = PROVIDER_ID, displayName = "OpenAI Provenance", modelId = MODEL_ID,
            evidenceType = EvidenceType.C2PA_CREDENTIAL, isRemote = true,
            capability = AnalysisCapability.CONTENT_PROVENANCE,
            inputType = AnalysisInputType.ORIGINAL_FILE
        )
        const val PROVIDER_ID = "openai:content-provenance"
        const val MODEL_ID = "content-provenance"
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/content_provenance_checks"
        private const val MAX_RETRY_AFTER_SECONDS = 300L
    }
}

private fun BoundedHttpResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private val SHARED_PROVENANCE_HTTP_CLIENT: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()
}
