package com.example.besharp.data.analysis

import com.example.besharp.domain.model.AuthenticityResult
import com.example.besharp.domain.model.ProviderDescriptor
import com.google.gson.JsonParser

/** Identifica el proveedor y el tipo de analisis que realiza. */
interface AnalysisProvider {
    val descriptor: ProviderDescriptor
}

/** Recibe una copia preparada, no el archivo original. */
interface NormalizedImageAnalysisProvider : AnalysisProvider {
    suspend fun analyze(input: AnalysisInput, executionId: String): AuthenticityResult
}

/** Recibe el original para conservar sus señales de procedencia. */
interface OriginalMediaAnalysisProvider : AnalysisProvider {
    val supportedMimeTypes: Set<String>
    val maxInputBytes: Long
    suspend fun analyze(input: OriginalMediaInput, executionId: String): AuthenticityResult
}
// Convertimos los errores HTTP en categorias que entiende el Worker.
internal fun providerHttpError(
    providerName: String,
    status: Int
): AnalysisProviderException {
    val category = httpErrorCategory(status)
    val message = when (category) {
        AnalysisErrorCategory.AUTHENTICATION -> "$providerName rechazo las credenciales o permisos"
        AnalysisErrorCategory.RATE_LIMIT -> "$providerName alcanzo el limite de peticiones o cuota"
        AnalysisErrorCategory.PROVIDER_UNAVAILABLE -> "$providerName no esta disponible temporalmente (HTTP $status)"
        else -> "$providerName devolvio un error HTTP $status"
    }
    return AnalysisProviderException(
        message = message,
        providerName = providerName,
        category = category,
        httpStatus = status
    )
}

internal fun openAiHttpError(
    providerName: String,
    response: BoundedHttpResponse
): AnalysisProviderException {
    val errorCode = runCatching {
        JsonParser.parseString(response.body.toString(Charsets.UTF_8))
            .asJsonObject
            .getAsJsonObject("error")
            ?.get("code")
            ?.takeUnless { it.isJsonNull }
            ?.asString
    }.getOrNull()
    val category = when (errorCode) {
        "insufficient_quota", "billing_hard_limit_reached" -> AnalysisErrorCategory.QUOTA
        else -> httpErrorCategory(response.statusCode)
    }
    return AnalysisProviderException(
        message = "$providerName devolvio un error HTTP ${response.statusCode}",
        providerName = providerName,
        category = category,
        httpStatus = response.statusCode
    )
}

private fun httpErrorCategory(status: Int): AnalysisErrorCategory = when (status) {
    401, 403 -> AnalysisErrorCategory.AUTHENTICATION
    429 -> AnalysisErrorCategory.RATE_LIMIT
    in 500..599 -> AnalysisErrorCategory.PROVIDER_UNAVAILABLE
    else -> AnalysisErrorCategory.HTTP
}
