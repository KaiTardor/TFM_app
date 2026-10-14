package com.example.besharp.data.local

// Gestiona archivos de captura sin borrar fotos cuyo estado sea desconocido.

import java.io.File
import kotlinx.coroutines.CancellationException

internal fun createCapturedPhotoFile(directory: File): File {
    check(directory.isDirectory || directory.mkdirs()) {
        "No se pudo crear el directorio de fotos"
    }
    // Cada captura reserva su archivo; dos capturas no comparten nombre.
    val file = File.createTempFile("BESHARP_", ".jpg", directory)
    try {
        check(File(directory, "${file.name}.pending").createNewFile())
        return file
    } catch (exception: Exception) {
        file.delete()
        throw exception
    }
}

internal fun markCaptureReady(file: File) {
    check(!File(file.parentFile, "${file.name}.cancelled").exists()) {
        "La captura ya esta descartada"
    }
    check(file.isFile && file.length() > 0) { "La captura esta vacia o no existe" }
    markCapture(file, "ready")
}

internal fun cancelCapturedPhoto(file: File) {
    check(!File(file.parentFile, "${file.name}.ready").exists() &&
        (File(file.parentFile, "${file.name}.pending").exists() ||
            File(file.parentFile, "${file.name}.cancelled").exists())
    ) { "La captura ya no esta pendiente" }
    // Guardamos la decision antes de borrar para poder reintentarlo tras un cierre.
    markCapture(file, "cancelled")
    deleteCancelledCapture(file)
}

private fun markCapture(file: File, state: String) {
    val marker = File(file.parentFile, "${file.name}.$state")
    check(marker.createNewFile() || marker.isFile
    ) { "No se pudo guardar el estado de la captura" }
    deleteCaptureMarker(file, "pending")
}

internal fun acknowledgeCapturedPhoto(file: File) {
    // Retiramos pending antes de ready para no ofrecer descartar una foto ya registrada.
    deleteCaptureMarker(file, "pending")
    deleteCaptureMarker(file, "ready")
}

private fun deleteCaptureMarker(file: File, state: String) {
    val marker = File(file.parentFile, "${file.name}.$state")
    check(!marker.exists() || marker.delete()) { "No se pudo borrar el marcador $state" }
}

private fun deleteCancelledCapture(file: File) {
    check(!file.exists() || file.delete()) { "No se pudo borrar la captura descartada" }
    deleteCaptureMarker(file, "ready")
    deleteCaptureMarker(file, "pending")
    deleteCaptureMarker(file, "cancelled")
}

internal data class RecoverableCapture(val file: File, val result: Boolean?)

internal fun listRecoverableCaptures(directory: File): List<RecoverableCapture> {
    if (!directory.exists()) return emptyList()
    val markers = checkNotNull(directory.listFiles()) { "No se pudieron leer las capturas" }
        .filter { it.name.startsWith("BESHARP_") &&
            (it.name.endsWith(".jpg.pending") || it.name.endsWith(".jpg.ready") ||
                it.name.endsWith(".jpg.cancelled")) }
    return markers.map { File(directory, it.name.substringBeforeLast('.')) }.distinct()
        .sortedBy { it.name }.map { file ->
            val result = when {
                File(directory, "${file.name}.cancelled").exists() -> false
                File(directory, "${file.name}.ready").exists() -> true
                else -> null
            }
            RecoverableCapture(file, result)
        }
}

internal suspend fun recoverCapturedPhotos(directory: File, register: suspend (File) -> Unit) {
    var failure: Exception? = null
    listRecoverableCaptures(directory).forEach { capture ->
        try {
            when (capture.result) {
                false -> deleteCancelledCapture(capture.file)
                true -> {
                    check(capture.file.isFile && capture.file.length() > 0) {
                        "La captura esta vacia o no existe"
                    }
                    register(capture.file)
                    acknowledgeCapturedPhoto(capture.file)
                }
                null -> Unit // Puede seguir abierta en la camara; requiere una decision explicita.
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Una foto fallida no bloquea las demas; su marcador permite reintentar.
            if (failure == null) failure = exception
        }
    }
    failure?.let { throw it }
}
