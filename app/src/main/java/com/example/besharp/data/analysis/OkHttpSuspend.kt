package com.example.besharp.data.analysis

// Convierte llamadas HTTP en corrutinas cancelables con respuestas de tamano limitado.

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException

internal const val MAX_HTTP_RESPONSE_BYTES = 256 * 1024

internal data class BoundedHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, String>
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

internal class ResponseTooLargeException : IOException("HTTP response exceeds the allowed size")

// Cancelar la corrutina cancela la llamada; use cierra la respuesta incluso si falla la lectura.
internal suspend fun Call.awaitBoundedResponse(
    maxBytes: Int = MAX_HTTP_RESPONSE_BYTES
): BoundedHttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, exception: IOException) {
            continuation.resumeWith(Result.failure(exception))
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    val contentLength = it.body?.contentLength() ?: 0L
                    if (contentLength > maxBytes) throw ResponseTooLargeException()
                    val body = it.body?.byteStream()?.readAtMost(maxBytes) ?: ByteArray(0)
                    continuation.resumeWith(
                        Result.success(
                            BoundedHttpResponse(
                                statusCode = it.code,
                                body = body,
                                headers = it.headers.names().associateWith { name -> it.header(name).orEmpty() }
                            )
                        )
                    )
                }
            } catch (exception: Throwable) {
                continuation.resumeWith(Result.failure(exception))
            }
        }
    })
}

// Lee un byte extra para detectar excesos aunque Content-Length falte o sea incorrecto.
private fun java.io.InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
        if (count == -1) break
        total += count
        if (total > maxBytes) throw ResponseTooLargeException()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
