package com.example.besharp.ui.screens.profile

// Gestiona borradores, credenciales y configuracion de los proveedores.

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.besharp.data.credentials.CredentialProvider
import com.example.besharp.data.credentials.CredentialValidationService
import com.example.besharp.data.credentials.CredentialValidator
import com.example.besharp.data.credentials.ProviderCredentialStore
import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.CustomApiConfigurationStore
import com.example.besharp.data.analysis.ProviderModelConfigurationStore
import com.example.besharp.data.analysis.ProviderModelCatalogService
import com.example.besharp.data.analysis.ProviderModelCatalog
import com.example.besharp.data.repository.AnalysisQueueRepository
import com.example.besharp.work.AnalysisScheduler
import com.example.besharp.application.analysis.CancelAnalysisJobsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data object Success : SaveState
    data class Error(val message: String) : SaveState
}

data class ProviderCredentialState(
    val isConfigured: Boolean = false,
    val isValidating: Boolean = false,
    val validationMessage: String? = null,
    val validationSucceeded: Boolean? = null,
    val saveState: SaveState = SaveState.Idle
)

data class CustomApiDraft(
    val displayName: String = "",
    val endpointUrl: String = "",
    val modelId: String = "",
    val accessToken: String = ""
)

data class GeminiModelState(
    val modelId: String,
    val models: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null
)

