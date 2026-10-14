package com.example.besharp.data.analysis

import com.example.besharp.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomApiAnalysisProviderTest {
    private val provider = CustomApiAnalysisProvider(
        configuration = CustomApiConfiguration(
            displayName = "Detector propio",
            endpointUrl = "https://example.com/analyze",
            modelId = "detector-v1"
        )
    )

    @Test
    fun parsesContractResponse() {
        val parsed = provider.parseResponse(
            """
            {
              "schemaVersion": "1.0",
              "verdict": "LIKELY_SYNTHETIC",
              "syntheticProbability": 91,
              "evidenceType": "LOCAL_CLASSIFIER",
              "summary": "Se detectaron patrones sintéticos.",
              "indicators": ["patrón uno", "patrón dos"]
            }
            """.trimIndent()
        )

        assertEquals(AnalysisVerdict.LIKELY_SYNTHETIC, parsed.verdict)
        assertEquals(91, parsed.syntheticProbability)
        assertEquals(EvidenceType.LOCAL_CLASSIFIER, parsed.evidenceType)
    }

    @Test
    fun parsesInconclusiveResponse() {
        val parsed = provider.parseResponse(
            """
            {
              "schemaVersion": "1.0",
              "verdict": "INCONCLUSIVE",
              "syntheticProbability": null,
              "evidenceType": "VISUAL_ESTIMATE",
              "summary": "No hay evidencia suficiente.",
              "indicators": []
            }
            """.trimIndent()
        )

        assertNull(parsed.syntheticProbability)
    }

    @Test(expected = AnalysisProviderException::class)
    fun rejectsUnsupportedContractVersion() {
        provider.parseResponse(
            """
            {
              "schemaVersion": "2.0",
              "verdict": "INCONCLUSIVE",
              "syntheticProbability": null,
              "evidenceType": "VISUAL_ESTIMATE",
              "summary": "No hay evidencia suficiente.",
              "indicators": []
            }
            """.trimIndent()
        )
    }

    @Test
    fun rejectsDecimalProbabilityInsteadOfTruncatingIt() {
        assertThrows(AnalysisProviderException::class.java) {
            provider.parseResponse(
                """
                {
                  "schemaVersion": "1.0",
                  "verdict": "LIKELY_SYNTHETIC",
                  "syntheticProbability": 50.9,
                  "evidenceType": "LOCAL_CLASSIFIER",
                  "summary": "Resultado del detector.",
                  "indicators": []
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun limitsIndicatorsToContractMaximum() {
        val parsed = provider.parseResponse(
            """
            {
              "schemaVersion": "1.0",
              "verdict": "LIKELY_AUTHENTIC",
              "syntheticProbability": 5,
              "evidenceType": "LOCAL_CLASSIFIER",
              "summary": "Resultado del detector.",
              "indicators": ["1", "2", "3", "4", "5", "6"]
            }
            """.trimIndent()
        )

        assertEquals(listOf("1", "2", "3", "4", "5"), parsed.indicators)
    }

    @Test
    fun rejectsOversizedIndicator() {
        assertThrows(AnalysisProviderException::class.java) {
            provider.parseResponse(
                """
                {
                  "schemaVersion": "1.0",
                  "verdict": "LIKELY_AUTHENTIC",
                  "syntheticProbability": 5,
                  "evidenceType": "LOCAL_CLASSIFIER",
                  "summary": "Resultado del detector.",
                  "indicators": ["${"i".repeat(201)}"]
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun reusesExecutionIdAsRequestIdAcrossRetries() {
        val first = provider.buildRequestMetadata("stable-execution-id")
        val retry = provider.buildRequestMetadata("stable-execution-id")

        assertEquals("stable-execution-id", first.get("requestId").asString)
        assertEquals(first.get("requestId"), retry.get("requestId"))
    }

    @Test
    fun treatsCustomProvenanceClaimsAsProviderDeclared() {
        assertEquals(
            VerificationLevel.PROVIDER_REPORTED,
            provider.verificationLevelFor(EvidenceType.C2PA_CREDENTIAL)
        )
        assertEquals(
            VerificationLevel.PROVIDER_REPORTED,
            provider.verificationLevelFor(EvidenceType.SYNTHID_WATERMARK)
        )
    }

    @Test
    fun parsesMultipleEvidenceSignalsAsProviderReported() {
        val parsed = provider.parseResponse(
            """
            {
              "schemaVersion": "1.0",
              "verdict": "LIKELY_SYNTHETIC",
              "syntheticProbability": 90,
              "evidenceType": "VISUAL_ESTIMATE",
              "summary": "Resultado combinado.",
              "indicators": [],
              "evidenceSignals": [
                {
                  "signalId": "c2pa-1",
                  "type": "C2PA_CREDENTIAL",
                  "outcome": "DETECTED",
                  "claim": "AI_GENERATION",
                  "issuer": "Example"
                },
                {
                  "signalId": "synthid-1",
                  "type": "SYNTHID_WATERMARK",
                  "outcome": "NOT_DETECTED"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, parsed.evidenceSignals.size)
        assertEquals(VerificationLevel.PROVIDER_REPORTED, parsed.evidenceSignals[0].verificationLevel)
        assertEquals(EvidenceOutcome.DETECTED, parsed.evidenceSignals[0].outcome)
        assertEquals(EvidenceClaim.AI_GENERATION, parsed.evidenceSignals[0].claim)
    }

    @Test
    fun rejectsDuplicatedSignalIdsBeforeRoom() {
        val signal = """{"signalId":"same","type":"C2PA_CREDENTIAL","outcome":"DETECTED"}"""
        assertThrows(AnalysisProviderException::class.java) {
            provider.parseResponse(responseWithSignals("$signal,$signal"))
        }
    }

    @Test
    fun rejectsLongIdsInsteadOfTruncatingThem() {
        val signal = """{"signalId":"${"a".repeat(101)}","type":"C2PA_CREDENTIAL","outcome":"DETECTED"}"""
        assertThrows(AnalysisProviderException::class.java) {
            provider.parseResponse(responseWithSignals(signal))
        }
    }

    @Test
    fun rejectsMalformedTimestampAndSignalText() {
        for (extra in listOf(
            "\"generatedAt\":1.5", "\"generatedAt\":9223372036854775808",
            "\"generatedAt\":\"1000\"", "\"issuer\":{}"
        )) {
            assertThrows(AnalysisProviderException::class.java) {
                provider.parseResponse(responseWithSignals(
                    """{"type":"C2PA_CREDENTIAL","outcome":"DETECTED",$extra}"""
                ))
            }
        }
    }

    private fun responseWithSignals(signals: String) = """
        {"schemaVersion":"1.0","verdict":"INCONCLUSIVE","syntheticProbability":null,
         "evidenceType":"C2PA_CREDENTIAL","summary":"Resultado.","evidenceSignals":[$signals]}
    """.trimIndent()

    @Test
    fun rejectsNonTextSummaryIndicatorsAndClaims() {
        for (fields in listOf(
            "\"summary\":123",
            "\"summary\":\"Resultado\",\"indicators\":[{}]",
            "\"summary\":\"Resultado\",\"evidenceSignals\":{}",
            "\"summary\":\"Resultado\",\"evidenceSignals\":[{\"type\":\"C2PA_CREDENTIAL\",\"outcome\":\"DETECTED\",\"claim\":true}]"
        )) {
            assertThrows(AnalysisProviderException::class.java) {
                provider.parseResponse("""{"schemaVersion":"1.0","verdict":"INCONCLUSIVE","syntheticProbability":null,"evidenceType":"C2PA_CREDENTIAL",$fields}""")
            }
        }
    }

    @Test
    fun customClientNeverFollowsRedirects() {
        val client = CUSTOM_API_HTTP_CLIENT

        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
    }
}
