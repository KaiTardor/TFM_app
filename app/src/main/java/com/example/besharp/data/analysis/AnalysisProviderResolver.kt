package com.example.besharp.data.analysis

// Elige el proveedor y reconstruye la configuracion con la que se creo cada trabajo.

import com.example.besharp.data.credentials.CredentialProvider
import com.example.besharp.data.credentials.ProviderCredentialStore
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.google.gson.JsonParser

class AnalysisProviderResolver(
    private val credentialStore: ProviderCredentialStore,
    private val customApiConfigurationStore: CustomApiConfigurationStore,
    private val modelConfigurationStore: ProviderModelConfigurationStore
) {
    // Mostrar destinos y pedir consentimiento no requiere descifrar credenciales.
    fun describe(providerId: String): com.example.besharp.domain.model.ProviderDescriptor = when (providerId) {
        GEMINI_PROVIDER_ID -> GeminiAnalysisProvider.describe(modelConfigurationStore.getModel())
        OPENAI_PROVIDER_ID, OPENAI_PROVENANCE_PROVIDER_ID -> OpenAiProvenanceProvider.describe()
        else -> {
            val configuration = customApiConfigurationStore.getConfiguration()
                ?: error("La API personalizada no esta configurada")
            check(configuration.providerId() == providerId) { "El destino custom ha cambiado" }
            CustomApiAnalysisProvider.describe(configuration)
        }
    }

    fun create(providerId: String): AnalysisProvider = when (providerId) {
        GEMINI_PROVIDER_ID -> GeminiAnalysisProvider(
            credentialStore.getCredential(CredentialProvider.GEMINI)
                ?: error("Gemini no tiene credencial"),
            modelConfigurationStore.getModel()
        )

        OPENAI_PROVIDER_ID, OPENAI_PROVENANCE_PROVIDER_ID -> OpenAiProvenanceProvider(
            credentialStore.getCredential(CredentialProvider.OPENAI)
                ?: error("OpenAI no tiene credencial")
        )

        else -> if (providerId.startsWith(CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX)) {
            val configuration = customApiConfigurationStore.getConfiguration()
                ?: error("La API personalizada no esta configurada")
            check(configuration.providerId() == providerId) { "El destino custom ha cambiado" }
            CustomApiAnalysisProvider(configuration, customCredential(configuration.destinationFingerprint()))
        } else {
            error("Proveedor no compatible: $providerId")
        }
    }

    // Un trabajo pendiente debe usar la configuracion con la que se creo.
    fun create(job: AnalysisJobEntity): AnalysisProvider {
        when {
            job.providerId == OPENAI_PROVENANCE_PROVIDER_ID -> return OpenAiProvenanceProvider(
                credentialStore.getCredential(CredentialProvider.OPENAI)
                    ?: error("OpenAI no tiene credencial")
            )
            job.providerId == GEMINI_PROVIDER_ID ||
                job.providerId.startsWith("$GEMINI_PROVIDER_ID:") -> return GeminiAnalysisProvider(
                credentialStore.getCredential(CredentialProvider.GEMINI)
                    ?: error("Gemini no tiene credencial"),
                job.modelId
            )
        }
        val endpoint = job.customEndpointUrl
            ?: error("El trabajo custom no contiene una instantanea del endpoint")
        val parameters = job.customParametersJson?.let { raw ->
            runCatching {
                JsonParser.parseString(raw).asJsonObject.entrySet().associate { (key, value) ->
                    key to value.asString
                }
            }.getOrDefault(emptyMap())
        }.orEmpty()
        val configuration = CustomApiConfiguration(
            displayName = job.providerName,
            endpointUrl = endpoint,
            modelId = job.modelId,
            parameters = parameters
        )
        check(configuration.destinationFingerprint() == job.destinationFingerprint) {
            "La instantanea custom no coincide con su identidad"
        }
        return CustomApiAnalysisProvider(configuration, customCredential(configuration.destinationFingerprint()))
    }

    private fun customCredential(fingerprint: String): String? = synchronized(customApiConfigurationStore) {
        check(customApiConfigurationStore.getConfiguration()?.destinationFingerprint() == fingerprint) {
            "El destino custom ha cambiado"
        }
        val credential = credentialStore.getCredential(CredentialProvider.CUSTOM_API)
        check(customApiConfigurationStore.getConfiguration()?.destinationFingerprint() == fingerprint) {
            "El destino custom ha cambiado"
        }
        credential
    }

    companion object {
        const val GEMINI_PROVIDER_ID = "google-gemini"
        const val OPENAI_PROVIDER_ID = "openai"
        const val OPENAI_PROVENANCE_PROVIDER_ID = OpenAiProvenanceProvider.PROVIDER_ID
    }
}
