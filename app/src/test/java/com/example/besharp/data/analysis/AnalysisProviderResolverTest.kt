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
    private val resolver = AnalysisProviderResolver(credentials, custom)

    @Test
    fun selectionUsesFixedOpenAiProvenanceModel() {
        assertEquals(
            OpenAiProvenanceProvider.MODEL_ID,
            resolver.describe(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID).modelId
        )
        assertEquals(
            AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID,
            resolver.describe(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID).providerId
        )
    }

    @Test
    fun describingConsentNeverReadsApiKeys() {
        val secretStore = object : ProviderCredentialStore by credentials {
            override fun getCredential(provider: CredentialProvider): String = error("No debe descifrar claves")
        }
        val descriptions = AnalysisProviderResolver(secretStore, custom)
        assertEquals(
            OpenAiProvenanceProvider.describe(),
            descriptions.describe(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)
        )
    }

    @Test
    fun persistedOpenAiJobUsesFixedProvenanceModel() {
        val provider = resolver.create(
            AnalysisJobEntity(
                jobId = 1,
                assessmentId = 1,
                providerId = AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID,
                providerName = "OpenAI",
                modelId = OpenAiProvenanceProvider.MODEL_ID,
                executionId = "execution",
                destinationFingerprint = "${AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID}:${OpenAiProvenanceProvider.MODEL_ID}",
                status = AnalysisJobStatus.QUEUED,
                createdAt = 1
            )
        )

        assertEquals(OpenAiProvenanceProvider.MODEL_ID, provider.descriptor.modelId)
        assertTrue(provider is OriginalMediaAnalysisProvider)
        assertEquals(AnalysisInputType.ORIGINAL_FILE, provider.descriptor.inputType)
        val originalProvider = provider as OriginalMediaAnalysisProvider
        assertEquals(setOf("image/png", "image/jpeg", "image/webp"), originalProvider.supportedMimeTypes)
        assertEquals(OriginalMediaInput.MAX_SIZE_BYTES, originalProvider.maxInputBytes)
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
        val resolver = AnalysisProviderResolver(secretStore, configurationStore)

        val failure = runCatching { resolver.create(customJob(snapshot)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("El destino custom ha cambiado", failure?.message)
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
        val resolver = AnalysisProviderResolver(secretStore, configurationStore)

        val failure = runCatching { resolver.create(customJob(snapshot)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("El destino custom ha cambiado", failure?.message)
        assertEquals(1, credentialReads)
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
