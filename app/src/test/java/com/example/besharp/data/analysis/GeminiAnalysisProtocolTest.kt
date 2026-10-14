package com.example.besharp.data.analysis

import com.example.besharp.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiAnalysisProtocolTest {
    @Test
    fun omitsChecksThatWereNotAvailable() {
        val result = GeminiAnalysisProtocol.parseModelJson(
            """
            {
              "summary": "No fue posible ejecutar herramientas de procedencia.",
              "c2pa": {"outcome": "NOT_CHECKED"},
              "synthId": {"outcome": "NOT_CHECKED"}
            }
            """.trimIndent()
        )

        assertEquals("No fue posible ejecutar herramientas de procedencia.", result.summary)
        assertTrue(result.evidenceSignals.isEmpty())
    }

    @Test
    fun parsesProviderReportedProvenance() {
        val result = GeminiAnalysisProtocol.parseModelJson(
            """
            {
              "summary": "Gemini informa de una señal SynthID.",
              "c2pa": {"outcome": "NOT_CHECKED"},
              "synthId": {
                "outcome": "DETECTED",
                "model": "Imagen 3",
                "details": "La herramienta disponible reporto la marca."
              }
            }
            """.trimIndent()
        )

        assertEquals(1, result.evidenceSignals.size)
        assertEquals(EvidenceOutcome.DETECTED, result.evidenceSignals[0].outcome)
        assertEquals(VerificationLevel.PROVIDER_REPORTED, result.evidenceSignals[0].verificationLevel)
        assertEquals(EvidenceClaim.AI_GENERATION, result.evidenceSignals[0].claim)
    }

    @Test
    fun c2paDoesNotImplyAiGenerationWithoutAClaim() {
        val result = GeminiAnalysisProtocol.parseModelJson(
            """
            {
              "summary": "Se encontro una credencial de contenido.",
              "c2pa": {
                "outcome": "DETECTED",
                "validationState": "valid",
                "claim": "UNKNOWN"
              },
              "synthId": {"outcome": "NOT_CHECKED"}
            }
            """.trimIndent()
        )

        assertEquals(EvidenceClaim.UNKNOWN, result.evidenceSignals.single().claim)
    }

    @Test(expected = AnalysisProviderException::class)
    fun rejectsSummaryLongerThanLimitInsteadOfTruncatingIt() {
        GeminiAnalysisProtocol.parseModelJson(
            """{"summary":"${"a".repeat(501)}"}"""
        )
    }

    @Test
    fun rejectsWrongTypesAndInvalidDates() {
        for (response in listOf(
            """{"summary":123}""",
            """{"summary":"Resultado","c2pa":[]}""",
            """{"summary":"Resultado","synthId":{"outcome":"DETECTED","model":12}}""",
            """{"summary":"Resultado","synthId":{"outcome":"DETECTED","generatedAt":"yesterday"}}"""
        )) {
            assertThrows(AnalysisProviderException::class.java) { GeminiAnalysisProtocol.parseModelJson(response) }
        }
    }
}
