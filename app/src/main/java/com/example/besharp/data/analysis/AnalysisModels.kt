package com.example.besharp.data.analysis

import java.io.File

/** Copia de la imagen preparada para un analizador visual. */
data class AnalysisInput(
    val imageBytes: ByteArray,
    val mimeType: String
) {
    init {
        require(imageBytes.isNotEmpty()) { "La imagen no puede estar vacia" }
        require(mimeType.startsWith("image/")) { "Tipo MIME de imagen no valido: $mimeType" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnalysisInput) return false
        return imageBytes.contentEquals(other.imageBytes) && mimeType == other.mimeType
    }

    override fun hashCode(): Int = 31 * imageBytes.contentHashCode() + mimeType.hashCode()
}

/** Temporal privado del original; use lo elimina al terminar. */
data class OriginalMediaInput(
    val file: File,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long
) : AutoCloseable {
    init {
        require(file.isFile) { "El archivo original no existe" }
        require(mimeType.startsWith("image/")) { "Tipo MIME no compatible: $mimeType" }
        require(fileName.isNotBlank()) { "El nombre del archivo no puede estar vacio" }
        require(sizeBytes in 1..MAX_SIZE_BYTES) { "Tamaño de archivo original no valido" }
    }

    override fun close() {
        file.delete()
    }

    companion object {
        const val MAX_SIZE_BYTES = 50L * 1024 * 1024
    }
}

enum class AnalysisErrorCategory {
    AUTHENTICATION,
    RATE_LIMIT,
    QUOTA,
    PROVIDER_UNAVAILABLE,
    ACCESS_UNAVAILABLE,
    INVALID_INPUT,
    SOURCE_UNAVAILABLE,
    LOCAL_STORAGE,
    FILE_TOO_LARGE,
    HTTP,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
    CONFIGURATION
}

class AnalysisProviderException(
    message: String,
    val providerName: String? = null,
    val category: AnalysisErrorCategory = AnalysisErrorCategory.INVALID_RESPONSE,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause)
