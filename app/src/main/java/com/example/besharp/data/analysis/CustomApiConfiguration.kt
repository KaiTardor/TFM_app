package com.example.besharp.data.analysis

// Valida el endpoint personalizado y calcula su identidad para el consentimiento.

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

data class CustomApiConfiguration(
    val displayName: String,
    val endpointUrl: String,
    val modelId: String,
    val parameters: Map<String, String> = emptyMap()
) {
    fun validate(): Result<HttpUrl> = runCatching {
        require(displayName.trim().isNotEmpty()) { "El nombre del proveedor es obligatorio" }
        require(modelId.trim().isNotEmpty()) { "El modelo es obligatorio" }
        require(parameters.size <= MAX_PARAMETERS) { "Se permiten como maximo $MAX_PARAMETERS parametros" }
        parameters.forEach { (key, value) ->
            require(PARAMETER_KEY.matches(key)) { "Parametro no valido: $key" }
            require(!SENSITIVE_PARAMETER_KEY.containsMatchIn(key)) {
                "Las credenciales deben configurarse fuera de los parametros"
            }
            require(value.length <= MAX_PARAMETER_VALUE_LENGTH) { "El valor de $key es demasiado largo" }
        }

        val endpoint = endpointUrl.trim().toHttpUrlOrNull()
            ?: error("La URL del endpoint no es valida")
        require(endpoint.isHttps) { "El endpoint debe usar HTTPS" }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) {
            "No incluyas credenciales en la URL"
        }
        require(endpoint.query == null) { "La URL no debe incluir parametros de consulta" }
        require(endpoint.fragment == null) { "La URL no debe incluir fragmentos" }
        endpoint
    }

    fun destinationFingerprint(): String {
        val endpoint = validate().getOrThrow()
        val destination = listOf(
            endpoint.host.lowercase(),
            endpoint.port.toString(),
            endpoint.encodedPath,
            modelId.trim()
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(destination.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun providerId(): String = "$CUSTOM_PROVIDER_PREFIX${destinationFingerprint()}"

    companion object {
        const val CUSTOM_PROVIDER_FAMILY = "custom-api"
        const val CUSTOM_PROVIDER_PREFIX = "$CUSTOM_PROVIDER_FAMILY:"
        private const val MAX_PARAMETERS = 10
        private const val MAX_PARAMETER_VALUE_LENGTH = 200
        private val PARAMETER_KEY = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
        private val SENSITIVE_PARAMETER_KEY =
            Regex(
                "token|secret|password|credential|api[_.-]?key|authorization|bearer|jwt|session|cookie",
                RegexOption.IGNORE_CASE
            )
    }
}

interface CustomApiConfigurationStore {
    fun getConfiguration(): CustomApiConfiguration?
    fun saveConfiguration(configuration: CustomApiConfiguration)
    fun deleteConfiguration()
}

class SharedPreferencesCustomApiConfigurationStore(context: Context) : CustomApiConfigurationStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun getConfiguration(): CustomApiConfiguration? {
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null) ?: return null
        val endpointUrl = preferences.getString(KEY_ENDPOINT_URL, null) ?: return null
        val modelId = preferences.getString(KEY_MODEL_ID, null) ?: return null
        val parameters = preferences.getString(KEY_PARAMETERS, null)?.let(::parseParameters).orEmpty()
        return CustomApiConfiguration(displayName, endpointUrl, modelId, parameters)
    }

    override fun saveConfiguration(configuration: CustomApiConfiguration) {
        configuration.validate().getOrThrow()
        preferences.edit()
            .putString(KEY_DISPLAY_NAME, configuration.displayName.trim())
            .putString(KEY_ENDPOINT_URL, configuration.endpointUrl.trim())
            .putString(KEY_MODEL_ID, configuration.modelId.trim())
            .putString(KEY_PARAMETERS, JsonObject().apply {
                configuration.parameters.forEach { (key, value) -> addProperty(key, value) }
            }.toString())
            .commit()
            .also { committed -> check(committed) { "No se pudo confirmar la configuracion custom" } }
    }

    override fun deleteConfiguration() {
        check(preferences.edit().clear().commit()) {
            "No se pudo confirmar el borrado de la configuracion custom"
        }
    }

    private fun parseParameters(raw: String): Map<String, String> = runCatching {
        JsonParser.parseString(raw).asJsonObject.entrySet().associate { (key, value) ->
            key to value.asString
        }
    }.getOrDefault(emptyMap())

    companion object {
        private const val PREFERENCES_NAME = "besharp_custom_api"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ENDPOINT_URL = "endpoint_url"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_PARAMETERS = "parameters"
    }
}
