package com.example.besharp

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.example.besharp.data.analysis.AnalysisProviderResolver
import com.example.besharp.data.analysis.AnalysisTemporaryFiles
import com.example.besharp.data.analysis.AnalysisImagePreprocessor
import com.example.besharp.data.analysis.SharedPreferencesCustomApiConfigurationStore
import com.example.besharp.data.credentials.AndroidKeystoreCredentialStore
import com.example.besharp.data.local.database.AppDatabase
import com.example.besharp.data.privacy.RemoteAnalysisConsentStore
import com.example.besharp.data.repository.AnalysisQueueRepository
import com.example.besharp.data.repository.AnalysisHistoryRepository
import com.example.besharp.data.repository.GalleryRepository
import com.example.besharp.work.AnalysisWorkScheduler
import com.example.besharp.notifications.NotificationHelper
import com.example.besharp.application.analysis.CancelAnalysisJobsUseCase
import com.example.besharp.application.analysis.QueueAnalysisBatchUseCase
import com.example.besharp.application.analysis.ReconcileAnalysisJobsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.util.Log

class BeSharpApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build()
            )
        }
        NotificationHelper.initializeProcessLifecycle()
        container = AppContainer(this)
        applicationScope.launch {
            try {
                AnalysisTemporaryFiles.ensureCleaned(this@BeSharpApplication)
                container.galleryRepository.recoverCaptures()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("BeSharpCapture", "No se pudieron recuperar las capturas", exception)
            }
        }
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.getDatabase(appContext) }
    val credentialStore by lazy { AndroidKeystoreCredentialStore(appContext) }
    val customApiConfigurationStore by lazy {
        SharedPreferencesCustomApiConfigurationStore(appContext)
    }
    val remoteAnalysisConsentStore by lazy { RemoteAnalysisConsentStore(appContext) }
    val analysisQueueRepository by lazy {
        AnalysisQueueRepository(database.analysisQueueDao(), onAssessmentCompleted = { id ->
            try {
                AnalysisImagePreprocessor.clearCached(appContext, id)
            } catch (exception: Exception) {
                Log.e("BeSharpImage", "No se pudo limpiar la copia de la evaluacion $id", exception)
            }
        })
    }
    val analysisHistoryRepository by lazy { AnalysisHistoryRepository(database.analysisQueueDao()) }
    val galleryRepository by lazy { GalleryRepository(appContext, database.photoDao()) }
    val providerResolver by lazy {
        AnalysisProviderResolver(
            credentialStore,
            customApiConfigurationStore
        )
    }
    val analysisWorkScheduler by lazy { AnalysisWorkScheduler(appContext) }
    val queueAnalysisBatch by lazy {
        QueueAnalysisBatchUseCase(analysisQueueRepository, analysisWorkScheduler)
    }
    val cancelAnalysisJobs by lazy {
        CancelAnalysisJobsUseCase(analysisQueueRepository, analysisWorkScheduler)
    }
    val reconcileAnalysisJobs by lazy {
        ReconcileAnalysisJobsUseCase(
            analysisQueueRepository,
            analysisWorkScheduler,
            remoteAnalysisConsentStore
        )
    }
}
