package com.example.besharp.work

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisConcurrencyLimiterTest {
    @Test
    fun serializesMemoryIntensiveAnalyses() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        List(8) {
            async(Dispatchers.Default) {
                AnalysisConcurrencyLimiter.run {
                    val current = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, current) }
                    try {
                        delay(25)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
    }
}
