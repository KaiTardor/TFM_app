package com.example.besharp.data.credentials

// Comprueba el acceso al proveedor con la credencial configurada.

import com.example.besharp.data.analysis.awaitBoundedResponse
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

fun interface CredentialValidator {
    suspend fun validate(provider: CredentialProvider, credential: String): Result<Unit>
}

class CredentialValidationService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) : CredentialValidator {
    override suspend fun validate(
        provider: CredentialProvider,
        credential: String
    ): Result<Unit> {
        return try {
            val request = buildValidationRequest(provider, credential)
            val response = client.newCall(request).awaitBoundedResponse()
            when (response.statusCode) {
                in 200..299 -> Unit
                401, 403 -> error("Credencial rechazada o sin permisos")
                429 -> error("Cuota o limite de peticiones alcanzado")
                else -> error("El proveedor devolvio HTTP ${response.statusCode}")
            }
            Result.success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            Result.failure(exception)
        }
    }

    internal fun buildValidationRequest(
        provider: CredentialProvider,
        credential: String
    ): Request = when (provider) {
        // Valida la clave, no garantiza acceso al endpoint de procedencia.
        CredentialProvider.OPENAI -> Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $credential")
            .get()
            .build()

        CredentialProvider.CUSTOM_API ->
            error("La API personalizada se valida al ejecutar un analisis")
    }
}
