package com.example.besharp.ui

import android.content.Intent
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.besharp.BeSharpApplication
import com.example.besharp.ui.screens.detail.PhotoDetailScreen
import com.example.besharp.ui.screens.detail.DetailLoadState
import com.example.besharp.ui.screens.detail.DetailStatusScreen
import com.example.besharp.ui.screens.detail.asDetailLoadState
import com.example.besharp.ui.screens.gallery.GalleryScreen
import com.example.besharp.ui.screens.gallery.GalleryViewMode
import com.example.besharp.ui.screens.gallery.GalleryViewModel
import com.example.besharp.ui.screens.gallery.GalleryViewModelFactory
import com.example.besharp.ui.screens.gallery.GalleryUiState
import com.example.besharp.ui.screens.gallery.PhotoFilter
import com.example.besharp.ui.screens.legal.LegalScreen
import com.example.besharp.ui.screens.profile.SettingsHostScreen
import com.example.besharp.ui.screens.profile.SettingsPage
import com.example.besharp.ui.screens.profile.ProfileViewModel
import com.example.besharp.ui.screens.profile.ProfileViewModelFactory
import com.example.besharp.ui.screens.analysis.AnalysisPreparationScreen
import com.example.besharp.ui.screens.analysis.AnalysisPreparationViewModel
import com.example.besharp.ui.screens.analysis.AnalysisPreparationViewModelFactory
import com.example.besharp.ui.navigation.AppRoute
import com.example.besharp.ui.model.AssessmentState
import com.example.besharp.domain.model.GalleryPhoto
import com.example.besharp.ui.components.BeSharpBottomBar
import com.example.besharp.ui.components.BeSharpTopBar
import com.example.besharp.ui.components.BeSharpNavigationRail
import com.example.besharp.ui.components.AssessmentReplacementDialog
import com.example.besharp.ui.common.rememberPhotoAccessRequest

/**
 * Secciones principales de la aplicación.
 */
