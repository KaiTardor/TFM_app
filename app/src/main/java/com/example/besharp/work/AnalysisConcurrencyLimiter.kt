package com.example.besharp.work

// Limita a uno los analisis intensivos simultaneos dentro del proceso.

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal object AnalysisConcurrencyLimiter {
    private const val MAX_CONCURRENT_ANALYSES = 1
    private val semaphore = Semaphore(MAX_CONCURRENT_ANALYSES)

    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit { block() }
}
