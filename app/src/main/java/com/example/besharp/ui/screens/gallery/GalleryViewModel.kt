package com.example.besharp.ui.screens.gallery

// Mantiene filtros y calendario, y sincroniza la galeria segun los permisos actuales.

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.besharp.data.repository.AnalysisHistoryRepository
import com.example.besharp.data.repository.GalleryRepository
import com.example.besharp.domain.model.GalleryPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class GalleryViewMode {
    CALENDAR,
    SEQUENTIAL
}

enum class PhotoFilter {
    ALL,
    UNANALYZED,
    ANALYZED
}

sealed interface GalleryUiState {
    data object Loading : GalleryUiState
    data class Content(
        val groupedPhotos: Map<LocalDate, List<GalleryPhoto>>,
        val analyzedPhotoUris: Set<String>,
        val hasPartialAccess: Boolean
    ) : GalleryUiState
    data class Empty(val hasPartialAccess: Boolean) : GalleryUiState
    data class PermissionDenied(val canRequestAgain: Boolean) : GalleryUiState
    data class Error(val message: String) : GalleryUiState
}

internal sealed interface GalleryAccess {
    data object Unknown : GalleryAccess
    data class Granted(val partial: Boolean) : GalleryAccess
    data class Denied(val canRequestAgain: Boolean) : GalleryAccess
}

