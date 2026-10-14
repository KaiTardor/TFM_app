package com.example.besharp.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertFalse

class CustomApiConfigurationTest {
    @Test
    fun acceptsHttpsEndpoint() {
        val result = CustomApiConfiguration(
            displayName = "Detector propio",
            endpointUrl = "https://example.com/v1/analyze",
            modelId = "detector-v1"
        ).validate()

        assertTrue(result.isSuccess)
        assertEquals("example.com", result.getOrThrow().host)
    }

    @Test
    fun rejectsHttpEndpoint() {
        val result = CustomApiConfiguration(
            displayName = "Detector propio",
            endpointUrl = "http://example.com/v1/analyze",
            modelId = "detector-v1"
        ).validate()

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsCredentialsInsideUrl() {
        val result = CustomApiConfiguration(
            displayName = "Detector propio",
            endpointUrl = "https://user:password@example.com/analyze",
            modelId = "detector-v1"
        ).validate()

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsQueryAndFragment() {
        listOf(
            "https://example.com/analyze?token=secret",
            "https://example.com/analyze#secret"
        ).forEach { endpoint ->
            val result = CustomApiConfiguration(
                displayName = "Detector propio",
                endpointUrl = endpoint,
                modelId = "detector-v1"
            ).validate()

            assertTrue(endpoint, result.isFailure)
        }
    }

    @Test
    fun destinationIdentityIgnoresDisplayNameButTracksEndpointAndModel() {
        val base = CustomApiConfiguration("Uno", "https://example.com:443/analyze", "model-v1")
        val renamed = base.copy(displayName = "Dos")
        val changedPath = base.copy(endpointUrl = "https://example.com/other")

        assertEquals(base.providerId(), renamed.providerId())
        assertTrue(base.providerId() != changedPath.providerId())
    }

    @Test
    fun rejectsCredentialLikeCustomParameters() {
        listOf("accessToken", "authorization", "bearer", "jwt", "sessionId", "cookie").forEach { key ->
            val result = CustomApiConfiguration(
                "Detector",
                "https://example.com/analyze",
                "v1",
                parameters = mapOf(key to "must-not-be-persisted")
            ).validate()

            assertFalse(key, result.isSuccess)
        }
    }
}
