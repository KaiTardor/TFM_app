package com.example.besharp.data.analysis

import android.content.Context
import com.example.besharp.data.credentials.CredentialProvider

interface ProviderModelConfigurationStore {
    fun getModel(): String
    fun saveModel(modelId: String)
}

class SharedPreferencesProviderModelConfigurationStore(context: Context) :
    ProviderModelConfigurationStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun getModel(): String =
        preferences.getString(CredentialProvider.GEMINI.storageKey, null)?.takeIf { it.isNotBlank() }
            ?: GeminiAnalysisProvider.DEFAULT_MODEL

    override fun saveModel(modelId: String) {
        val normalized = modelId.trim().removePrefix("models/")
        require(normalized.matches(MODEL_ID)) { "Identificador de modelo no valido" }
        check(preferences.edit().putString(CredentialProvider.GEMINI.storageKey, normalized).commit()) {
            "No se pudo guardar el modelo"
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "besharp_provider_models"
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
