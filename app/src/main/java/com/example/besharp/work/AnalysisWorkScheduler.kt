package com.example.besharp.work

// Programa, cancela y consulta trabajos persistentes de WorkManager.

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

enum class AnalysisWorkState { ABSENT, ACTIVE, FINISHED }

interface AnalysisScheduler {
    suspend fun enqueue(jobId: Long)
    suspend fun cancel(jobId: Long)
    suspend fun cancelAllRemote()
    suspend fun workState(jobId: Long): AnalysisWorkState
}

class AnalysisWorkScheduler(context: Context) : AnalysisScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override suspend fun enqueue(jobId: Long) = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(Data.Builder().putLong(AnalysisWorker.KEY_JOB_ID, jobId).build())
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(jobTag(jobId))
            .addTag(TAG_REMOTE_ANALYSIS)
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(jobId),
            ExistingWorkPolicy.KEEP,
            request
        ).result.get()
        Unit
    }

    override suspend fun cancel(jobId: Long) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(uniqueWorkName(jobId)).result.get()
        Unit
    }

    override suspend fun cancelAllRemote() = withContext(Dispatchers.IO) {
        workManager.cancelAllWorkByTag(TAG_REMOTE_ANALYSIS).result.get()
        Unit
    }

    override suspend fun workState(jobId: Long): AnalysisWorkState = withContext(Dispatchers.IO) {
        val entries = workManager.getWorkInfosForUniqueWork(uniqueWorkName(jobId)).get()
        when {
            entries.any { !it.state.isFinished } -> AnalysisWorkState.ACTIVE
            entries.isNotEmpty() -> AnalysisWorkState.FINISHED
            else -> AnalysisWorkState.ABSENT
        }
    }

    companion object {
        fun uniqueWorkName(jobId: Long) = "besharp-analysis-$jobId"
        fun jobTag(jobId: Long) = "analysis-job-$jobId"
        private const val TAG_REMOTE_ANALYSIS = "besharp-remote-analysis"
    }
}
