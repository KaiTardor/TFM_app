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

class ProfileViewModel(
    private val credentialStore: ProviderCredentialStore,
    private val validationService: CredentialValidator,
    private val customApiConfigurationStore: CustomApiConfigurationStore,
    private val analysisQueueRepository: AnalysisQueueRepository,
    private val workScheduler: AnalysisScheduler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutationMutex = Mutex()
    private val cancelJobs = CancelAnalysisJobsUseCase(analysisQueueRepository, workScheduler)
    private var openAiConfigurationVersion = 0L
    private var customDraftVersion = 0L
    private val _providerStates = MutableStateFlow(initialStates())
    val providerStates: StateFlow<Map<CredentialProvider, ProviderCredentialState>> =
        _providerStates.asStateFlow()

    private val _openAiCredentialDraft = MutableStateFlow("")
    val openAiCredentialDraft: StateFlow<String> = _openAiCredentialDraft.asStateFlow()

    private val _customApiConfiguration = MutableStateFlow(
        customApiConfigurationStore.getConfiguration()
    )
    val customApiConfiguration: StateFlow<CustomApiConfiguration?> =
        _customApiConfiguration.asStateFlow()

    private val _customApiDraft = MutableStateFlow(_customApiConfiguration.value.toDraft())
    val customApiDraft: StateFlow<CustomApiDraft> = _customApiDraft.asStateFlow()

    private val _customApiSaveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val customApiSaveState: StateFlow<SaveState> = _customApiSaveState.asStateFlow()

    fun updateOpenAiCredentialDraft(credential: String) {
        if (_openAiCredentialDraft.value == credential) return
        invalidateOpenAiRequests()
        _openAiCredentialDraft.value = credential
    }

    // Conserva las ediciones nuevas si termina de guardarse un borrador anterior.
    fun saveOpenAiCredential() {
        if (_providerStates.value[CredentialProvider.OPENAI]?.saveState is SaveState.Saving) return
        val credential = _openAiCredentialDraft.value
        val alreadyConfigured = _providerStates.value[CredentialProvider.OPENAI]?.isConfigured == true
        if (credential.isBlank() && !alreadyConfigured) return
        invalidateOpenAiRequests()
        val version = openAiConfigurationVersion
        updateState(CredentialProvider.OPENAI) { it.copy(saveState = SaveState.Saving) }
        viewModelScope.launch {
            mutationMutex.withLock {
                try {
                    withContext(ioDispatcher) {
                        if (credential.isNotBlank() && alreadyConfigured) {
                            cancelActiveProviderJobs(CredentialProvider.OPENAI)
                        }
                        if (credential.isNotBlank()) {
                            credentialStore.saveCredential(CredentialProvider.OPENAI, credential)
                        }
                    }
                    val draftUnchanged = openAiConfigurationVersion == version
                    invalidateOpenAiRequests()
                    if (draftUnchanged) {
                        _openAiCredentialDraft.value = ""
                    }
                    updateState(CredentialProvider.OPENAI) {
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
                    refreshStoredConfiguration(CredentialProvider.OPENAI)
                    if (openAiConfigurationVersion == version) {
                        showOpenAiCredentialError(error.message ?: "No se pudo completar el guardado de la credencial")
                    } else {
                        updateState(CredentialProvider.OPENAI) { it.copy(saveState = SaveState.Idle) }
                    }
                }
            }
        }
    }

    // Descarta resultados de conexion si la credencial o su borrador han cambiado durante la consulta.
    fun validateOpenAiCredential() {
        if (_providerStates.value[CredentialProvider.OPENAI]?.isConfigured != true) return
        if (_providerStates.value[CredentialProvider.OPENAI]?.isValidating == true ||
            _providerStates.value[CredentialProvider.OPENAI]?.saveState is SaveState.Saving
        ) return
        val version = openAiConfigurationVersion
        val credentialDraft = _openAiCredentialDraft.value
        updateState(CredentialProvider.OPENAI) {
            it.copy(isValidating = true, validationMessage = null, validationSucceeded = null)
        }
        viewModelScope.launch {
            val result = try {
                val credential = credentialDraft.trim().takeIf { it.isNotBlank() }
                    ?: withContext(ioDispatcher) { credentialStore.getCredential(CredentialProvider.OPENAI) }
                    ?: error("No se pudo leer la credencial guardada")
                if (openAiConfigurationVersion != version) return@launch
                validationService.validate(CredentialProvider.OPENAI, credential)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Result.failure(exception)
            }
            if (openAiConfigurationVersion != version) return@launch
            updateState(CredentialProvider.OPENAI) {
                it.copy(
                    isValidating = false,
                    validationMessage = if (result.isSuccess) "Credencial accesible" else
                        result.exceptionOrNull()?.message ?: "No se pudo validar la conexion",
                    validationSucceeded = result.isSuccess
                )
            }
        }
    }

    fun deleteOpenAiCredential() {
        if (_providerStates.value[CredentialProvider.OPENAI]?.saveState is SaveState.Saving) return
        invalidateOpenAiRequests()
        val version = openAiConfigurationVersion
        updateState(CredentialProvider.OPENAI) { it.copy(saveState = SaveState.Saving) }
        viewModelScope.launch {
            mutationMutex.withLock {
                try {
                    withContext(ioDispatcher) {
                        cancelActiveProviderJobs(CredentialProvider.OPENAI)
                        credentialStore.deleteCredential(CredentialProvider.OPENAI)
                    }
                    val draftUnchanged = openAiConfigurationVersion == version
                    invalidateOpenAiRequests()
                    updateState(CredentialProvider.OPENAI) { ProviderCredentialState() }
                    if (draftUnchanged) {
                        _openAiCredentialDraft.value = ""
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    refreshStoredConfiguration(CredentialProvider.OPENAI)
                    if (openAiConfigurationVersion == version) {
                        showOpenAiCredentialError(error.message ?: "No se pudo eliminar la credencial")
                    } else {
                        updateState(CredentialProvider.OPENAI) { it.copy(saveState = SaveState.Idle) }
                    }
                }
            }
        }
    }

    fun updateCustomApiDraft(draft: CustomApiDraft) {
        if (_customApiDraft.value == draft) return
        customDraftVersion++
        _customApiDraft.value = draft
        if (_customApiSaveState.value !is SaveState.Saving) _customApiSaveState.value = SaveState.Idle
    }

    // Guarda una instantanea sin sobrescribir ediciones posteriores ni publicar errores obsoletos.
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

    // Una respuesta solo puede actualizar la configuracion que la solicito.
    private fun invalidateOpenAiRequests() {
        openAiConfigurationVersion++
        updateState(CredentialProvider.OPENAI) {
            it.copy(
                isValidating = false, validationMessage = null, validationSucceeded = null,
                saveState = if (it.saveState is SaveState.Saving) it.saveState else SaveState.Idle
            )
        }
    }

    private fun showOpenAiCredentialError(message: String) {
        updateState(CredentialProvider.OPENAI) {
            it.copy(validationMessage = message, validationSucceeded = false, saveState = SaveState.Error(message))
        }
    }

    // Retira el token anterior antes de publicar otro destino para no exponerlo al nuevo servidor.
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

    private fun CustomApiConfiguration?.toDraft() = CustomApiDraft(
        displayName = this?.displayName.orEmpty(),
        endpointUrl = this?.endpointUrl.orEmpty(),
        modelId = this?.modelId.orEmpty()
    )

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
            CredentialProvider.OPENAI -> "openai"
            CredentialProvider.CUSTOM_API -> CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX
        }
        cancelJobs.execute(
            analysisQueueRepository.getActiveJobsForProviderPrefix(prefix).map { it.jobId },
            "La configuracion del proveedor ha cambiado"
        )
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
}

class ProfileViewModelFactory(
    private val credentialStore: ProviderCredentialStore,
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
                customApiConfigurationStore,
                analysisQueueRepository,
                workScheduler
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
