package com.example.besharp.data.credentials

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialValidationServiceTest {
    private val service = CredentialValidationService()

    @Test
    fun validatesTheConfiguredGeminiModel() {
        val request = service.buildValidationRequest(
            CredentialProvider.GEMINI,
            "secret",
            "gemini-current"
        )

        assertEquals("/v1beta/models/gemini-current", request.url.encodedPath)
        assertEquals("secret", request.header("x-goog-api-key"))
    }

    @Test
    fun validatesOpenAiCredentialWithoutAVisualModel() {
        val request = service.buildValidationRequest(
            CredentialProvider.OPENAI,
            "secret",
            ""
        )

        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer secret", request.header("Authorization"))
    }
}
