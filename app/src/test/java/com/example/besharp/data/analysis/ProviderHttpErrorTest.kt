package com.example.besharp.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpErrorTest {
    @Test
    fun classifiesAuthenticationAndQuotaErrors() {
        val authentication = providerHttpError("OpenAI", 401)
        val quota = providerHttpError("OpenAI", 429)

        assertEquals(401, authentication.httpStatus)
        assertTrue(authentication.message.orEmpty().contains("credenciales"))
        assertEquals(429, quota.httpStatus)
        assertTrue(quota.message.orEmpty().contains("cuota"))
    }

    @Test
    fun neverIncludesRemoteMessageForOtherHttpErrors() {
        val error = providerHttpError("Detector", 500)

        assertEquals(500, error.httpStatus)
        assertEquals(AnalysisErrorCategory.PROVIDER_UNAVAILABLE, error.category)
        assertEquals("Detector no esta disponible temporalmente (HTTP 500)", error.message)
        assertTrue(!error.message.orEmpty().contains("Servicio temporalmente no disponible"))
    }

    @Test
    fun distinguishesOpenAiQuotaFromTransientRateLimit() {
        val quota = openAiHttpError(
            "OpenAI",
            BoundedHttpResponse(
                statusCode = 429,
                isSuccessful = false,
                body = """{"error":{"code":"insufficient_quota"}}""".toByteArray(),
                headers = emptyMap()
            )
        )
        val rateLimit = openAiHttpError(
            "OpenAI",
            BoundedHttpResponse(
                statusCode = 429,
                isSuccessful = false,
                body = """{"error":{"code":"rate_limit_exceeded"}}""".toByteArray(),
                headers = emptyMap()
            )
        )

        assertEquals(AnalysisErrorCategory.QUOTA, quota.category)
        assertEquals(AnalysisErrorCategory.RATE_LIMIT, rateLimit.category)
    }
}
