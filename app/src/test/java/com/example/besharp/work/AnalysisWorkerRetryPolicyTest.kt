package com.example.besharp.work

import com.example.besharp.data.analysis.AnalysisErrorCategory
import com.example.besharp.data.analysis.AnalysisProviderException
import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.OpenAiProvenanceProvider
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisWorkerRetryPolicyTest {
    private val customProviderId = CustomApiConfiguration(
        "Custom",
        "https://custom.example/analyze",
        "v1"
    ).providerId()

    @Test
    fun retriesTransientFailuresForCustomAndProvenanceProviders() {
        val networkFailure = IOException("remote-controlled detail")

        assertTrue(AnalysisExecutionPolicy.shouldRetryRequest(customProviderId, networkFailure))
        assertTrue(AnalysisExecutionPolicy.shouldRetryRequest(OpenAiProvenanceProvider.PROVIDER_ID, networkFailure))
        assertFalse(AnalysisExecutionPolicy.shouldRetryRequest("unsupported-provider", networkFailure))
    }

    @Test
    fun customRetryRequiresTransientCategory() {
        assertTrue(AnalysisExecutionPolicy.shouldRetryRequest(
            customProviderId,
            AnalysisProviderException(
                "quota detail",
                category = AnalysisErrorCategory.RATE_LIMIT,
                httpStatus = 429
            )
        ))
        assertFalse(AnalysisExecutionPolicy.shouldRetryRequest(
            customProviderId,
            AnalysisProviderException(
                "authentication detail",
                category = AnalysisErrorCategory.AUTHENTICATION,
                httpStatus = 401
            )
        ))
    }
}