class ProfileViewModel(
    private val credentialStore: ProviderCredentialStore,
    private val validationService: CredentialValidator,
    private val modelConfigurationStore: ProviderModelConfigurationStore,
    private val modelCatalogService: ProviderModelCatalog,
    private val customApiConfigurationStore: CustomApiConfigurationStore,
    private val analysisQueueRepository: AnalysisQueueRepository,
    private val workScheduler: AnalysisScheduler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutationMutex = Mutex()
    private val cancelJobs = CancelAnalysisJobsUseCase(analysisQueueRepository, workScheduler)
    private val configurationVersions = mutableMapOf<CredentialProvider, Long>()
    private var customDraftVersion = 0L
    private val _providerStates = MutableStateFlow(initialStates())
    val providerStates: StateFlow<Map<CredentialProvider, ProviderCredentialState>> =
        _providerStates.asStateFlow()

    private val _credentialDrafts = MutableStateFlow(
        CredentialProvider.entries.associateWith { "" }
    )
    val credentialDrafts: StateFlow<Map<CredentialProvider, String>> = _credentialDrafts.asStateFlow()

    private val _geminiModel = MutableStateFlow(GeminiModelState(modelConfigurationStore.getModel()))
    val geminiModel: StateFlow<GeminiModelState> = _geminiModel.asStateFlow()

    private val _customApiConfiguration = MutableStateFlow(
        customApiConfigurationStore.getConfiguration()
    )
    val customApiConfiguration: StateFlow<CustomApiConfiguration?> =
        _customApiConfiguration.asStateFlow()

    private val _customApiDraft = MutableStateFlow(_customApiConfiguration.value.toDraft())
    val customApiDraft: StateFlow<CustomApiDraft> = _customApiDraft.asStateFlow()

    private val _customApiSaveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val customApiSaveState: StateFlow<SaveState> = _customApiSaveState.asStateFlow()

    fun updateCredentialDraft(provider: CredentialProvider, credential: String) {
        if (_credentialDrafts.value[provider] == credential) return
        invalidateRequests(provider, clearCatalog = true)
        _credentialDrafts.value = _credentialDrafts.value + (provider to credential)
    }

    fun updateModelDraft(modelId: String) {
        val provider = CredentialProvider.GEMINI
        val normalized = modelId.trim().removePrefix("models/")
        if (_geminiModel.value.modelId == normalized) return
        invalidateRequests(provider)
        _geminiModel.value = _geminiModel.value.copy(modelId = normalized)
    }

    fun refreshAvailableModels() {
        val provider = CredentialProvider.GEMINI
        if (_geminiModel.value.isLoading) return
        if (_providerStates.value[provider]?.saveState is SaveState.Saving) return
        val version = configurationVersions[provider] ?: 0L
        val credentialDraft = _credentialDrafts.value[provider]
        _geminiModel.value = _geminiModel.value.copy(isLoading = true, message = null)
        viewModelScope.launch {
            try {
                val credential = credentialDraft?.trim()?.takeIf { it.isNotBlank() }
                    ?: withContext(ioDispatcher) { credentialStore.getCredential(provider) }
                if ((configurationVersions[provider] ?: 0L) != version) return@launch
                if (credential == null) {
                    _geminiModel.value = _geminiModel.value.copy(message = "Configura primero una API key")
                    return@launch
                }
                val result = modelCatalogService.fetch(credential)
                if ((configurationVersions[provider] ?: 0L) != version) return@launch
                result
                    .onSuccess { models ->
                        _geminiModel.value = _geminiModel.value.copy(models = models)
                    }
                    .onFailure { error ->
                        _geminiModel.value = _geminiModel.value.copy(
                            message = error.message ?: "No se pudieron cargar los modelos"
                        )
                    }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if ((configurationVersions[provider] ?: 0L) == version) {
                    _geminiModel.value = _geminiModel.value.copy(message = "No se pudo cargar el catalogo")
                }
            } finally {
                if ((configurationVersions[provider] ?: 0L) == version) {
                    _geminiModel.value = _geminiModel.value.copy(isLoading = false)
                }
            }
        }
    }

    fun saveCredential(provider: CredentialProvider) {
        if (provider == CredentialProvider.CUSTOM_API) return
        if (_providerStates.value[provider]?.saveState is SaveState.Saving) return
        val credential = _credentialDrafts.value[provider].orEmpty()
        val modelId = _geminiModel.value.modelId
        val usesConfigurableModel = provider == CredentialProvider.GEMINI
        val alreadyConfigured = _providerStates.value[provider]?.isConfigured == true
        if ((usesConfigurableModel && modelId.isBlank()) ||
            (credential.isBlank() && !alreadyConfigured)
        ) return
        invalidateRequests(provider, clearCatalog = credential.isNotBlank())
        val version = configurationVersions[provider]
        updateState(provider) { it.copy(saveState = SaveState.Saving) }
        viewModelScope.launch {
            mutationMutex.withLock {
                try {
                    withContext(ioDispatcher) {
                        if (credential.isNotBlank() && alreadyConfigured) {
                            cancelActiveProviderJobs(provider)
                        }
                        if (usesConfigurableModel) {
                            modelConfigurationStore.saveModel(modelId)
                        }
                        if (credential.isNotBlank()) {
                            credentialStore.saveCredential(provider, credential)
                        }
                    }
                    // No borramos cambios escritos mientras se guardaba el borrador anterior.
                    val draftUnchanged = configurationVersions[provider] == version
                    invalidateRequests(provider, clearCatalog = credential.isNotBlank())
                    if (draftUnchanged) {
                        _credentialDrafts.value = _credentialDrafts.value + (provider to "")
                    }
                    updateState(provider) {
                        it.copy(
                            isConfigured = true,
                            validationMessage = if (draftUnchanged) "Configuración guardada" else null,
                            validationSucceeded = null,
                            saveState = if (draftUnchanged) SaveState.Success else SaveState.Idle
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Una excepcion no garantiza que la escritura no se haya aplicado.
                    refreshStoredConfiguration(provider)
                    if (configurationVersions[provider] == version) {
                        showCredentialError(provider, error.message ?: "No se pudo completar el guardado de la credencial")
                    } else {
                        updateState(provider) { it.copy(saveState = SaveState.Idle) }
                    }
                }
            }
        }
    }

    fun deleteCredential(provider: CredentialProvider) {
        if (_providerStates.value[provider]?.saveState is SaveState.Saving) return
        invalidateRequests(provider, clearCatalog = true)
        val version = configurationVersions[provider]
        updateState(provider) { it.copy(saveState = SaveState.Saving) }
        viewModelScope.launch {
            mutationMutex.withLock {
                try {
                    withContext(ioDispatcher) {
                        cancelActiveProviderJobs(provider)
                        credentialStore.deleteCredential(provider)
                    }
                    val draftUnchanged = configurationVersions[provider] == version
                    invalidateRequests(provider, clearCatalog = true)
                    updateState(provider) { ProviderCredentialState() }
                    if (draftUnchanged) {
                        _credentialDrafts.value = _credentialDrafts.value + (provider to "")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    refreshStoredConfiguration(provider)
                    if (configurationVersions[provider] == version) {
                        showCredentialError(provider, error.message ?: "No se pudo eliminar la credencial")
                    } else {
                        updateState(provider) { it.copy(saveState = SaveState.Idle) }
                    }
                }
            }
        }
    }

    fun validateCredential(provider: CredentialProvider) {
        if (provider == CredentialProvider.CUSTOM_API) return
        if (_providerStates.value[provider]?.isConfigured != true) return
        if (_providerStates.value[provider]?.isValidating == true ||
            _providerStates.value[provider]?.saveState is SaveState.Saving
        ) return
        val version = configurationVersions[provider] ?: 0L
        val credentialDraft = _credentialDrafts.value[provider]
        val modelId = if (provider == CredentialProvider.GEMINI) _geminiModel.value.modelId else ""
        updateState(provider) {
            it.copy(isValidating = true, validationMessage = null, validationSucceeded = null)
        }
        viewModelScope.launch {
            val result = try {
                check(provider != CredentialProvider.GEMINI || modelId.isNotBlank()) { "Configura un modelo" }
                val credential = credentialDraft?.trim()?.takeIf { it.isNotBlank() }
                    ?: withContext(ioDispatcher) { credentialStore.getCredential(provider) }
                    ?: error("No se pudo leer la credencial guardada")
                if ((configurationVersions[provider] ?: 0L) != version) return@launch
                validationService.validate(provider, credential, modelId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Result.failure(exception)
            }
            if ((configurationVersions[provider] ?: 0L) != version) return@launch
            updateState(provider) {
                it.copy(
                    isValidating = false,
                    validationMessage = if (result.isSuccess) "Credencial accesible" else
                        result.exceptionOrNull()?.message ?: "No se pudo validar la conexion",
                    validationSucceeded = result.isSuccess
                )
            }
        }
    }

    fun updateCustomApiDraft(draft: CustomApiDraft) {
        if (_customApiDraft.value == draft) return
        customDraftVersion++
        _customApiDraft.value = draft
        if (_customApiSaveState.value !is SaveState.Saving) _customApiSaveState.value = SaveState.Idle
    }

    fun saveCustomApiConfiguration() {
        if (_customApiSaveState.value is SaveState.Saving) return
        val draft = _customApiDraft.value
        val version = customDraftVersion
        _customApiSaveState.value = SaveState.Saving
        viewModelScope.launch {
            mutationMutex.withLock {
                val configuration = CustomApiConfiguration(
                    draft.displayName.trim(),
                    draft.endpointUrl.trim(),
                    draft.modelId.trim(),
                    parameters = _customApiConfiguration.value?.parameters.orEmpty()
                )
                try {
                    withContext(ioDispatcher) {
                        replaceCustomDestination(configuration, draft.accessToken)
                    }
                    _customApiConfiguration.value = configuration
                    val hasCredential = withContext(ioDispatcher) {
                        credentialStore.hasCredential(CredentialProvider.CUSTOM_API)
                    }
                    val draftUnchanged = customDraftVersion == version
                    if (draftUnchanged) _customApiDraft.value = configuration.toDraft()
                    updateState(CredentialProvider.CUSTOM_API) { it.copy(isConfigured = hasCredential) }
                    _customApiSaveState.value = if (draftUnchanged) SaveState.Success else SaveState.Idle
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    refreshStoredConfiguration(CredentialProvider.CUSTOM_API)
                    _customApiSaveState.value = if (customDraftVersion == version) SaveState.Error(
                        error.message ?: "No se pudo guardar la API personalizada"
                    ) else SaveState.Idle
                }
            }
        }
    }

    fun deleteCustomApiConfiguration() {
        if (_customApiSaveState.value is SaveState.Saving) return
        val version = customDraftVersion
        _customApiSaveState.value = SaveState.Saving
        viewModelScope.launch {
            mutationMutex.withLock {
                try {
                    withContext(ioDispatcher) {
                        cancelActiveProviderJobs(CredentialProvider.CUSTOM_API)
                        synchronized(customApiConfigurationStore) {
                            credentialStore.deleteCredential(CredentialProvider.CUSTOM_API)
                            customApiConfigurationStore.deleteConfiguration()
                        }
                    }
                    _customApiConfiguration.value = null
                    if (customDraftVersion == version) _customApiDraft.value = CustomApiDraft()
                    updateState(CredentialProvider.CUSTOM_API) { ProviderCredentialState() }
                    _customApiSaveState.value = SaveState.Idle
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    refreshStoredConfiguration(CredentialProvider.CUSTOM_API)
                    _customApiSaveState.value = if (customDraftVersion == version)
                        SaveState.Error("No se pudo eliminar la API personalizada") else SaveState.Idle
                }
            }
        }
    }

    private suspend fun refreshStoredConfiguration(provider: CredentialProvider) {
        val configured = withContext(ioDispatcher) {
            runCatching { credentialStore.hasCredential(provider) }
        }
        configured.onSuccess { value -> updateState(provider) { it.copy(isConfigured = value) } }
        if (provider == CredentialProvider.CUSTOM_API) {
            val configuration = withContext(ioDispatcher) {
                runCatching { customApiConfigurationStore.getConfiguration() }
            }
            configuration.onSuccess { _customApiConfiguration.value = it }
        }
    }

    private suspend fun cancelActiveProviderJobs(provider: CredentialProvider) {
        val prefix = when (provider) {
            CredentialProvider.GEMINI -> "google-gemini"
            CredentialProvider.OPENAI -> "openai"
            CredentialProvider.CUSTOM_API -> CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX
        }
        cancelJobs.execute(
            analysisQueueRepository.getActiveJobsForProviderPrefix(prefix).map { it.jobId },
            "La configuracion del proveedor ha cambiado"
        )
    }

    private suspend fun replaceCustomDestination(
        configuration: CustomApiConfiguration,
        accessToken: String
    ) {
        configuration.validate().getOrThrow()
        if (accessToken.isNotBlank() || customApiConfigurationStore.getConfiguration()?.destinationFingerprint() !=
            configuration.destinationFingerprint()
        ) cancelActiveProviderJobs(CredentialProvider.CUSTOM_API)
        synchronized(customApiConfigurationStore) {
            val previousConfiguration = customApiConfigurationStore.getConfiguration()
            val destinationChanged = previousConfiguration?.destinationFingerprint() !=
                configuration.destinationFingerprint()
            val credentialWillChange = destinationChanged || accessToken.isNotBlank()
            val previousCredential = if (credentialWillChange) {
                credentialStore.getCredential(CredentialProvider.CUSTOM_API)
            } else {
                null
            }

            try {
                if (destinationChanged) {
                    // El destino nuevo nunca llega a observar el token perteneciente al anterior.
                    credentialStore.deleteCredential(CredentialProvider.CUSTOM_API)
                }
                customApiConfigurationStore.saveConfiguration(configuration)
                if (accessToken.isNotBlank()) {
                    credentialStore.saveCredential(CredentialProvider.CUSTOM_API, accessToken)
                }
            } catch (error: Exception) {
                val credentialCleared = !credentialWillChange || runCatching {
                    credentialStore.deleteCredential(CredentialProvider.CUSTOM_API)
                }.isSuccess
                // No restaurar el destino anterior si no se pudo retirar el token parcial.
                val configurationRestored = (!destinationChanged || credentialCleared) && runCatching {
                    if (previousConfiguration == null) customApiConfigurationStore.deleteConfiguration()
                    else customApiConfigurationStore.saveConfiguration(previousConfiguration)
                }.isSuccess
                if (credentialWillChange && credentialCleared && configurationRestored && previousCredential != null) {
                    runCatching {
                        credentialStore.saveCredential(CredentialProvider.CUSTOM_API, previousCredential)
                    }
                }
                throw error
            }
        }
    }

    // Una respuesta solo puede actualizar la configuracion que la solicito.
    private fun invalidateRequests(provider: CredentialProvider, clearCatalog: Boolean = false) {
        configurationVersions[provider] = (configurationVersions[provider] ?: 0L) + 1
        if (provider == CredentialProvider.GEMINI) {
            _geminiModel.value = _geminiModel.value.copy(
                isLoading = false, message = null,
                models = if (clearCatalog) emptyList() else _geminiModel.value.models
            )
        }
        updateState(provider) {
            it.copy(
                isValidating = false, validationMessage = null, validationSucceeded = null,
                saveState = if (it.saveState is SaveState.Saving) it.saveState else SaveState.Idle
            )
        }
    }

    private fun showCredentialError(provider: CredentialProvider, message: String) {
        updateState(provider) {
            it.copy(validationMessage = message, validationSucceeded = false, saveState = SaveState.Error(message))
        }
    }

    private fun initialStates(): Map<CredentialProvider, ProviderCredentialState> =
        CredentialProvider.entries.associateWith { provider ->
            ProviderCredentialState(isConfigured = credentialStore.hasCredential(provider))
        }

    private fun updateState(
        provider: CredentialProvider,
        transform: (ProviderCredentialState) -> ProviderCredentialState
    ) {
        val current = _providerStates.value[provider] ?: ProviderCredentialState()
        _providerStates.value = _providerStates.value + (provider to transform(current))
    }

    private fun CustomApiConfiguration?.toDraft() = CustomApiDraft(
        displayName = this?.displayName.orEmpty(),
        endpointUrl = this?.endpointUrl.orEmpty(),
        modelId = this?.modelId.orEmpty()
    )
}

class ProfileViewModelFactory(
    private val credentialStore: ProviderCredentialStore,
    private val modelConfigurationStore: ProviderModelConfigurationStore,
    private val modelCatalogService: ProviderModelCatalogService,
    private val customApiConfigurationStore: CustomApiConfigurationStore,
    private val analysisQueueRepository: AnalysisQueueRepository,
    private val workScheduler: AnalysisScheduler,
    private val validationService: CredentialValidationService = CredentialValidationService()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            return ProfileViewModel(
                credentialStore,
                validationService,
                modelConfigurationStore,
                modelCatalogService,
                customApiConfigurationStore,
                analysisQueueRepository,
                workScheduler
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
