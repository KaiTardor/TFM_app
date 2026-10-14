package com.example.besharp.data.analysis

import com.example.besharp.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class OpenAiProvenanceProviderTest {
    private val provider = OpenAiProvenanceProvider("test-key")

    @Test
    fun parsesC2paAndSynthIdIndependently() {
        val signals = provider.parseResponse(
            """
            {
              "object": "content_provenance_check",
              "created_at": 1778000000,
              "results": [
                {
                  "type": "c2pa",
                  "outcome": "detected",
                  "validation_state": "trusted",
                  "issuer": "OpenAI OpCo, LLC",
                  "model": "gpt-image",
                  "generated_at": "2026-07-27T18:34:12Z"
                },
                {
                  "type": "synthid",
                  "outcome": "not_detected",
                  "model": null,
                  "generated_at": null
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, signals.size)
        assertEquals(EvidenceType.C2PA_CREDENTIAL, signals[0].type)
        assertEquals(EvidenceOutcome.DETECTED, signals[0].outcome)
        assertEquals(VerificationLevel.REMOTE_VERIFIED, signals[0].verificationLevel)
        assertEquals(EvidenceClaim.AI_GENERATION, signals[0].claim)
        assertEquals("trusted", signals[0].validationState)
        assertEquals(EvidenceOutcome.NOT_DETECTED, signals[1].outcome)
        assertEquals(EvidenceClaim.UNKNOWN, signals[1].claim)
        assertNull(signals[1].generatedAt)
    }

    @Test
    fun rejectsResponseWithoutResults() {
        assertThrows(AnalysisProviderException::class.java) {
            provider.parseResponse("""{"object":"content_provenance_check"}""")
        }
    }

    @Test
    fun rejectsUnexpectedObject() {
        assertThrows(AnalysisProviderException::class.java) {
            provider.parseResponse("""{"object":"other","results":[]}""")
        }
    }

    @Test
    fun malformedOrUnknownSignalsAreNotInventedAsEvidence() {
        for (result in listOf(
            """{"type":"unknown","outcome":"detected"}""",
            """{"type":"synthid","outcome":"maybe"}""",
            """{"type":"synthid","outcome":"detected","model":[]}""",
            """{"type":"synthid","outcome":"detected","generated_at":"yesterday"}"""
        )) {
            val error = assertThrows(AnalysisProviderException::class.java) {
                provider.parseResponse("""{"object":"content_provenance_check","results":[$result]}""")
            }
            assertEquals(AnalysisErrorCategory.INVALID_RESPONSE, error.category)
        }
        assertThrows(AnalysisProviderException::class.java) {
            provider.parseResponse("""{"object":"content_provenance_check","results":{}}""")
        }
    }

    @Test
    fun parserErrorsDoNotCarryRawResponseBodies() {
        val secret = "private-response-body"
        val error = assertThrows(AnalysisProviderException::class.java) {
            provider.parseResponse("\"$secret\"")
        }
        assertFalse(error.message.orEmpty().contains(secret))
        assertNull(error.cause)
    }

    @Test
    fun streamsOriginalAsMultipartAndPreservesRetryAfter() = runBlocking {
        val server = MockWebServer()
        server.start()
        val file = File.createTempFile("provenance-test", ".png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "12")
                    .setBody("{}")
            )
            val testProvider = OpenAiProvenanceProvider(
                apiKey = "secret-key",
                endpointUrl = server.url("/v1/content_provenance_checks").toString()
            )
            val error = runCatching {
                OriginalMediaInput(file, "image/png", "original.png", file.length()).use { input ->
                    testProvider.analyze(input, "execution-id")
                }
            }.exceptionOrNull() as AnalysisProviderException
            val request = server.takeRequest()

            assertEquals(AnalysisErrorCategory.RATE_LIMIT, error.category)
            assertEquals(12_000L, error.retryAfterMillis)
            assertEquals("execution-id", request.getHeader("X-Client-Request-Id"))
            assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
            assertTrue(request.body.readUtf8().contains("original.png"))
        } finally {
            file.delete()
            server.shutdown()
        }
    }
}
