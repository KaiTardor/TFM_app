package com.example.besharp.data.analysis

import com.example.besharp.data.credentials.CredentialProvider
import com.example.besharp.data.credentials.ProviderCredentialStore
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.example.besharp.data.local.entity.AnalysisJobStatus
import com.example.besharp.domain.model.AnalysisInputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisProviderResolverTest {
    private val credentials = object : ProviderCredentialStore {
        override fun hasCredential(provider: CredentialProvider) = true
        override fun getCredential(provider: CredentialProvider) = "key"
        override fun saveCredential(provider: CredentialProvider, credential: String) = Unit
        override fun deleteCredential(provider: CredentialProvider) = Unit
    }
    private val custom = object : CustomApiConfigurationStore {
        override fun getConfiguration(): CustomApiConfiguration? = null
        override fun saveConfiguration(configuration: CustomApiConfiguration) = Unit
        override fun deleteConfiguration() = Unit
    }
    private val models = object : ProviderModelConfigurationStore {
        override fun getModel() = "gemini-current"
        override fun saveModel(modelId: String) = Unit
    }
    private val resolver = AnalysisProviderResolver(credentials, custom, models)

    @Test
    fun selectionUsesGeminiModelAndFixedOpenAiCapability() {
        assertEquals(
            "gemini-current",
            resolver.create(AnalysisProviderResolver.GEMINI_PROVIDER_ID).descriptor.modelId
        )
        assertEquals(
            OpenAiProvenanceProvider.MODEL_ID,
            resolver.create(AnalysisProviderResolver.OPENAI_PROVIDER_ID).descriptor.modelId
        )
        assertEquals(
            AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID,
            resolver.create(AnalysisProviderResolver.OPENAI_PROVIDER_ID).descriptor.providerId
        )
    }

    @Test
    fun describingConsentNeverReadsApiKeys() {
        val secretStore = object : ProviderCredentialStore by credentials {
            override fun getCredential(provider: CredentialProvider): String = error("No debe descifrar claves")
        }
        val descriptions = AnalysisProviderResolver(secretStore, custom, models)
        assertEquals(
            resolver.create(AnalysisProviderResolver.GEMINI_PROVIDER_ID).descriptor,
            descriptions.describe(AnalysisProviderResolver.GEMINI_PROVIDER_ID)
        )
        assertEquals(
            resolver.create(AnalysisProviderResolver.OPENAI_PROVIDER_ID).descriptor,
            descriptions.describe(AnalysisProviderResolver.OPENAI_PROVIDER_ID)
        )
    }

    @Test
    fun officialProvenanceProvidersDeclareTheirOriginalFileLimits() {
        val gemini = resolver.create(AnalysisProviderResolver.GEMINI_PROVIDER_ID)
            as OriginalMediaAnalysisProvider
        val openAi = resolver.create(AnalysisProviderResolver.OPENAI_PROVIDER_ID)
            as OriginalMediaAnalysisProvider

        assertEquals(setOf("image/png", "image/jpeg", "image/webp"), gemini.supportedMimeTypes)
        assertEquals(GeminiAnalysisProvider.MAX_INPUT_BYTES, gemini.maxInputBytes)
        assertEquals(setOf("image/png", "image/jpeg", "image/webp"), openAi.supportedMimeTypes)
        assertEquals(OriginalMediaInput.MAX_SIZE_BYTES, openAi.maxInputBytes)
    }

    @Test
    fun persistedGeminiJobKeepsItsOriginalModel() {
        val provider = resolver.create(
            AnalysisJobEntity(
                jobId = 1,
                assessmentId = 1,
                providerId = AnalysisProviderResolver.GEMINI_PROVIDER_ID,
                providerName = "Gemini",
                modelId = "gemini-snapshot",
                executionId = "execution",
                destinationFingerprint = "google-gemini:gemini-snapshot",
                status = AnalysisJobStatus.QUEUED,
                createdAt = 1
            )
        )

        assertEquals("gemini-snapshot", provider.descriptor.modelId)
        assertTrue(provider is OriginalMediaAnalysisProvider)
        assertEquals(AnalysisInputType.ORIGINAL_FILE, provider.descriptor.inputType)
    }

    @Test
    fun customDestinationMismatchRejectsBeforeReadingCredential() {
        val snapshot = CustomApiConfiguration("Custom A", "https://a.example/analyze", "model")
        val current = snapshot.copy(endpointUrl = "https://b.example/analyze")
        val configurationStore = object : CustomApiConfigurationStore by custom {
            override fun getConfiguration() = current
        }
        var credentialReads = 0
        val secretStore = object : ProviderCredentialStore by credentials {
            override fun getCredential(provider: CredentialProvider): String {
                credentialReads++
                return "token-b"
            }
        }
        val resolver = AnalysisProviderResolver(secretStore, configurationStore, models)

        for (useSnapshot in listOf(false, true)) {
            val failure = runCatching {
                if (useSnapshot) resolver.create(customJob(snapshot))
                else resolver.create(snapshot.providerId())
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("El destino custom ha cambiado", failure?.message)
        }
        assertEquals(0, credentialReads)
    }

    @Test
    fun customDestinationMutationDuringCredentialReadRejectsProvider() {
        val snapshot = CustomApiConfiguration("Custom A", "https://a.example/analyze", "model")
        var current = snapshot
        val configurationStore = object : CustomApiConfigurationStore by custom {
            override fun getConfiguration() = current
        }
        var credentialReads = 0
        val secretStore = object : ProviderCredentialStore by credentials {
            override fun getCredential(provider: CredentialProvider): String {
                assertEquals(CredentialProvider.CUSTOM_API, provider)
                credentialReads++
                current = snapshot.copy(endpointUrl = "https://b.example/analyze")
                return "token-b"
            }
        }
        val resolver = AnalysisProviderResolver(secretStore, configurationStore, models)

        for (useSnapshot in listOf(false, true)) {
            current = snapshot
            val failure = runCatching {
                if (useSnapshot) resolver.create(customJob(snapshot))
                else resolver.create(snapshot.providerId())
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("El destino custom ha cambiado", failure?.message)
        }
        assertEquals(2, credentialReads)
    }

    private fun customJob(configuration: CustomApiConfiguration) = AnalysisJobEntity(
        jobId = 1,
        assessmentId = 1,
        providerId = configuration.providerId(),
        providerName = configuration.displayName,
        modelId = configuration.modelId,
        executionId = "execution",
        destinationFingerprint = configuration.destinationFingerprint(),
        customEndpointUrl = configuration.endpointUrl,
        status = AnalysisJobStatus.QUEUED,
        createdAt = 1
    )
}