enum class BottomSection {
    GALLERY,
    ANALYSIS,
    SETTINGS,
    LEGAL
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun BeSharpApp(
    initialOpenAnalysis: Boolean = false,
    initialAssessmentId: Long? = null,
    onAnalysisOpened: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination
    val selectedSection = when {
        destination?.hasRoute<AppRoute.Analysis>() == true -> BottomSection.ANALYSIS
        destination?.hasRoute<AppRoute.Settings>() == true -> BottomSection.SETTINGS
        destination?.hasRoute<AppRoute.Legal>() == true -> BottomSection.LEGAL
        else -> BottomSection.GALLERY
    }
    var reopenConsentAfterLegal by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.MENU) }
    var selectingImagesForAnalysis by rememberSaveable { mutableStateOf(false) }
    var analysisSelectionUris by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var viewModeBeforeSelection by rememberSaveable { mutableStateOf(GalleryViewMode.CALENDAR) }
    var filterBeforeSelection by rememberSaveable { mutableStateOf(PhotoFilter.ALL) }
    var partialAccessNoticeConsumed by rememberSaveable { mutableStateOf(false) }
    var showPartialAccessNotice by rememberSaveable { mutableStateOf(false) }
    var partialAccessNoticeExpiresAt by rememberSaveable { mutableLongStateOf(0L) }
    var completedAssessmentNoticeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var previousActiveAssessments by remember { mutableStateOf<Set<Long>?>(null) }

    val context = LocalContext.current
    val appContext = context.applicationContext
    val useNavigationRail = calculateWindowSizeClass(context as Activity).widthSizeClass ==
        WindowWidthSizeClass.Expanded
    val container = remember(appContext) { (appContext as BeSharpApplication).container }

    fun navigateTo(section: BottomSection) {
        if (section == BottomSection.SETTINGS && selectedSection != BottomSection.SETTINGS) {
            settingsPage = SettingsPage.MENU
        }
        val route: AppRoute = when (section) {
            BottomSection.GALLERY -> AppRoute.Gallery
            BottomSection.ANALYSIS -> AppRoute.Analysis
            BottomSection.SETTINGS -> AppRoute.Settings
            BottomSection.LEGAL -> AppRoute.Legal
        }
        navController.navigate(route) {
            launchSingleTop = true
            if (section != BottomSection.LEGAL) {
                val restorePreviousState = section != BottomSection.GALLERY
                popUpTo(navController.graph.startDestinationId) {
                    saveState = restorePreviousState
                }
                restoreState = restorePreviousState
            }
        }
    }

    val galleryViewModel: GalleryViewModel = viewModel(
        factory = GalleryViewModelFactory(
            container.galleryRepository,
            container.analysisHistoryRepository
        )
    )

    val profileViewModel: ProfileViewModel = viewModel(
        factory = ProfileViewModelFactory(
            container.credentialStore,
            container.customApiConfigurationStore,
            container.analysisQueueRepository,
            container.analysisWorkScheduler
        )
    )
    val analysisPreparationViewModel: AnalysisPreparationViewModel = viewModel(
        factory = AnalysisPreparationViewModelFactory(
            container.credentialStore,
            container.customApiConfigurationStore,
            container.analysisQueueRepository,
            container.analysisHistoryRepository,
            container.remoteAnalysisConsentStore,
            container.providerResolver,
            container.analysisWorkScheduler,
            container.queueAnalysisBatch,
            container.cancelAnalysisJobs,
            container.reconcileAnalysisJobs
        )
    )

    /*
     * Estados observados desde los ViewModels.
     */
    val galleryUiState by galleryViewModel.uiState.collectAsStateWithLifecycle()
    val viewMode by galleryViewModel.viewMode.collectAsStateWithLifecycle()
    val currentYearMonth by galleryViewModel.currentYearMonth.collectAsStateWithLifecycle()
    val selectedDate by galleryViewModel.selectedDate.collectAsStateWithLifecycle()
    val photoFilter by galleryViewModel.photoFilter.collectAsStateWithLifecycle()
    val assessmentHistory by analysisPreparationViewModel.assessmentHistory.collectAsStateWithLifecycle()
    val pendingAnalysisCount = remember(assessmentHistory) {
        assessmentHistory.count { it.state == AssessmentState.PREPARING || it.state == AssessmentState.ACTIVE }
    }
    val pendingReplacement = remember(assessmentHistory) {
        assessmentHistory
            .filter { it.state == AssessmentState.COMPLETED }
            .groupBy { it.imageUri }
            .mapNotNull { (_, assessments) ->
                val ordered = assessments.sortedByDescending { it.completedAt ?: it.createdAt }
                if (ordered.size >= 2) ordered[1] to ordered[0] else null
            }
            .maxByOrNull { (_, candidate) -> candidate.completedAt ?: candidate.createdAt }
    }
    LaunchedEffect(assessmentHistory) {
        val active = assessmentHistory
            .filter { it.state == AssessmentState.PREPARING || it.state == AssessmentState.ACTIVE }
            .mapTo(mutableSetOf()) { it.assessmentId }
        previousActiveAssessments?.let { previous ->
            val completed = assessmentHistory
                .filter { it.assessmentId in previous && it.state == AssessmentState.COMPLETED }
                .maxByOrNull { it.completedAt ?: it.createdAt }
            if (completed != null) completedAssessmentNoticeId = completed.assessmentId
        }
        previousActiveAssessments = active
    }
    val analysisUiState by analysisPreparationViewModel.uiState.collectAsStateWithLifecycle()
    val remoteConsentAccepted by analysisPreparationViewModel.remoteConsentAccepted
        .collectAsStateWithLifecycle()

    val isSyncing by galleryViewModel.isSyncing.collectAsStateWithLifecycle()
    val syncProgress by galleryViewModel.syncProgress.collectAsStateWithLifecycle()

    val hasPartialGalleryAccess = when (val state = galleryUiState) {
        is GalleryUiState.Content -> state.hasPartialAccess
        is GalleryUiState.Empty -> state.hasPartialAccess
        else -> false
    }

    LaunchedEffect(hasPartialGalleryAccess, selectedSection) {
        when {
            selectedSection != BottomSection.GALLERY -> showPartialAccessNotice = false
            !hasPartialGalleryAccess -> showPartialAccessNotice = false
            hasPartialGalleryAccess && !partialAccessNoticeConsumed -> {
                partialAccessNoticeConsumed = true
                showPartialAccessNotice = true
                partialAccessNoticeExpiresAt = System.currentTimeMillis() + PARTIAL_NOTICE_DURATION_MS
            }
        }
    }


    val requestPhotoAccess = rememberPhotoAccessRequest(
        galleryVisible = selectedSection == BottomSection.GALLERY,
        onAccessChanged = galleryViewModel::updatePhotoAccess,
        onSynchronize = { force -> galleryViewModel.syncMediaStore(force) }
    )

    fun leaveAnalysisImageSelection(addSelected: Boolean) {
        if (addSelected) {
            analysisPreparationViewModel.addImages(context, analysisSelectionUris.map(Uri::parse))
        }
        selectingImagesForAnalysis = false
        analysisSelectionUris = emptyList()
        galleryViewModel.setFilter(filterBeforeSelection)
        if (viewMode != viewModeBeforeSelection) galleryViewModel.toggleViewMode()
        navigateTo(BottomSection.ANALYSIS)
    }

    // Manejo de la navegación hacia atrás del sistema
    BackHandler(
        enabled = selectingImagesForAnalysis
    ) {
        leaveAnalysisImageSelection(addSelected = false)
    }

    LaunchedEffect(initialOpenAnalysis, initialAssessmentId) {
        if (initialAssessmentId != null) {
            navController.navigate(AppRoute.AssessmentResult(initialAssessmentId)) {
                launchSingleTop = true
            }
            onAnalysisOpened()
        } else if (initialOpenAnalysis) {
            if (selectingImagesForAnalysis) {
                leaveAnalysisImageSelection(addSelected = false)
            } else {
                navigateTo(BottomSection.ANALYSIS)
            }
            onAnalysisOpened()
        }
    }

    Scaffold(
            topBar = {
                if (destination?.hasRoute<AppRoute.PhotoDetail>() != true &&
                    destination?.hasRoute<AppRoute.AssessmentResult>() != true &&
                    selectedSection != BottomSection.ANALYSIS &&
                    selectedSection != BottomSection.LEGAL &&
                    !selectingImagesForAnalysis &&
                    (selectedSection != BottomSection.SETTINGS || settingsPage == SettingsPage.MENU)
                ) {
                    BeSharpTopBar(
                        selectedSection = selectedSection,
                        photoFilter = photoFilter,
                        viewMode = viewMode,
                        onFilterSelected = { filter ->
                            galleryViewModel.setFilter(filter)
                        },
                        onToggleViewMode = {
                            galleryViewModel.toggleViewMode()
                        }
                    )
                }
            },
            bottomBar = {
                if (!useNavigationRail && !selectingImagesForAnalysis &&
                    selectedSection != BottomSection.LEGAL
                ) {
                    BeSharpBottomBar(
                        selectedSection = selectedSection,
                        onSectionSelected = ::navigateTo
                    )
                }
            }
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (useNavigationRail && !selectingImagesForAnalysis &&
                    selectedSection != BottomSection.LEGAL
                ) {
                    BeSharpNavigationRail(
                        selectedSection = selectedSection,
                        onSectionSelected = ::navigateTo,
                        modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = AppRoute.Gallery,
                    modifier = Modifier.weight(1f)
                ) {
                composable<AppRoute.Gallery> {
                        GalleryScreen(
                            innerPadding = innerPadding,
                            viewMode = viewMode,
                            uiState = galleryUiState,
                            currentYearMonth = currentYearMonth,
                            selectedDate = selectedDate,
                            onNextMonth = { galleryViewModel.nextMonth() },
                            onPrevMonth = { galleryViewModel.prevMonth() },
                            onNextWeek = { galleryViewModel.nextWeek() },
                            onPrevWeek = { galleryViewModel.prevWeek() },
                            onSelectDate = { date -> galleryViewModel.selectDate(date) },
                            onPhotoClick = { clickedPhoto ->
                                if (selectingImagesForAnalysis) {
                                    val uri = clickedPhoto.contentUri
                                    analysisSelectionUris = toggleAnalysisImageSelection(
                                        current = analysisSelectionUris,
                                        uri = uri,
                                        maximum = AnalysisPreparationViewModel.MAX_IMAGES -
                                            analysisUiState.images.size
                                    )
                                } else {
                                    navController.navigate(AppRoute.PhotoDetail(clickedPhoto.photoId))
                                }
                            },
                            onYearMonthSelected = { yearMonth -> galleryViewModel.setYearMonth(yearMonth) },
                            isSyncing = isSyncing,
                            syncProgress = syncProgress,
                             onRequestPermission = requestPhotoAccess,
                            onOpenSettings = {
                                context.startActivity(Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", appContext.packageName, null)
                                ))
                            },
                            onRetry = { galleryViewModel.syncMediaStore(force = true) },
                            showPartialAccessNotice = showPartialAccessNotice,
                            partialAccessNoticeDurationMillis = (
                                partialAccessNoticeExpiresAt - System.currentTimeMillis()
                            ).coerceIn(1L, PARTIAL_NOTICE_DURATION_MS).toInt(),
                            onPartialAccessNoticeExpired = { showPartialAccessNotice = false },
                            pendingAnalysisCount = pendingAnalysisCount,
                            completedAnalysisName = completedAssessmentNoticeId?.let { id ->
                                assessmentHistory.firstOrNull { it.assessmentId == id }
                                    ?.imageDisplayName
                            },
                            onOpenCompletedAnalysis = {
                                completedAssessmentNoticeId?.let { id ->
                                    completedAssessmentNoticeId = null
                                    navController.navigate(AppRoute.AssessmentResult(id))
                                }
                            },
                            onDismissCompletedAnalysis = {
                                completedAssessmentNoticeId = null
                            },
                            selectionMode = selectingImagesForAnalysis,
                            selectedPhotoUris = analysisSelectionUris.toSet(),
                            maxSelection = (
                                AnalysisPreparationViewModel.MAX_IMAGES - analysisUiState.images.size
                            ).coerceAtLeast(0),
                            onConfirmSelection = {
                                leaveAnalysisImageSelection(addSelected = true)
                            },
                            onCancelSelection = {
                                leaveAnalysisImageSelection(addSelected = false)
                            }
                        )
                }

                composable<AppRoute.PhotoDetail> { entry ->
                    val route = entry.toRoute<AppRoute.PhotoDetail>()
                    val state by remember(route.photoId) {
                        galleryViewModel.observePhoto(route.photoId).asDetailLoadState()
                    }.collectAsStateWithLifecycle(initialValue = DetailLoadState.Loading)
                    val photo = (state as? DetailLoadState.Ready)?.value
                    photo?.let { selected ->
                        PhotoDetailScreen(
                            innerPadding = innerPadding,
                            photo = selected,
                            assessments = assessmentHistory,
                            onBack = { navController.popBackStack() },
                            onAnalyze = {
                                analysisPreparationViewModel.addImages(
                                    context,
                                    listOf(Uri.parse(selected.contentUri))
                                )
                                navigateTo(BottomSection.ANALYSIS)
                            }
                        )
                    } ?: DetailStatusScreen(state, innerPadding) { navController.popBackStack() }
                }

                composable<AppRoute.AssessmentResult> { entry ->
                    val route = entry.toRoute<AppRoute.AssessmentResult>()
                    val state by remember(route.assessmentId) {
                        analysisPreparationViewModel.observeAssessment(route.assessmentId).asDetailLoadState()
                    }.collectAsStateWithLifecycle(initialValue = DetailLoadState.Loading)
                    val assessment = (state as? DetailLoadState.Ready)?.value
                    assessment?.let { result ->
                        PhotoDetailScreen(
                            innerPadding = innerPadding,
                            photo = GalleryPhoto(
                                photoId = 0,
                                contentUri = result.imageUri,
                                dateTaken = result.createdAt,
                                displayName = result.imageDisplayName,
                                mimeType = null
                            ),
                            assessments = listOf(result),
                            onBack = { navController.popBackStack() },
                            onAnalyze = {
                                analysisPreparationViewModel.addImages(
                                    context,
                                    listOf(Uri.parse(result.imageUri))
                                )
                                navigateTo(BottomSection.ANALYSIS)
                            }
                        )
                    } ?: DetailStatusScreen(state, innerPadding) { navController.popBackStack() }
                }

                composable<AppRoute.Analysis> {
                    AnalysisPreparationScreen(
                        innerPadding = innerPadding,
                        viewModel = analysisPreparationViewModel,
                        onCapturedPhoto = { uri, fileName ->
                            galleryViewModel.insertCapturedPhoto(uri, fileName)
                        },
                        onOpenAppGallery = {
                            if (analysisUiState.images.size < AnalysisPreparationViewModel.MAX_IMAGES) {
                                viewModeBeforeSelection = viewMode
                                filterBeforeSelection = photoFilter
                                galleryViewModel.setFilter(PhotoFilter.ALL)
                                if (viewMode != GalleryViewMode.SEQUENTIAL) {
                                    galleryViewModel.toggleViewMode()
                                }
                                analysisSelectionUris = emptyList()
                                selectingImagesForAnalysis = true
                                navigateTo(BottomSection.GALLERY)
                            }
                        },
                        onOpenPrivacy = {
                            reopenConsentAfterLegal = true
                            navigateTo(BottomSection.LEGAL)
                        },
                        onAnalysisSubmitted = { navigateTo(BottomSection.GALLERY) },
                        reopenConsent = reopenConsentAfterLegal,
                        onConsentReopened = { reopenConsentAfterLegal = false }
                    )
                }

                composable<AppRoute.Settings> {
                    SettingsHostScreen(
                        innerPadding = innerPadding,
                        viewModel = profileViewModel,
                        page = settingsPage,
                        onPageChanged = { settingsPage = it },
                        onOpenLegal = {
                            navigateTo(BottomSection.LEGAL)
                        }
                    )
                }

                composable<AppRoute.Legal> {
                    LegalScreen(
                        innerPadding = innerPadding,
                        remoteConsentAccepted = remoteConsentAccepted,
                        onRevokeRemoteConsent = analysisPreparationViewModel::revokeRemoteConsent,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            }
        }

    pendingReplacement?.let { (previous, candidate) ->
        AssessmentReplacementDialog(
            previous = previous,
            candidate = candidate,
            onKeepPrevious = {
                analysisPreparationViewModel.keepCompletedAssessment(
                    candidate.imageUri,
                    previous.assessmentId
                )
            },
            onKeepCandidate = {
                analysisPreparationViewModel.keepCompletedAssessment(
                    candidate.imageUri,
                    candidate.assessmentId
                )
            }
        )
    }
}

internal fun toggleAnalysisImageSelection(
    current: List<String>,
    uri: String,
    maximum: Int
): List<String> = when {
    uri in current -> current - uri
    current.size < maximum -> current + uri
    else -> current
}

private const val PARTIAL_NOTICE_DURATION_MS = 10_000L
