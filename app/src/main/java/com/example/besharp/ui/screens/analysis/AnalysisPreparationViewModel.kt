package com.example.besharp.ui.screens.analysis

// Mantiene la seleccion y coordina el consentimiento y los lotes de analisis.

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import com.example.besharp.data.analysis.AnalysisProviderResolver
import com.example.besharp.domain.model.AnalysisInputType
import com.example.besharp.data.analysis.CustomApiConfigurationStore
import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.CustomApiAnalysisProvider
import com.example.besharp.data.credentials.CredentialProvider
import com.example.besharp.data.credentials.ProviderCredentialStore
import com.example.besharp.data.local.listRecoverableCaptures
import com.example.besharp.data.privacy.RemoteAnalysisConsentStore
import com.example.besharp.data.privacy.RemoteConsentTarget
import com.example.besharp.data.privacy.RemoteConsentStore
import com.example.besharp.data.repository.AnalysisHistoryRepository
import com.example.besharp.domain.model.AnalysisHistoryEntry
import com.example.besharp.domain.model.AssessmentHistoryEntry
import com.example.besharp.data.repository.AnalysisQueueRepository
import com.example.besharp.data.repository.AnalysisJobRequest
import com.example.besharp.application.analysis.AnalysisBatchImage
import com.example.besharp.application.analysis.CancelAnalysisJobsUseCase
import com.example.besharp.application.analysis.QueueAnalysisBatchUseCase
import com.example.besharp.application.analysis.ReconcileAnalysisJobsUseCase
import com.example.besharp.ui.model.AnalysisJobState
import com.example.besharp.ui.model.AnalysisJobUi
import com.example.besharp.ui.model.AssessmentUi
import com.example.besharp.ui.model.AssessmentState
import com.example.besharp.work.AnalysisScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class SelectedAnalysisImage(val uri: String, val displayName: String)

data class PendingCameraCapture(val path: String, val result: Boolean?)

data class RemoteConsentPreview(val providerNames: String, val targets: Set<RemoteConsentTarget>) {
    val inputTypes: Set<AnalysisInputType> = targets.mapTo(mutableSetOf()) { it.inputType }
}

data class AnalysisModelOption(
    val id: String,
    val displayName: String,
    val modelId: String,
    val isConfigured: Boolean
)

data class AnalysisPreparationUiState(
    val images: List<SelectedAnalysisImage> = emptyList(),
    val models: List<AnalysisModelOption> = emptyList(),
    val selectedModelIds: Set<String> = emptySet(),
    val isRunning: Boolean = false,
    val isReconciling: Boolean = true,
    val submissionId: Long = 0,
    val batchError: String? = null
)

