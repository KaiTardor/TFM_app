package com.example.besharp.data.analysis

// Envia un JPEG normalizado a un endpoint del contrato BeSharp.

import android.util.Base64
import com.example.besharp.domain.model.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class CustomApiAnalysisProvider(
    private val configuration: CustomApiConfiguration,
    private val accessToken: String? = null,
    private val client: OkHttpClient = CUSTOM_API_HTTP_CLIENT
) : NormalizedImageAnalysisProvider {
    private val endpoint = configuration.validate().getOrThrow()

    override val descriptor = describe(configuration)

    // Realiza el análisis de la imagen utilizando la API personalizada.
    override suspend fun analyze(input: AnalysisInput, executionId: String): AuthenticityResult {
        val startedAt = System.nanoTime()
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header(CONTRACT_HEADER, CONTRACT_VERSION)
            .post(buildRequestBody(input, executionId).toString().toRequestBody(CUSTOM_API_JSON_MEDIA_TYPE))
        accessToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val response = try {
            client.newCall(requestBuilder.build()).awaitBoundedResponse()
        } catch (exception: ResponseTooLargeException) {
            throw AnalysisProviderException(
                "La API personalizada devolvio una respuesta demasiado grande",
                category = AnalysisErrorCategory.RESPONSE_TOO_LARGE,
                cause = exception
            )
        }
        if (!response.isSuccessful) {
            throw providerHttpError(descriptor.displayName, response.statusCode)
        }
        val responseBody = response.body.toString(Charsets.UTF_8)

        val parsed = parseResponse(responseBody)
        return AuthenticityResult(
            provider = descriptor.copy(evidenceType = parsed.evidenceType),
            verdict = parsed.verdict,
            syntheticProbability = parsed.syntheticProbability,
            summary = parsed.summary,
            indicators = parsed.indicators,
            analyzedAt = System.currentTimeMillis(),
            durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            verificationLevel = verificationLevelFor(parsed.evidenceType),
            evidenceSignals = parsed.evidenceSignals.ifEmpty { legacySignal(parsed) }
        )
    }

    internal fun buildRequestBody(input: AnalysisInput, executionId: String): JsonObject =
        buildRequestMetadata(executionId).apply {
        addProperty("model", descriptor.modelId)
        add("image", JsonObject().apply {
            addProperty("mimeType", input.mimeType)
            addProperty("data", Base64.encodeToString(input.imageBytes, Base64.NO_WRAP))
        })
        add("parameters", JsonObject().apply {
            configuration.parameters.forEach { (key, value) -> addProperty(key, value) }
        })
    }

    internal fun buildRequestMetadata(executionId: String): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", CONTRACT_VERSION)
        addProperty("requestId", executionId)
    }

    internal fun parseResponse(rawBody: String): CustomApiParsedResult {
        return try {
            parseResponseObject(rawBody)
        } catch (exception: AnalysisProviderException) {
            throw exception
        } catch (_: Exception) {
            throw AnalysisProviderException("La API personalizada devolvio campos no validos")
        }
    }

    private fun parseResponseObject(rawBody: String): CustomApiParsedResult {
        val json = try {
            JsonParser.parseString(rawBody).asJsonObject
        } catch (_: Exception) {
            throw AnalysisProviderException("La API personalizada no devolvio JSON valido")
        }
        if (json.stringOrNull("schemaVersion") != CONTRACT_VERSION) {
            throw AnalysisProviderException("Version de contrato no compatible")
        }

        val verdict = parseEnum<AnalysisVerdict>(json.stringOrNull("verdict"), "veredicto")
        val evidenceType = parseEnum<EvidenceType>(json.stringOrNull("evidenceType"), "evidencia")
        val probability = parseProbability(json.get("syntheticProbability")) {
            AnalysisProviderException("La probabilidad debe ser un entero entre 0 y 100")
        }
        if (verdict == AnalysisVerdict.INCONCLUSIVE && probability != null) {
            throw AnalysisProviderException("Un resultado inconcluso no debe incluir probabilidad")
        }

        val summary = json.stringOrNull("summary") ?: throw AnalysisProviderException("Resumen ausente")
        val indicators = json.arrayOrNull("indicators")
            ?.map { element ->
                if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                    throw AnalysisProviderException("Los indicadores deben ser texto")
                }
                element.asString.trim()
            }
            ?.filter { it.isNotEmpty() }
            ?.take(MAX_INDICATORS)
            .orEmpty()
        if (indicators.any { it.length > MAX_INDICATOR_LENGTH }) {
            throw AnalysisProviderException("La API personalizada devolvio un indicador demasiado largo")
        }

        val evidenceSignals = json.arrayOrNull("evidenceSignals")
            ?.take(MAX_EVIDENCE_SIGNALS)
            ?.mapIndexed { index, element -> parseEvidenceSignal(element.asJsonObject, index) }
            .orEmpty()
        if (evidenceSignals.map { it.signalId }.distinct().size != evidenceSignals.size) {
            throw AnalysisProviderException("La API personalizada repitio identificadores de señal")
        }

        return CustomApiParsedResult(
            verdict,
            probability,
            evidenceType,
            summary,
            indicators,
            evidenceSignals
        )
    }

    internal fun verificationLevelFor(evidenceType: EvidenceType): VerificationLevel =
        if (evidenceType == EvidenceType.C2PA_CREDENTIAL ||
            evidenceType == EvidenceType.SYNTHID_WATERMARK
        ) {
            VerificationLevel.PROVIDER_REPORTED
        } else {
            VerificationLevel.VISUAL_ESTIMATE
        }

    private fun parseEvidenceSignal(json: JsonObject, index: Int): EvidenceSignal {
        val type = parseEnum<EvidenceType>(json.stringOrNull("type"), "tipo de señal")
        val outcome = parseEnum<EvidenceOutcome>(json.stringOrNull("outcome"), "estado de señal")
        val claim = json.stringOrNull("claim")?.let { value ->
            parseEnum<EvidenceClaim>(value, "afirmacion de señal")
        } ?: EvidenceClaim.UNKNOWN
        val signalId = json.stringOrNull("signalId", MAX_SIGNAL_ID_LENGTH) ?: "custom-signal-$index"
        val timestamp = json.get("generatedAt")?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) {
                throw AnalysisProviderException("La fecha de señal debe ser un entero en milisegundos")
            }
            it.asBigDecimal.longValueExact()
        }
        return EvidenceSignal(
            signalId = signalId,
            type = type,
            outcome = outcome,
            verificationLevel = if (type == EvidenceType.VISUAL_ESTIMATE) {
                VerificationLevel.VISUAL_ESTIMATE
            } else {
                VerificationLevel.PROVIDER_REPORTED
            },
            source = descriptor.displayName,
            claim = claim,
            issuer = json.stringOrNull("issuer"),
            model = json.stringOrNull("model"),
            generatedAt = timestamp,
            explanation = json.stringOrNull("explanation"),
            validationState = json.stringOrNull("validationState")
        )
    }

    private fun legacySignal(parsed: CustomApiParsedResult): List<EvidenceSignal> {
        // El contrato 1.0 permite declarar procedencia sin la lista evidenceSignals.
        if (parsed.evidenceType != EvidenceType.C2PA_CREDENTIAL &&
            parsed.evidenceType != EvidenceType.SYNTHID_WATERMARK
        ) return emptyList()
        val outcome = when (parsed.verdict) {
            AnalysisVerdict.LIKELY_SYNTHETIC -> EvidenceOutcome.DETECTED
            AnalysisVerdict.LIKELY_AUTHENTIC -> EvidenceOutcome.NOT_DETECTED
            AnalysisVerdict.INCONCLUSIVE -> EvidenceOutcome.INCONCLUSIVE
        }
        return listOf(
            EvidenceSignal(
                signalId = "custom-legacy-provenance",
                type = parsed.evidenceType,
                outcome = outcome,
                verificationLevel = VerificationLevel.PROVIDER_REPORTED,
                source = descriptor.displayName,
                claim = if (outcome == EvidenceOutcome.DETECTED) {
                    EvidenceClaim.AI_GENERATION
                } else {
                    EvidenceClaim.UNKNOWN
                },
                explanation = "Señal declarada mediante el contrato BeSharp 1.0."
            )
        )
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String?, field: String): T = try {
        enumValueOf(value.orEmpty().trim().uppercase())
    } catch (_: Exception) {
        throw AnalysisProviderException("La API personalizada devolvio $field no valido")
    }

    companion object {
        fun describe(configuration: CustomApiConfiguration) = ProviderDescriptor(
            providerId = configuration.providerId(), displayName = configuration.displayName.trim(),
            modelId = configuration.modelId.trim(), evidenceType = EvidenceType.VISUAL_ESTIMATE,
            isRemote = true
        )
        const val CONTRACT_VERSION = "1.0"
        const val CONTRACT_HEADER = "X-BeSharp-Contract"
        private const val MAX_INDICATORS = 5
        private const val MAX_INDICATOR_LENGTH = 200
        private const val MAX_EVIDENCE_SIGNALS = 10
        private const val MAX_SIGNAL_ID_LENGTH = 100
    }
}

internal data class CustomApiParsedResult(
    val verdict: AnalysisVerdict,
    val syntheticProbability: Int?,
    val evidenceType: EvidenceType,
    val summary: String,
    val indicators: List<String>,
    val evidenceSignals: List<EvidenceSignal> = emptyList()
)

private fun parseProbability(
    element: JsonElement?,
    invalidValue: () -> AnalysisProviderException
): Int? {
    if (element == null || element.isJsonNull) return null
    val primitive = element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        ?.takeIf { it.isNumber }
        ?: throw invalidValue()
    val number = runCatching { primitive.asBigDecimal }.getOrElse { throw invalidValue() }
    // No truncamos decimales: 50.9 no es una probabilidad entera valida.
    val normalized = number.stripTrailingZeros()
    if (normalized.scale() > 0 || number < java.math.BigDecimal.ZERO ||
        number > java.math.BigDecimal.valueOf(100)
    ) {
        throw invalidValue()
    }
    return number.toInt()
}

private val CUSTOM_API_JSON_MEDIA_TYPE = "application/json".toMediaType()

internal val CUSTOM_API_HTTP_CLIENT: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
}
