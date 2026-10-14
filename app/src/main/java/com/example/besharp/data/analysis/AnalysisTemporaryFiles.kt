package com.example.besharp.data.analysis

// Limpia los temporales antes de permitir que los preparadores creen nuevas copias.

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AnalysisTemporaryFiles {
    private val initialization = Mutex()
    private var cleaned = false
    const val NORMALIZED_DIRECTORY = "besharp-normalized"
    const val ORIGINAL_DIRECTORY = "besharp-originals"
    const val PREPROCESSING_DIRECTORY = "besharp-preprocessing"

    suspend fun ensureCleaned(context: Context) = initialization.withLock {
        if (cleaned) return@withLock
        withContext(Dispatchers.IO) { cleanupOnProcessStart(context) }
        cleaned = true
    }

    private fun cleanupOnProcessStart(context: Context) {
        listOf(NORMALIZED_DIRECTORY, ORIGINAL_DIRECTORY, PREPROCESSING_DIRECTORY).forEach { name ->
            File(context.cacheDir, name).deleteRecursively()
        }
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("analysis-source-") ||
                file.name.startsWith("provenance-original-")
            ) {
                file.delete()
            }
        }
    }
}
