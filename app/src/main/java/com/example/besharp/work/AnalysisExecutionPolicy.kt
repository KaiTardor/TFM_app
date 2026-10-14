package com.example.besharp.work

// Decide que proveedores y fallos permiten reintentar una peticion.

import com.example.besharp.data.analysis.AnalysisProviderException
import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.OpenAiProvenanceProvider
import java.io.IOException

internal object AnalysisExecutionPolicy {
    fun canRetryRequest(providerId: String): Boolean =
        providerId.startsWith(CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX) ||
            providerId == OpenAiProvenanceProvider.PROVIDER_ID

    fun shouldRetryRequest(providerId: String, exception: Exception): Boolean =
        canRetryRequest(providerId) && when (exception) {
            is IOException -> true
            is AnalysisProviderException ->
                exception.category == com.example.besharp.data.analysis.AnalysisErrorCategory.RATE_LIMIT ||
                    exception.category ==
                    com.example.besharp.data.analysis.AnalysisErrorCategory.PROVIDER_UNAVAILABLE
            else -> exception.cause is IOException
        }
}
