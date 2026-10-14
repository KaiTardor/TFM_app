package com.example.besharp.data.analysis

// Copia y valida el original sin modificar sus bytes ni sus metadatos.

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FileNotFoundException

object OriginalMediaPreparer {
    suspend fun prepare(
        context: Context,
        uri: Uri,
        displayName: String,
        supportedMimeTypes: Set<String>,
        maxInputBytes: Long,
        providerName: String
    ): OriginalMediaInput = withContext(Dispatchers.IO) {
        localAccess(AnalysisErrorCategory.LOCAL_STORAGE) { AnalysisTemporaryFiles.ensureCleaned(context) }
        val resolver = context.contentResolver
        val mimeType = localAccess(AnalysisErrorCategory.SOURCE_UNAVAILABLE) { resolver.getType(uri) }?.lowercase()
            ?: throw IllegalArgumentException("No se pudo determinar el tipo de imagen")
        require(mimeType in supportedMimeTypes) {
            "$providerName no admite el formato $mimeType"
        }

        localAccess(AnalysisErrorCategory.SOURCE_UNAVAILABLE) {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                validateSize(descriptor.length, maxInputBytes, providerName)
            }
        }

        val suffix = when (mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val file = localAccess(AnalysisErrorCategory.LOCAL_STORAGE) {
            val directory = File(context.cacheDir, AnalysisTemporaryFiles.ORIGINAL_DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo crear el directorio temporal")
            File.createTempFile("provenance-original-", suffix, directory)
        }
        try {
            var total = 0L
            localAccess(AnalysisErrorCategory.SOURCE_UNAVAILABLE) {
                val source = resolver.openInputStream(uri) ?: throw FileNotFoundException("No se pudo abrir la imagen")
                source.use { input ->
                    localAccess(AnalysisErrorCategory.LOCAL_STORAGE) {
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = localAccess(AnalysisErrorCategory.SOURCE_UNAVAILABLE) { input.read(buffer) }
                                if (count == -1) break
                                total += count
                                validateSize(total, maxInputBytes, providerName)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            require(total > 0) { "La imagen original esta vacia" }
            OriginalMediaInput(
                file = file,
                mimeType = mimeType,
                fileName = displayName.takeIf { it.isNotBlank() } ?: "imagen$suffix",
                sizeBytes = total
            )
        } catch (exception: Throwable) {
            file.delete()
            throw exception
        }
    }

    internal inline fun <T> localAccess(category: AnalysisErrorCategory, block: () -> T): T = try {
        block()
    } catch (exception: Exception) {
        if (exception !is SecurityException && exception !is IOException) throw exception
        throw AnalysisProviderException(
            if (category == AnalysisErrorCategory.SOURCE_UNAVAILABLE) "No se pudo acceder a la imagen original"
            else "No se pudo preparar la copia privada",
            category = category,
            cause = exception
        )
    }

    internal fun validateSize(size: Long, maximum: Long, providerName: String) {
        if (size > maximum) {
            throw AnalysisProviderException(
                "La imagen original supera el limite de ${maximum / (1024 * 1024)} MiB",
                providerName = providerName,
                category = AnalysisErrorCategory.FILE_TOO_LARGE
            )
        }
    }
}
