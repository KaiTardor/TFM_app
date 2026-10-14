package com.example.besharp.ui.screens.profile

import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.CustomApiConfigurationStore
import com.example.besharp.data.analysis.ProviderModelConfigurationStore
import com.example.besharp.data.analysis.GeminiAnalysisProvider
import com.example.besharp.data.analysis.ProviderModelCatalogService
import com.example.besharp.data.analysis.ProviderModelCatalog
import com.example.besharp.data.credentials.CredentialProvider
import com.example.besharp.data.credentials.CredentialValidationService
import com.example.besharp.data.credentials.CredentialValidator
import com.example.besharp.data.credentials.ProviderCredentialStore
import com.example.besharp.data.local.dao.AnalysisJobWithResult
import com.example.besharp.data.local.dao.AnalysisQueueDao
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.example.besharp.data.local.entity.AuthenticityResultEntity
import com.example.besharp.data.repository.AnalysisQueueRepository
import com.example.besharp.work.AnalysisScheduler
import com.example.besharp.work.AnalysisWorkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    @Test
    fun credentialDraftIsKeptAfterErrorAndClearedOnlyAfterSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val credentials = FakeCredentialStore(failSave = true)
        try {
        val viewModel = ProfileViewModel(
            credentialStore = credentials,
            validationService = CredentialValidationService(),
            modelConfigurationStore = FakeModelStore(),
            modelCatalogService = ProviderModelCatalogService(),
            customApiConfigurationStore = EmptyCustomStore,
            analysisQueueRepository = AnalysisQueueRepository(EmptyQueueDao),
            workScheduler = EmptyScheduler,
            ioDispatcher = dispatcher
        )

        viewModel.updateCredentialDraft(CredentialProvider.GEMINI, "secret-draft")
        viewModel.saveCredential(CredentialProvider.GEMINI)
        advanceUntilIdle()

        assertEquals("secret-draft", viewModel.credentialDrafts.value[CredentialProvider.GEMINI])
        assertTrue(viewModel.providerStates.value[CredentialProvider.GEMINI]?.saveState is SaveState.Error)

        credentials.failSave = false
        viewModel.saveCredential(CredentialProvider.GEMINI)
        advanceUntilIdle()

        assertEquals("", viewModel.credentialDrafts.value[CredentialProvider.GEMINI])
        assertTrue(viewModel.providerStates.value[CredentialProvider.GEMINI]?.saveState is SaveState.Success)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun changingCustomDestinationWithBlankTokenRemovesPreviousToken() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val oldConfiguration = CustomApiConfiguration("Anterior", "https://old.example/analyze", "v1")
        val customStore = FakeCustomStore(oldConfiguration)
        val credentials = FakeCredentialStore().apply {
            saveCredential(CredentialProvider.CUSTOM_API, "old-secret")
        }
        try {
            val viewModel = ProfileViewModel(
                credentials,
                CredentialValidationService(),
                FakeModelStore(),
                ProviderModelCatalogService(),
                customStore,
                AnalysisQueueRepository(EmptyQueueDao),
                EmptyScheduler,
                dispatcher
            )

            viewModel.updateCustomApiDraft(
                CustomApiDraft("Nuevo", "https://new.example/v2/analyze", "v2", "")
            )
            viewModel.saveCustomApiConfiguration()
            advanceUntilIdle()

            assertEquals("new.example", customStore.currentConfiguration?.validate()?.getOrThrow()?.host)
            assertEquals(null, credentials.getCredential(CredentialProvider.CUSTOM_API))
            assertTrue(viewModel.customApiSaveState.value is SaveState.Success)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedTokenReplacementRestoresOldDestinationAndToken() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val oldConfiguration = CustomApiConfiguration("Anterior", "https://old.example/analyze", "v1")
        val customStore = FakeCustomStore(oldConfiguration)
        val credentials = FakeCredentialStore().apply {
            saveCredential(CredentialProvider.CUSTOM_API, "old-secret")
            failNextSave = true
        }
        try {
            val viewModel = ProfileViewModel(
                credentials,
                CredentialValidationService(),
                FakeModelStore(),
                ProviderModelCatalogService(),
                customStore,
                AnalysisQueueRepository(EmptyQueueDao),
                EmptyScheduler,
                dispatcher
            )

            viewModel.updateCustomApiDraft(
                CustomApiDraft("Nuevo", "https://new.example/analyze", "v2", "new-secret")
            )
            viewModel.saveCustomApiConfiguration()
            advanceUntilIdle()

            assertEquals(oldConfiguration, customStore.currentConfiguration)
            assertEquals("old-secret", credentials.getCredential(CredentialProvider.CUSTOM_API))
            assertEquals(
                CustomApiDraft("Nuevo", "https://new.example/analyze", "v2", "new-secret"),
                viewModel.customApiDraft.value
            )
            assertTrue(viewModel.customApiSaveState.value is SaveState.Error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun configuredProviderPersistsSelectedModelWithoutReplacingKey() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val credentials = FakeCredentialStore().apply {
            saveCredential(CredentialProvider.GEMINI, "stored-key")
        }
        val models = FakeModelStore()
        try {
            val viewModel = ProfileViewModel(
                credentials,
                CredentialValidationService(),
                models,
                ProviderModelCatalogService(),
                EmptyCustomStore,
                AnalysisQueueRepository(EmptyQueueDao),
                EmptyScheduler,
                dispatcher
            )

            viewModel.updateModelDraft("gemini-new")
            assertEquals(GeminiAnalysisProvider.DEFAULT_MODEL, models.getModel())
            viewModel.saveCredential(CredentialProvider.GEMINI)
            advanceUntilIdle()

            assertEquals("gemini-new", models.getModel())
            assertEquals("stored-key", credentials.getCredential(CredentialProvider.GEMINI))
            assertTrue(viewModel.providerStates.value[CredentialProvider.GEMINI]?.saveState is SaveState.Success)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun savingCredentialPreservesNewerDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentialStore()
            val viewModel = ProfileViewModel(
                credentials, CredentialValidationService(), FakeModelStore(),
                ProviderModelCatalogService(), EmptyCustomStore,
                AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
            )
            credentials.onSave = {
                viewModel.updateCredentialDraft(CredentialProvider.OPENAI, "newer-key")
            }
            viewModel.updateCredentialDraft(CredentialProvider.OPENAI, "submitted-key")
            viewModel.saveCredential(CredentialProvider.OPENAI)
            advanceUntilIdle()

            assertEquals("submitted-key", credentials.getCredential(CredentialProvider.OPENAI))
            assertEquals("newer-key", viewModel.credentialDrafts.value[CredentialProvider.OPENAI])
            assertEquals(SaveState.Idle, viewModel.providerStates.value[CredentialProvider.OPENAI]?.saveState)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun savingCustomConfigurationPreservesNewerDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val custom = FakeCustomStore(null)
            val viewModel = ProfileViewModel(
                FakeCredentialStore(), CredentialValidationService(), FakeModelStore(),
                ProviderModelCatalogService(), custom,
                AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
            )
            val submitted = CustomApiDraft("Enviado", "https://first.example/analyze", "v1")
            val newer = submitted.copy(endpointUrl = "https://second.example/analyze")
            custom.onSave = { viewModel.updateCustomApiDraft(newer) }
            viewModel.updateCustomApiDraft(submitted)
            viewModel.saveCustomApiConfiguration()
            advanceUntilIdle()

            assertEquals(submitted.endpointUrl, custom.currentConfiguration?.endpointUrl)
            assertEquals(newer, viewModel.customApiDraft.value)
            assertEquals(SaveState.Idle, viewModel.customApiSaveState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun replacingOnlyCustomTokenCancelsActiveJobs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val configuration = CustomApiConfiguration(
                "Detector", "https://custom.example/analyze", "v1",
                parameters = mapOf("mode" to "strict")
            )
            val customStore = FakeCustomStore(configuration)
            val job = AnalysisJobEntity(
                jobId = 7, assessmentId = 1,
                providerId = configuration.providerId(), providerName = "Detector", modelId = "v1",
                executionId = "execution", destinationFingerprint = configuration.destinationFingerprint(),
                status = com.example.besharp.data.local.entity.AnalysisJobStatus.QUEUED, createdAt = 1
            )
            val dao = object : AnalysisQueueDao by EmptyQueueDao {
                override suspend fun getActiveJobsForProviderPrefix(providerPrefix: String) = listOf(job)
                override suspend fun getAssessment(assessmentId: Long) =
                    com.example.besharp.data.local.entity.ImageAssessmentEntity(
                        assessmentId = assessmentId, imageUri = "content://photo",
                        imageDisplayName = "photo.jpg", expectedJobCount = 1, createdAt = 1
                    )
            }
            val cancelledJobs = mutableListOf<Long>()
            val scheduler = object : AnalysisScheduler by EmptyScheduler {
                override suspend fun cancel(jobId: Long) { cancelledJobs += jobId }
            }
            val credentials = FakeCredentialStore().apply {
                saveCredential(CredentialProvider.CUSTOM_API, "previous-token")
            }
            val viewModel = ProfileViewModel(
                credentials, CredentialValidationService(), FakeModelStore(),
                ProviderModelCatalogService(), customStore,
                AnalysisQueueRepository(dao), scheduler, dispatcher
            )
            viewModel.updateCustomApiDraft(viewModel.customApiDraft.value.copy(accessToken = "new-token"))
            viewModel.saveCustomApiConfiguration()
            advanceUntilIdle()

            assertEquals(listOf(job.jobId), cancelledJobs)
            assertEquals("new-token", credentials.getCredential(CredentialProvider.CUSTOM_API))
            assertEquals(configuration.parameters, customStore.currentConfiguration?.parameters)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun validationCannotPublishAfterCredentialOrModelChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (changeModel in listOf(false, true)) {
                val reply = CompletableDeferred<Result<Unit>>()
                val viewModel = networkViewModel(
                    dispatcher,
                    validator = CredentialValidator { _, _, _ -> reply.await() }
                )
                viewModel.validateCredential(CredentialProvider.GEMINI)
                runCurrent()
                assertTrue(viewModel.providerStates.value[CredentialProvider.GEMINI]?.isValidating == true)
                if (changeModel) viewModel.updateModelDraft("gemini-new")
                else viewModel.updateCredentialDraft(CredentialProvider.GEMINI, "new-key")
                reply.complete(Result.success(Unit))
                advanceUntilIdle()

                assertEquals(null, viewModel.providerStates.value[CredentialProvider.GEMINI]?.validationSucceeded)
                assertFalse(viewModel.providerStates.value[CredentialProvider.GEMINI]?.isValidating == true)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun obsoleteCatalogCannotSelectAModelOrClearNewRequestLoadingState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val oldReply = CompletableDeferred<Result<List<String>>>()
            val newReply = CompletableDeferred<Result<List<String>>>()
            val viewModel = networkViewModel(
                dispatcher,
                catalog = ProviderModelCatalog { credential ->
                    if (credential == "stored-key") oldReply.await() else newReply.await()
                }
            )
            viewModel.refreshAvailableModels()
            runCurrent()
            viewModel.updateCredentialDraft(CredentialProvider.GEMINI, "new-key")
            viewModel.refreshAvailableModels()
            runCurrent()
            oldReply.complete(Result.success(listOf("gemini-obsolete")))
            runCurrent()

            assertTrue(viewModel.geminiModel.value.models.isEmpty())
            assertEquals(GeminiAnalysisProvider.DEFAULT_MODEL, viewModel.geminiModel.value.modelId)
            assertTrue(viewModel.geminiModel.value.isLoading)

            newReply.complete(Result.success(listOf(GeminiAnalysisProvider.DEFAULT_MODEL)))
            advanceUntilIdle()
            assertEquals(listOf(GeminiAnalysisProvider.DEFAULT_MODEL), viewModel.geminiModel.value.models)
            assertFalse(viewModel.geminiModel.value.isLoading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun validationCannotPublishAfterCredentialDeletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val reply = CompletableDeferred<Result<Unit>>()
            val viewModel = networkViewModel(
                dispatcher, validator = CredentialValidator { _, _, _ -> reply.await() }
            )
            viewModel.validateCredential(CredentialProvider.GEMINI)
            runCurrent()
            viewModel.deleteCredential(CredentialProvider.GEMINI)
            runCurrent()
            reply.complete(Result.success(Unit))
            advanceUntilIdle()
            assertEquals(ProviderCredentialState(), viewModel.providerStates.value[CredentialProvider.GEMINI])
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun networkViewModel(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        validator: CredentialValidator = CredentialValidator { _, _, _ -> Result.success(Unit) },
        catalog: ProviderModelCatalog = ProviderModelCatalog { Result.success(emptyList()) }
    ) = ProfileViewModel(
        FakeCredentialStore().apply { saveCredential(CredentialProvider.GEMINI, "stored-key") },
        validator, FakeModelStore(), catalog, EmptyCustomStore,
        AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
    )

    @Test
    fun deletingConfigurationPreservesEditsMadeDuringDeletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentialStore().apply {
                saveCredential(CredentialProvider.OPENAI, "old-key")
            }
            val custom = FakeCustomStore(CustomApiConfiguration("Old", "https://old.example/analyze", "v1"))
            val viewModel = ProfileViewModel(
                credentials, CredentialValidationService(), FakeModelStore(), ProviderModelCatalogService(),
                custom, AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
            )
            credentials.onDelete = {
                viewModel.updateCredentialDraft(CredentialProvider.OPENAI, "new-draft")
            }
            viewModel.deleteCredential(CredentialProvider.OPENAI)
            advanceUntilIdle()
            assertEquals("new-draft", viewModel.credentialDrafts.value[CredentialProvider.OPENAI])
            assertEquals(null, credentials.getCredential(CredentialProvider.OPENAI))

            val newer = CustomApiDraft("New", "https://new.example/analyze", "v2")
            custom.onDelete = { viewModel.updateCustomApiDraft(newer) }
            viewModel.deleteCustomApiConfiguration()
            advanceUntilIdle()
            assertEquals(newer, viewModel.customApiDraft.value)
            assertEquals(null, custom.currentConfiguration)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun repeatedSaveEventsDoNotEnqueueDuplicateWrites() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentialStore()
            val custom = FakeCustomStore(null)
            var credentialWrites = 0
            var customWrites = 0
            credentials.onSave = { credentialWrites++ }
            custom.onSave = { customWrites++ }
            val viewModel = ProfileViewModel(
                credentials, CredentialValidationService(), FakeModelStore(), ProviderModelCatalogService(),
                custom, AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
            )
            viewModel.updateCredentialDraft(CredentialProvider.OPENAI, "key")
            viewModel.saveCredential(CredentialProvider.OPENAI)
            viewModel.saveCredential(CredentialProvider.OPENAI)
            viewModel.updateCustomApiDraft(CustomApiDraft("Detector", "https://custom.example/analyze", "v1"))
            viewModel.saveCustomApiConfiguration()
            viewModel.saveCustomApiConfiguration()
            advanceUntilIdle()
            assertEquals(1, credentialWrites)
            assertEquals(1, customWrites)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unexpectedCatalogAndValidationFailuresPublishErrorsAndStopLoading() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = networkViewModel(
                dispatcher,
                validator = CredentialValidator { _, _, _ -> error("validator failed") },
                catalog = ProviderModelCatalog { error("catalog failed") }
            )
            viewModel.refreshAvailableModels()
            viewModel.validateCredential(CredentialProvider.GEMINI)
            advanceUntilIdle()
            assertFalse(viewModel.geminiModel.value.isLoading)
            assertTrue(viewModel.geminiModel.value.message != null)
            assertEquals(false, viewModel.providerStates.value[CredentialProvider.GEMINI]?.validationSucceeded)
            assertFalse(viewModel.providerStates.value[CredentialProvider.GEMINI]?.isValidating == true)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun partialCustomDeletionReflectsStoredTokenStateWithoutLosingDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val configuration = CustomApiConfiguration("Detector", "https://custom.example/analyze", "v1")
            val credentials = FakeCredentialStore().apply { saveCredential(CredentialProvider.CUSTOM_API, "key") }
            val custom = FakeCustomStore(configuration).apply { failDelete = true }
            val viewModel = ProfileViewModel(
                credentials, CredentialValidationService(), FakeModelStore(), ProviderModelCatalogService(),
                custom, AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
            )
            val draft = viewModel.customApiDraft.value
            viewModel.deleteCustomApiConfiguration()
            advanceUntilIdle()
            assertEquals(configuration, viewModel.customApiConfiguration.value)
            assertEquals(draft, viewModel.customApiDraft.value)
            assertFalse(viewModel.providerStates.value[CredentialProvider.CUSTOM_API]?.isConfigured == true)
            assertTrue(viewModel.customApiSaveState.value is SaveState.Error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unreadableCredentialDoesNotHideTheStoredConfiguration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val saved = FakeCredentialStore().apply { saveCredential(CredentialProvider.GEMINI, "key") }
            val credentials = object : ProviderCredentialStore by saved {
                override fun getCredential(provider: CredentialProvider): String = error("storage failed")
            }
            val viewModel = ProfileViewModel(
                credentials, CredentialValidationService(), FakeModelStore(), ProviderModelCatalogService(),
                EmptyCustomStore, AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
            )
            viewModel.validateCredential(CredentialProvider.GEMINI)
            advanceUntilIdle()
            val state = viewModel.providerStates.value.getValue(CredentialProvider.GEMINI)
            assertTrue(state.isConfigured)
            assertFalse(state.isValidating)
            assertEquals(false, state.validationSucceeded)
            assertTrue(saved.hasCredential(CredentialProvider.GEMINI))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun validationRequiresAModelAndTrimsTheKeyBeforeSending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var received: String? = null
            val viewModel = networkViewModel(dispatcher, validator = CredentialValidator { _, key, _ ->
                received = key
                Result.success(Unit)
            })
            viewModel.updateModelDraft("")
            viewModel.validateCredential(CredentialProvider.GEMINI)
            advanceUntilIdle()
            assertEquals(null, received)
            assertEquals(false, viewModel.providerStates.value[CredentialProvider.GEMINI]?.validationSucceeded)

            viewModel.updateModelDraft(GeminiAnalysisProvider.DEFAULT_MODEL)
            viewModel.updateCredentialDraft(CredentialProvider.GEMINI, "  new-key  ")
            viewModel.validateCredential(CredentialProvider.GEMINI)
            advanceUntilIdle()
            assertEquals("new-key", received)
            assertEquals(true, viewModel.providerStates.value[CredentialProvider.GEMINI]?.validationSucceeded)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun credentialMutationErrorsAfterEditingDoNotPublishObsoleteMessages() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (deleting in listOf(false, true)) {
                val credentials = FakeCredentialStore().apply {
                    saveCredential(CredentialProvider.OPENAI, "old-key")
                }
                val viewModel = ProfileViewModel(
                    credentials, CredentialValidationService(), FakeModelStore(), ProviderModelCatalogService(),
                    EmptyCustomStore, AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
                )
                val failAfterEdit = {
                    viewModel.updateCredentialDraft(CredentialProvider.OPENAI, "newer-draft")
                    error("write outcome ambiguous")
                }
                if (deleting) {
                    credentials.onDelete = failAfterEdit
                    viewModel.deleteCredential(CredentialProvider.OPENAI)
                } else {
                    credentials.onSave = failAfterEdit
                    viewModel.updateCredentialDraft(CredentialProvider.OPENAI, "submitted-key")
                    viewModel.saveCredential(CredentialProvider.OPENAI)
                }
                advanceUntilIdle()

                val state = viewModel.providerStates.value.getValue(CredentialProvider.OPENAI)
                assertEquals("newer-draft", viewModel.credentialDrafts.value[CredentialProvider.OPENAI])
                assertEquals(SaveState.Idle, state.saveState)
                assertEquals(null, state.validationMessage)
                assertEquals(!deleting, state.isConfigured)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun ambiguousCredentialWriteDoesNotRollbackModelOrLoseDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentialStore().apply { onSave = { error("write outcome ambiguous") } }
            val models = FakeModelStore()
            val viewModel = ProfileViewModel(
                credentials, CredentialValidationService(), models, ProviderModelCatalogService(),
                EmptyCustomStore, AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
            )
            viewModel.updateCredentialDraft(CredentialProvider.GEMINI, "submitted-key")
            viewModel.updateModelDraft("gemini-new")
            viewModel.saveCredential(CredentialProvider.GEMINI)
            advanceUntilIdle()

            assertEquals("gemini-new", models.getModel())
            assertEquals("submitted-key", credentials.getCredential(CredentialProvider.GEMINI))
            assertEquals("submitted-key", viewModel.credentialDrafts.value[CredentialProvider.GEMINI])
            val state = viewModel.providerStates.value.getValue(CredentialProvider.GEMINI)
            assertTrue(state.isConfigured)
            assertTrue(state.saveState is SaveState.Error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun customMutationErrorsAfterEditingPreserveDraftAndClearSaving() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (deleting in listOf(false, true)) {
                val configuration = CustomApiConfiguration("Old", "https://old.example/analyze", "v1")
                val custom = FakeCustomStore(configuration)
                val viewModel = ProfileViewModel(
                    FakeCredentialStore(), CredentialValidationService(), FakeModelStore(), ProviderModelCatalogService(),
                    custom, AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
                )
                val newer = CustomApiDraft("Newer", "https://newer.example/analyze", "v3")
                val failAfterEdit = {
                    viewModel.updateCustomApiDraft(newer)
                    error("write outcome ambiguous")
                }
                if (deleting) {
                    custom.onDelete = failAfterEdit
                    viewModel.deleteCustomApiConfiguration()
                } else {
                    custom.onSave = failAfterEdit
                    viewModel.updateCustomApiDraft(CustomApiDraft("Submitted", "https://submitted.example/analyze", "v2"))
                    viewModel.saveCustomApiConfiguration()
                }
                advanceUntilIdle()

                assertEquals(newer, viewModel.customApiDraft.value)
                assertEquals(SaveState.Idle, viewModel.customApiSaveState.value)
                assertEquals(custom.currentConfiguration, viewModel.customApiConfiguration.value)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedConfigurationReadDoesNotHideConfigurationOrPreventTokenRefresh() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val configuration = CustomApiConfiguration("Old", "https://old.example/analyze", "v1")
            val credentials = FakeCredentialStore().apply { saveCredential(CredentialProvider.CUSTOM_API, "key") }
            var unreadable = false
            val custom = object : CustomApiConfigurationStore {
                override fun getConfiguration(): CustomApiConfiguration {
                    if (unreadable) error("read failed")
                    return configuration
                }
                override fun saveConfiguration(configuration: CustomApiConfiguration) = Unit
                override fun deleteConfiguration() {
                    unreadable = true
                    error("delete failed")
                }
            }
            val viewModel = ProfileViewModel(
                credentials, CredentialValidationService(), FakeModelStore(), ProviderModelCatalogService(),
                custom, AnalysisQueueRepository(EmptyQueueDao), EmptyScheduler, dispatcher
            )
            val draft = viewModel.customApiDraft.value
            viewModel.deleteCustomApiConfiguration()
            advanceUntilIdle()

            assertEquals(configuration, viewModel.customApiConfiguration.value)
            assertEquals(draft, viewModel.customApiDraft.value)
            assertFalse(viewModel.providerStates.value.getValue(CredentialProvider.CUSTOM_API).isConfigured)
            assertTrue(viewModel.customApiSaveState.value is SaveState.Error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeCredentialStore(var failSave: Boolean = false) : ProviderCredentialStore {
        private val values = mutableMapOf<CredentialProvider, String>()
        var failNextSave = false
        var onSave: (() -> Unit)? = null
        var onDelete: (() -> Unit)? = null
        override fun hasCredential(provider: CredentialProvider) = provider in values
        override fun getCredential(provider: CredentialProvider) = values[provider]
        override fun saveCredential(provider: CredentialProvider, credential: String) {
            if (failSave || failNextSave) {
                failNextSave = false
                error("storage failed")
            }
            values[provider] = credential
            onSave?.invoke()
        }
        override fun deleteCredential(provider: CredentialProvider) {
            values.remove(provider)
            onDelete?.invoke()
        }
    }

    private class FakeModelStore : ProviderModelConfigurationStore {
        private var model = GeminiAnalysisProvider.DEFAULT_MODEL
        override fun getModel() = model
        override fun saveModel(modelId: String) { model = modelId }
    }

    private class FakeCustomStore(initial: CustomApiConfiguration?) : CustomApiConfigurationStore {
        var currentConfiguration = initial
        var failDelete = false
        var onSave: (() -> Unit)? = null
        var onDelete: (() -> Unit)? = null
        override fun getConfiguration() = currentConfiguration
        override fun saveConfiguration(configuration: CustomApiConfiguration) {
            currentConfiguration = configuration
            onSave?.invoke()
        }
        override fun deleteConfiguration() {
            if (failDelete) error("configuration deletion failed")
            currentConfiguration = null
            onDelete?.invoke()
        }
    }

    private object EmptyCustomStore : CustomApiConfigurationStore {
        override fun getConfiguration(): CustomApiConfiguration? = null
        override fun saveConfiguration(configuration: CustomApiConfiguration) = Unit
        override fun deleteConfiguration() = Unit
    }

    private object EmptyScheduler : AnalysisScheduler {
        override suspend fun enqueue(jobId: Long) = Unit
        override suspend fun cancel(jobId: Long) = Unit
        override suspend fun cancelAllRemote() = Unit
        override suspend fun workState(jobId: Long) = AnalysisWorkState.ABSENT
    }

    private object EmptyQueueDao : AnalysisQueueDao {
        override suspend fun insertAssessment(
            assessment: com.example.besharp.data.local.entity.ImageAssessmentEntity
        ) = 0L
        override suspend fun insertJob(job: AnalysisJobEntity) = 0L
        override suspend fun activatePreparedAssessment(assessmentId: Long) = 0
        override suspend fun insertResult(result: AuthenticityResultEntity) = Unit
        override suspend fun insertSignals(
            signals: List<com.example.besharp.data.local.entity.EvidenceSignalEntity>
        ) = Unit
        override suspend fun getJob(jobId: Long): AnalysisJobEntity? = null
        override suspend fun getAssessment(assessmentId: Long) = null
        override suspend fun getJobsForAssessment(assessmentId: Long) = emptyList<AnalysisJobWithResult>()
        override suspend fun getReadyAssessmentIds() = emptyList<Long>()
        override suspend fun completeAssessmentIfActive(
            assessmentId: Long,
            completedAt: Long,
            finalResolution: String,
            summary: String,
            reason: String,
            policyVersion: Int,
            decisiveSignalIdsJson: String,
            conflictingSignalIdsJson: String
        ) = 0
        override suspend fun getActiveJobs() = emptyList<AnalysisJobEntity>()
        override suspend fun getActiveJobsForProviderPrefix(providerPrefix: String) = emptyList<AnalysisJobEntity>()
        override suspend fun transitionQueuedToRunning(jobId: Long, startedAt: Long) = 0
        override suspend fun transitionRunningToQueuedForRetry(jobId: Long) = 0
        override suspend fun transitionRunningToSucceeded(jobId: Long, completedAt: Long) = 0
        override suspend fun transitionRunningToFailed(jobId: Long, completedAt: Long, message: String, category: String, httpStatus: Int?) = 0
        override suspend fun transitionActiveToCancelled(jobId: Long, completedAt: Long, message: String) = 0
        override suspend fun cancelAllActive(completedAt: Long, message: String) = 0
        override fun observeRecentAssessments(limit: Int) =
            flowOf(emptyList<com.example.besharp.data.local.dao.ImageAssessmentWithJobs>())
        override fun observeAssessment(assessmentId: Long) =
            flowOf<com.example.besharp.data.local.dao.ImageAssessmentWithJobs?>(null)
        override fun observeAnalyzedImageUris(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun deleteForImage(imageUri: String) = Unit
        override suspend fun deleteOtherCompletedAssessments(
            imageUri: String,
            keepAssessmentId: Long
        ) = Unit
    }
}