class AnalysisPreparationViewModel(
    private val credentialStore: ProviderCredentialStore,
    private val customApiConfigurationStore: CustomApiConfigurationStore,
    private val analysisQueueRepository: AnalysisQueueRepository,
    private val analysisHistoryRepository: AnalysisHistoryRepository,
    private val providerResolver: AnalysisProviderResolver,
    private val workScheduler: AnalysisScheduler,
    private val consentStore: RemoteConsentStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val queueAnalysisBatch: QueueAnalysisBatchUseCase = QueueAnalysisBatchUseCase(
        analysisQueueRepository,
        workScheduler
    ),
    private val cancelAnalysisJobs: CancelAnalysisJobsUseCase = CancelAnalysisJobsUseCase(
        analysisQueueRepository,
        workScheduler
    ),
    private val reconcileAnalysisJobs: ReconcileAnalysisJobsUseCase = ReconcileAnalysisJobsUseCase(
        analysisQueueRepository,
        workScheduler,
        consentStore
    ),
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AnalysisPreparationUiState(
            images = restoredImages(),
            selectedModelIds = savedStateHandle
                .get<ArrayList<String>>(KEY_SELECTED_MODELS)
                ?.toSet()
                .orEmpty()
        )
    )
    val uiState: StateFlow<AnalysisPreparationUiState> = _uiState.asStateFlow()
    val assessmentHistory: StateFlow<List<AssessmentUi>> = analysisHistoryRepository
        .observeAssessmentHistory(limit = 5_000)
        .map { entries -> entries.map(::toUiModel) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun observeAssessment(assessmentId: Long) = analysisHistoryRepository.observeAssessment(assessmentId)
        .map { it?.let(::toUiModel) }
    private val activeJobs = mutableSetOf<Long>()
    private val scheduling = AtomicBoolean(false)
    private val batchCancellationRequested = AtomicBoolean(false)
    private val cancellationInProgress = AtomicBoolean(false)
    private val orchestrationMutex = Mutex()
    private val restoredImagesValidated = AtomicBoolean(false)
    private var imageSelectionGeneration = 0L
    private val _pendingCaptures = MutableStateFlow<List<PendingCameraCapture>?>(null)
    val pendingCaptures = _pendingCaptures.asStateFlow()
    private val captureScanMutex = Mutex()
    private val _remoteConsentAccepted = MutableStateFlow(consentStore.getCurrentConsent() != null)
    val remoteConsentAccepted: StateFlow<Boolean> = _remoteConsentAccepted.asStateFlow()

    init {
        refreshModels()
        viewModelScope.launch {
            try {
                orchestrationMutex.withLock { reconcilePersistedJobs() }
            } finally {
                _uiState.value = _uiState.value.copy(isReconciling = false)
            }
        }
    }

    fun refreshModels() {
        val custom = customApiConfigurationStore.getConfiguration()
        val models = buildList {
            val id = AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID
            val provider = providerResolver.describe(id)
            add(AnalysisModelOption(
                id, provider.displayName, provider.modelId,
                credentialStore.hasCredential(CredentialProvider.OPENAI)
            ))
            if (custom != null) {
                add(AnalysisModelOption(
                    id = custom.providerId(),
                    displayName = custom.displayName,
                    modelId = custom.modelId,
                    isConfigured = true
                ))
            }
        }
        val availableIds = models.filter { it.isConfigured }.mapTo(mutableSetOf()) { it.id }
        _uiState.value = _uiState.value.copy(
            models = models,
            selectedModelIds = _uiState.value.selectedModelIds.intersect(availableIds)
        )
        persistPreparation()
        updateConsentState()
    }

    fun addImages(context: Context, uris: List<Uri>) {
        if (uris.isEmpty() || _uiState.value.isRunning) return
        addImages {
            uris.distinct().map { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                SelectedAnalysisImage(uri.toString(), displayName(context, uri))
            }
        }
    }

    internal fun addImages(resolveImages: suspend () -> List<SelectedAnalysisImage>) {
        if (_uiState.value.isRunning) return
        val generation = imageSelectionGeneration
        viewModelScope.launch {
            val newImages = try {
                withContext(ioDispatcher) { resolveImages() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (generation == imageSelectionGeneration) {
                    _uiState.value = _uiState.value.copy(batchError = "No se pudieron abrir las imagenes seleccionadas")
                }
                return@launch
            }
            // Un envio invalida las lecturas pendientes, incluso si ya termino.
            if (generation != imageSelectionGeneration || _uiState.value.isRunning) return@launch
            _uiState.value = _uiState.value.copy(
                images = (_uiState.value.images + newImages).distinctBy { it.uri }.take(MAX_IMAGES),
                batchError = null
            )
            persistPreparation()
        }
    }

    fun addCapturedImage(uri: Uri, displayName: String) {
        addCapturedImage(uri.toString(), displayName)
    }

    suspend fun refreshPendingCaptures(context: Context) = captureScanMutex.withLock {
        val captures = withContext(ioDispatcher) {
            listRecoverableCaptures(File(context.applicationContext.filesDir, "photos"))
                .map { PendingCameraCapture(it.file.absolutePath, it.result) }
        }
        _pendingCaptures.value = captures
    }

    internal fun addCapturedImage(uri: String, displayName: String) {
        if (_uiState.value.isRunning) return
        _uiState.value = _uiState.value.copy(
            images = (_uiState.value.images + SelectedAnalysisImage(uri, displayName))
                .distinctBy { it.uri }.take(MAX_IMAGES),
            batchError = null
        )
        persistPreparation()
    }

    fun removeImage(uri: String) {
        if (_uiState.value.isRunning) return
        _uiState.value = _uiState.value.copy(
            images = _uiState.value.images.filterNot { it.uri == uri },
            batchError = null
        )
        persistPreparation()
    }

    fun toggleModel(modelId: String) {
        if (_uiState.value.isRunning) return
        val option = _uiState.value.models.find { it.id == modelId } ?: return
        if (!option.isConfigured) return
        val selected = _uiState.value.selectedModelIds
        _uiState.value = _uiState.value.copy(
            selectedModelIds = if (modelId in selected) selected - modelId else selected + modelId,
            batchError = null
        )
        persistPreparation()
        updateConsentState()
    }

    fun analyze() {
        val snapshot = _uiState.value
        if (snapshot.images.isEmpty() || snapshot.selectedModelIds.isEmpty() || snapshot.isRunning ||
            snapshot.isReconciling ||
            cancellationInProgress.get()
        ) return
        if (requiresRemoteConsent() || !scheduling.compareAndSet(false, true)) return
        imageSelectionGeneration++
        batchCancellationRequested.set(false)
        _uiState.value = snapshot.copy(isRunning = true, batchError = null)

        viewModelScope.launch {
            orchestrationMutex.withLock {
                val persisted = mutableListOf<Long>()
                try {
                    val custom = customApiConfigurationStore.getConfiguration()
                    val providers = withContext(ioDispatcher) {
                        snapshot.selectedModelIds.map(providerResolver::create)
                    }
                    val requests = providers.map { provider ->
                        AnalysisJobRequest(
                            provider = provider.descriptor,
                            customConfiguration = custom?.takeIf {
                                it.providerId() == provider.descriptor.providerId
                            }
                        )
                    }
                    // Registramos todos los trabajos antes de arrancar el primer Worker.
                    queueAnalysisBatch.execute(
                        images = snapshot.images.map { AnalysisBatchImage(it.uri, it.displayName) },
                        requests = requests,
                        ensureActive = ::ensureBatchNotCancelled,
                        onPrepared = { jobs ->
                            jobs.forEach { queued ->
                                persisted += queued.jobId
                                activeJobs += queued.jobId
                            }
                        }
                    )
                    ensureBatchNotCancelled()
                    activeJobs.clear()
                    _uiState.value = AnalysisPreparationUiState(
                        models = _uiState.value.models,
                        isReconciling = false,
                        submissionId = snapshot.submissionId + 1
                    )
                    persistPreparation()
                } catch (error: BatchSchedulingCancelled) {
                    cleanupScheduledBatch(persisted, "Cancelado por el usuario")
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        cleanupScheduledBatch(persisted, "Programacion interrumpida")
                    }
                    throw error
                } catch (error: Exception) {
                    cleanupScheduledBatch(persisted, "Fallo al programar el lote")
                    val message = error.message ?: "No se pudo programar la cola"
                    _uiState.value = _uiState.value.copy(
                        batchError = message
                    )
                } finally {
                    scheduling.set(false)
                    _uiState.value = _uiState.value.copy(isRunning = false)
                }
            }
        }
    }

    fun consumeSubmission() {
        if (_uiState.value.submissionId != 0L) {
            _uiState.value = _uiState.value.copy(submissionId = 0L)
        }
    }

    fun keepCompletedAssessment(imageUri: String, assessmentId: Long) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    analysisQueueRepository.deleteOtherCompletedAssessments(imageUri, assessmentId)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(batchError = "No se pudo conservar el resultado elegido")
            }
        }
    }

    fun requiresRemoteConsent(preview: RemoteConsentPreview = selectedConsentPreview()): Boolean {
        val targets = preview.targets
        return targets.isNotEmpty() && !consentStore.hasConsentFor(targets)
    }

    fun selectedConsentPreview(): RemoteConsentPreview {
        val custom = customApiConfigurationStore.getConfiguration()
        val customId = custom?.providerId()
        val providers = _uiState.value.selectedModelIds.map { id ->
            if (custom != null && id == customId) CustomApiAnalysisProvider.describe(custom)
            else providerResolver.describe(id)
        }.filter { it.isRemote }
        val names = providers.joinToString { provider ->
            if (custom == null || provider.providerId != customId) provider.displayName
            else {
                val endpoint = custom.validate().getOrThrow()
                "${provider.displayName} (host: ${endpoint.host}, puerto: ${endpoint.port}, " +
                    "ruta: ${endpoint.encodedPath}, modelo: ${custom.modelId.trim()})"
            }
        }
        val targets = providers.mapTo(mutableSetOf()) { provider ->
            RemoteConsentTarget(
                providerId = provider.providerId,
                destinationFingerprint = if (provider.providerId == customId)
                    provider.providerId.removePrefix(CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX)
                else "${provider.providerId}:${provider.modelId}",
                capability = provider.capability,
                inputType = provider.inputType
            )
        }
        return RemoteConsentPreview(names, targets)
    }

    fun validateRestoredImages(context: Context) {
        validateRestoredImages { image ->
            runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(image.uri), "r")
                    ?.use { true }
                    ?: false
            }.getOrDefault(false)
        }
    }

    internal fun validateRestoredImages(isReadable: suspend (SelectedAnalysisImage) -> Boolean) {
        val images = _uiState.value.images
        if (images.isEmpty() || !restoredImagesValidated.compareAndSet(false, true)) return
        val generation = imageSelectionGeneration
        viewModelScope.launch {
            val invalidUris = withContext(ioDispatcher) {
                images.filterNot { isReadable(it) }.mapTo(mutableSetOf()) { it.uri }
            }
            if (generation != imageSelectionGeneration || _uiState.value.isRunning) return@launch
            // Solo retiramos URI invalidas; no restauramos una lista antigua.
            val current = _uiState.value
            val valid = current.images.filterNot { it.uri in invalidUris }
            if (valid != current.images) {
                _uiState.value = current.copy(images = valid)
                persistPreparation()
            }
        }
    }

    suspend fun acceptRemoteConsent(preview: RemoteConsentPreview): Boolean {
        return try {
            // Solo autorizamos los destinos que se han mostrado en el dialogo.
            check(preview.targets.isNotEmpty() && preview.targets == selectedConsentPreview().targets)
            withContext(ioDispatcher) { consentStore.accept(preview.targets) }
            _remoteConsentAccepted.value = true
            preview.targets == selectedConsentPreview().targets
        } catch (exception: CancellationException) {
            _remoteConsentAccepted.value = consentStore.getCurrentConsent() != null
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    suspend fun revokeRemoteConsent(): Boolean = withContext(NonCancellable) {
        if (!cancellationInProgress.compareAndSet(false, true)) return@withContext false
        // Si no se puede persistir la revocacion, no anunciamos que se ha aplicado.
        val revoked = withContext(ioDispatcher) { runCatching { consentStore.revoke() }.isSuccess }
        if (!revoked) {
            cancellationInProgress.set(false)
            return@withContext false
        }
        batchCancellationRequested.set(true)
        _remoteConsentAccepted.value = false
        _uiState.value = _uiState.value.copy(isRunning = false)
        try {
            var failure: Exception? = null
            try {
                analysisQueueRepository.cancelAllActive()
            } catch (exception: Exception) {
                failure = exception
            } finally {
                // Esta operacion no depende del ciclo de vida del ViewModel.
                try {
                    workScheduler.cancelAllRemote()
                } catch (exception: Exception) {
                    if (failure == null) failure = exception
                }
            }
            failure?.let { throw it }
            activeJobs.clear()
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(batchError = "No se pudo completar la cancelacion de los trabajos remotos")
        } finally {
            cancellationInProgress.set(false)
        }
        true
    }

    fun cancelAnalysis() {
        batchCancellationRequested.set(true)
        val startsCleanup = cancellationInProgress.compareAndSet(false, true)
        _uiState.value = _uiState.value.copy(
            isRunning = false
        )
        if (!startsCleanup) return
        viewModelScope.launch {
            try {
                val jobs = activeJobs.toList()
                cancelAnalysisJobs.execute(jobs, "Cancelado por el usuario")
                activeJobs.clear()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(batchError = "No se pudieron cancelar todos los trabajos")
            } finally {
                cancellationInProgress.set(false)
            }
        }
    }

    private suspend fun reconcilePersistedJobs() {
        repeat(RECONCILIATION_ATTEMPTS) { attempt ->
            val completed = try {
                reconcileAnalysisJobs.execute()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                false
            }
            if (completed) return
            if (attempt < RECONCILIATION_ATTEMPTS - 1) {
                delay(RECONCILIATION_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        _uiState.value = _uiState.value.copy(
            batchError = "No se pudo completar la recuperacion de los trabajos pendientes"
        )
    }

    private fun updateConsentState() {
        _remoteConsentAccepted.value = consentStore.getCurrentConsent() != null
    }

    private fun ensureBatchNotCancelled() {
        if (batchCancellationRequested.get()) throw BatchSchedulingCancelled()
    }

    private suspend fun cleanupScheduledBatch(
        jobs: List<Long>,
        reason: String
    ) {
        try {
            cancelAnalysisJobs.execute(jobs, reason)
            jobs.forEach(activeJobs::remove)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(batchError = "No se pudo completar la limpieza del lote")
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0) ?: "Imagen"
            }
        return uri.lastPathSegment ?: "Imagen"
    }

    private fun restoredImages(): List<SelectedAnalysisImage> {
        val uris = savedStateHandle.get<ArrayList<String>>(KEY_IMAGE_URIS).orEmpty()
        val names = savedStateHandle.get<ArrayList<String>>(KEY_IMAGE_NAMES).orEmpty()
        return uris.zip(names).map { (uri, name) -> SelectedAnalysisImage(uri, name) }
            .distinctBy(SelectedAnalysisImage::uri)
            .take(MAX_IMAGES)
    }

    private fun persistPreparation() {
        savedStateHandle[KEY_IMAGE_URIS] = ArrayList(_uiState.value.images.map { it.uri })
        savedStateHandle[KEY_IMAGE_NAMES] = ArrayList(_uiState.value.images.map { it.displayName })
        savedStateHandle[KEY_SELECTED_MODELS] = ArrayList(_uiState.value.selectedModelIds)
    }

    private fun toUiModel(entry: AnalysisHistoryEntry) = AnalysisJobUi(
        jobId = entry.jobId,
        providerName = entry.providerName,
        state = runCatching { AnalysisJobState.valueOf(entry.status) }
            .getOrDefault(AnalysisJobState.UNKNOWN),
        errorMessage = entry.errorMessage,
        result = entry.result
    )

    private fun toUiModel(entry: AssessmentHistoryEntry) = AssessmentUi(
        assessmentId = entry.assessmentId,
        imageUri = entry.imageUri,
        imageDisplayName = entry.imageDisplayName,
        state = runCatching { AssessmentState.valueOf(entry.status) }
            .getOrDefault(AssessmentState.UNKNOWN),
        createdAt = entry.createdAt,
        completedAt = entry.completedAt,
        finalResolution = entry.finalResolution,
        summary = entry.summary,
        reason = entry.reason,
        policyVersion = entry.policyVersion,
        conflictingSignalIds = entry.conflictingSignalIds,
        jobs = entry.jobs.map(::toUiModel)
    )

    companion object {
        const val MAX_IMAGES = 10
        private const val RECONCILIATION_ATTEMPTS = 3
        private const val RECONCILIATION_RETRY_DELAY_MS = 500L
        private const val KEY_IMAGE_URIS = "analysis_image_uris"
        private const val KEY_IMAGE_NAMES = "analysis_image_names"
        private const val KEY_SELECTED_MODELS = "analysis_selected_models"
    }

    private class BatchSchedulingCancelled : Exception()
}

class AnalysisPreparationViewModelFactory(
    private val credentialStore: ProviderCredentialStore,
    private val customApiConfigurationStore: CustomApiConfigurationStore,
    private val analysisQueueRepository: AnalysisQueueRepository,
    private val analysisHistoryRepository: AnalysisHistoryRepository,
    private val consentStore: RemoteAnalysisConsentStore,
    private val providerResolver: AnalysisProviderResolver,
    private val workScheduler: AnalysisScheduler,
    private val queueAnalysisBatch: QueueAnalysisBatchUseCase,
    private val cancelAnalysisJobs: CancelAnalysisJobsUseCase,
    private val reconcileAnalysisJobs: ReconcileAnalysisJobsUseCase
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return create(modelClass, SavedStateHandle())
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: androidx.lifecycle.viewmodel.CreationExtras
    ): T = create(modelClass, extras.createSavedStateHandle())

    @Suppress("UNCHECKED_CAST")
    private fun <T : ViewModel> create(modelClass: Class<T>, savedStateHandle: SavedStateHandle): T {
        if (modelClass.isAssignableFrom(AnalysisPreparationViewModel::class.java)) {
            return AnalysisPreparationViewModel(
                credentialStore,
                customApiConfigurationStore,
                analysisQueueRepository,
                analysisHistoryRepository,
                providerResolver,
                workScheduler,
                consentStore,
                queueAnalysisBatch = queueAnalysisBatch,
                cancelAnalysisJobs = cancelAnalysisJobs,
                reconcileAnalysisJobs = reconcileAnalysisJobs,
                savedStateHandle = savedStateHandle
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