class GalleryViewModel(
    private val galleryRepository: GalleryRepository,
    analysisHistoryRepository: AnalysisHistoryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _photoFilter = MutableStateFlow(
        savedStateHandle.get<String>(KEY_PHOTO_FILTER)
            ?.let { runCatching { PhotoFilter.valueOf(it) }.getOrNull() }
            ?: PhotoFilter.ALL
    )
    val photoFilter: StateFlow<PhotoFilter> = _photoFilter.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow(0f)
    val syncProgress: StateFlow<Float> = _syncProgress.asStateFlow()

    private val _galleryAccess = MutableStateFlow<GalleryAccess>(GalleryAccess.Unknown)
    private val _syncError = MutableStateFlow<String?>(null)
    private val analyzedPhotoUris = analysisHistoryRepository.observeAnalyzedImageUris()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val groupedPhotos: StateFlow<Map<LocalDate, List<GalleryPhoto>>> = combine(
        galleryRepository.observePhotos(),
        _photoFilter,
        analyzedPhotoUris
    ) { photos, filter, analyzedUris ->
        photos.filterByAuthenticityState(filter, analyzedUris)
            .groupBy { it.dateTaken.toLocalDate() }
            .toSortedMap(compareByDescending { it })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap()
    )
    val uiState: StateFlow<GalleryUiState> = combine(
        groupedPhotos,
        analyzedPhotoUris,
        _galleryAccess,
        _isSyncing,
        _syncError
    ) { photos, analyzedUris, access, syncing, error ->
        resolveGalleryUiState(photos, analyzedUris, access, syncing, error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryUiState.Loading
    )

    private val _viewMode = MutableStateFlow(
        savedStateHandle.get<String>(KEY_VIEW_MODE)
            ?.let { runCatching { GalleryViewMode.valueOf(it) }.getOrNull() }
            ?: GalleryViewMode.CALENDAR
    )
    val viewMode: StateFlow<GalleryViewMode> = _viewMode.asStateFlow()

    private val _currentYearMonth = MutableStateFlow(
        savedStateHandle.get<String>(KEY_YEAR_MONTH)
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: YearMonth.now()
    )
    val currentYearMonth: StateFlow<YearMonth> = _currentYearMonth.asStateFlow()

    private val _selectedDate = MutableStateFlow(
        savedStateHandle.get<String>(KEY_SELECTED_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    )
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    private var syncJob: Job? = null
    private var syncRequired = true
    private var lastSyncAt = 0L

    fun setFilter(filter: PhotoFilter) {
        _photoFilter.value = filter
        savedStateHandle[KEY_PHOTO_FILTER] = filter.name
    }

    fun toggleViewMode() {
        _viewMode.value = when (_viewMode.value) {
            GalleryViewMode.CALENDAR -> GalleryViewMode.SEQUENTIAL
            GalleryViewMode.SEQUENTIAL -> GalleryViewMode.CALENDAR
        }
        savedStateHandle[KEY_VIEW_MODE] = _viewMode.value.name
    }

    fun nextMonth() {
        _currentYearMonth.value = _currentYearMonth.value.plusMonths(1)
        persistCalendarState()
    }

    fun prevMonth() {
        _currentYearMonth.value = _currentYearMonth.value.minusMonths(1)
        persistCalendarState()
    }

    fun nextWeek() {
        val next = (_selectedDate.value ?: LocalDate.now()).plusWeeks(1)
        _selectedDate.value = next
        _currentYearMonth.value = YearMonth.from(next)
        persistCalendarState()
    }

    fun prevWeek() {
        val previous = (_selectedDate.value ?: LocalDate.now()).minusWeeks(1)
        _selectedDate.value = previous
        _currentYearMonth.value = YearMonth.from(previous)
        persistCalendarState()
    }

    fun setYearMonth(yearMonth: YearMonth) {
        _currentYearMonth.value = yearMonth
        _selectedDate.value = null
        persistCalendarState()
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        persistCalendarState()
    }

    fun observePhoto(photoId: Long) = galleryRepository.observePhoto(photoId)

    // Invalida la sincronizacion previa cuando cambian los permisos para no reutilizar otro alcance.
    fun updatePhotoAccess(fullAccess: Boolean, partialAccess: Boolean, canRequestAgain: Boolean) {
        val access = when {
            fullAccess -> GalleryAccess.Granted(partial = false)
            partialAccess -> GalleryAccess.Granted(partial = true)
            else -> GalleryAccess.Denied(canRequestAgain)
        }
        if (access != _galleryAccess.value) {
            syncJob?.cancel()
            syncRequired = true
            _syncError.value = null
        }
        _galleryAccess.value = access
        if (!fullAccess && !partialAccess) {
            syncJob?.cancel()
            _isSyncing.value = false
        }
    }

    suspend fun insertCapturedPhoto(uri: String, fileName: String) {
        // La pantalla ofrece reintento si falla el registro, incluso sin acceso a MediaStore.
        withContext(Dispatchers.IO) {
            galleryRepository.insertCapturedPhoto(uri, fileName)
        }
    }

    // Espera la limpieza previa y conserva el alcance de permisos vigente al iniciar la sincronizacion.
    fun syncMediaStore(force: Boolean = false) {
        val access = _galleryAccess.value as? GalleryAccess.Granted ?: return
        if (syncJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && !syncRequired && now - lastSyncAt < SYNC_DEBOUNCE_MS) return

        val previousSync = syncJob
        syncJob = viewModelScope.launch {
            previousSync?.join()
            if (_galleryAccess.value != access) return@launch
            _syncError.value = null
            _isSyncing.value = true
            _syncProgress.value = 0.1f
            try {
                withContext(Dispatchers.IO) {
                    galleryRepository.synchronize(access.partial) { progress ->
                        _syncProgress.value = progress
                    }
                }
                _syncProgress.value = 1f
                delay(250)
                lastSyncAt = System.currentTimeMillis()
                syncRequired = false
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "No se pudo sincronizar MediaStore", exception)
                _syncError.value = "No se pudo cargar la galería. Comprueba el acceso e inténtalo de nuevo."
            } finally {
                _isSyncing.value = false
                _syncProgress.value = 0f
            }
        }
    }

    private fun List<GalleryPhoto>.filterByAuthenticityState(
        filter: PhotoFilter,
        analyzedPhotoUris: Set<String>
    ): List<GalleryPhoto> {
        if (filter == PhotoFilter.ALL) return this
        return filter { photo ->
            val hasResult = photo.contentUri in analyzedPhotoUris
            when (filter) {
                PhotoFilter.ALL -> true
                PhotoFilter.UNANALYZED -> !hasResult
                PhotoFilter.ANALYZED -> hasResult
            }
        }
    }

    private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    private fun persistCalendarState() {
        savedStateHandle[KEY_YEAR_MONTH] = _currentYearMonth.value.toString()
        savedStateHandle[KEY_SELECTED_DATE] = _selectedDate.value?.toString()
    }

    companion object {
        private const val TAG = "BESHARP_GALLERY"
        private const val SYNC_DEBOUNCE_MS = 5 * 60 * 1_000L
        private const val KEY_PHOTO_FILTER = "gallery_photo_filter"
        private const val KEY_VIEW_MODE = "gallery_view_mode"
        private const val KEY_YEAR_MONTH = "gallery_year_month"
        private const val KEY_SELECTED_DATE = "gallery_selected_date"
    }
}

internal fun resolveGalleryUiState(
    photos: Map<LocalDate, List<GalleryPhoto>>,
    analyzedPhotoUris: Set<String>,
    access: GalleryAccess,
    syncing: Boolean,
    error: String?
): GalleryUiState = when {
    access is GalleryAccess.Denied -> GalleryUiState.PermissionDenied(access.canRequestAgain)
    access is GalleryAccess.Unknown -> GalleryUiState.Loading
    error != null && photos.isEmpty() -> GalleryUiState.Error(error)
    syncing && photos.isEmpty() -> GalleryUiState.Loading
    access is GalleryAccess.Granted && photos.isEmpty() -> GalleryUiState.Empty(access.partial)
    access is GalleryAccess.Granted -> GalleryUiState.Content(photos, analyzedPhotoUris, access.partial)
    else -> GalleryUiState.Loading
}

class GalleryViewModelFactory(
    private val galleryRepository: GalleryRepository,
    private val analysisHistoryRepository: AnalysisHistoryRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(
                galleryRepository,
                analysisHistoryRepository,
                SavedStateHandle()
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: androidx.lifecycle.viewmodel.CreationExtras
    ): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(
                galleryRepository,
                analysisHistoryRepository,
                extras.createSavedStateHandle()
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
