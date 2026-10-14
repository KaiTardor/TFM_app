package com.example.besharp.data.analysis

// Define el prompt de procedencia y valida el JSON devuelto por Gemini.

import com.example.besharp.domain.model.EvidenceClaim
import com.example.besharp.domain.model.EvidenceOutcome
import com.example.besharp.domain.model.EvidenceSignal
import com.example.besharp.domain.model.EvidenceType
import com.example.besharp.domain.model.VerificationLevel
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object GeminiAnalysisProtocol {
    val prompt: String = """
        Inspect the original image file using any provenance tools available to you, including
        C2PA Content Credentials, Google SynthID, VerifyAI, or equivalent provider-side tools.
        Do not infer provenance from visual appearance. If a tool is unavailable, use NOT_CHECKED.
        Respond only with valid JSON using this schema:
        {
          "summary": "<one short user-facing sentence, at most 500 characters>",
          "c2pa": {
            "outcome": "DETECTED | NOT_DETECTED | INCONCLUSIVE | NOT_CHECKED",
            "validationState": "trusted | valid | invalid | not_present | unknown",
            "claim": "AI_GENERATION | AI_EDITING | CAMERA_CAPTURE | UNKNOWN",
            "issuer": "<issuer or null>",
            "model": "<model or null>",
            "generatedAt": "<ISO-8601 timestamp or null>",
            "details": "<short explanation or null>"
          },
          "synthId": {
            "outcome": "DETECTED | NOT_DETECTED | INCONCLUSIVE | NOT_CHECKED",
            "model": "<model or null>",
            "generatedAt": "<ISO-8601 timestamp or null>",
            "details": "<short explanation or null>"
          }
        }
        Do not wrap the JSON in markdown and do not claim verification unless a tool actually ran.
    """.trimIndent()

    fun parseModelJson(rawText: String): ParsedGeminiProvenance {
        val json = parseObject(rawText)
        val summary = json.stringOrNull("summary")
            ?: throw AnalysisProviderException("Gemini no devolvio un resumen de procedencia")
        val signals = try {
            listOfNotNull(
                parseSignal(json.objectOrNull("c2pa"), "gemini-c2pa", EvidenceType.C2PA_CREDENTIAL),
                parseSignal(json.objectOrNull("synthId"), "gemini-synthid", EvidenceType.SYNTHID_WATERMARK)
            )
        } catch (exception: AnalysisProviderException) {
            throw exception
        } catch (_: Exception) {
            throw AnalysisProviderException("Gemini devolvio una señal de procedencia no valida")
        }
        return ParsedGeminiProvenance(summary, signals)
    }

    private fun parseSignal(
        json: JsonObject?,
        id: String,
        type: EvidenceType
    ): EvidenceSignal? {
        json ?: return null
        val outcome = runCatching {
            EvidenceOutcome.valueOf(json.stringOrNull("outcome").orEmpty().uppercase())
        }.getOrElse {
            throw AnalysisProviderException("Gemini devolvio un estado de procedencia no valido")
        }
        // No guardamos como evidencia una herramienta que no se ha ejecutado.
        if (outcome == EvidenceOutcome.NOT_CHECKED) return null
        return EvidenceSignal(
            signalId = id,
            type = type,
            outcome = outcome,
            verificationLevel = VerificationLevel.PROVIDER_REPORTED,
            source = "Gemini",
            claim = parseClaim(json.stringOrNull("claim"), type, outcome),
            issuer = json.stringOrNull("issuer"),
            model = json.stringOrNull("model"),
            generatedAt = json.stringOrNull("generatedAt")?.let(::parseProvenanceTimestamp),
            explanation = json.stringOrNull("details"),
            validationState = json.stringOrNull("validationState")
        )
    }

    private fun parseClaim(
        value: String?,
        type: EvidenceType,
        outcome: EvidenceOutcome
    ): EvidenceClaim {
        if (outcome != EvidenceOutcome.DETECTED) return EvidenceClaim.UNKNOWN
        if (type == EvidenceType.SYNTHID_WATERMARK) return EvidenceClaim.AI_GENERATION
        return value?.let { EvidenceClaim.valueOf(it.uppercase()) } ?: EvidenceClaim.UNKNOWN
    }

    private fun parseObject(rawText: String): JsonObject {
        val normalized = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            JsonParser.parseString(normalized).asJsonObject
        } catch (_: Exception) {
            throw AnalysisProviderException("Gemini no devolvio un resultado JSON valido")
        }
    }

}

internal data class ParsedGeminiProvenance(
    val summary: String,
    val evidenceSignals: List<EvidenceSignal>
)
