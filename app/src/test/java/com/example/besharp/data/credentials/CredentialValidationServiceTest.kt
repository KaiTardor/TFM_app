package com.example.besharp.data.credentials

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialValidationServiceTest {
    private val service = CredentialValidationService()

    @Test
    fun validatesOpenAiCredentialWithoutAVisualModel() {
        val request = service.buildValidationRequest(
            CredentialProvider.OPENAI,
            "secret"
        )

        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer secret", request.header("Authorization"))
    }
}
