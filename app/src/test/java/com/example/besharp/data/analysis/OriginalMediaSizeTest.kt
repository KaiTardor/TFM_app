package com.example.besharp.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlinx.coroutines.CancellationException
import com.example.besharp.work.AnalysisExecutionPolicy

class OriginalMediaSizeTest {
    @Test
    fun declaredOrStreamedSizeAboveLimitHasSpecificCategory() {
        val error = assertThrows(AnalysisProviderException::class.java) {
            OriginalMediaPreparer.validateSize(101, 100, "Proveedor")
        }
        assertEquals(AnalysisErrorCategory.FILE_TOO_LARGE, error.category)
        assertEquals("Proveedor", error.providerName)
    }

    @Test
    fun exactLimitAndUnknownDeclaredSizeAreAccepted() {
        OriginalMediaPreparer.validateSize(100, 100, "Proveedor")
        OriginalMediaPreparer.validateSize(-1, 100, "Proveedor")
    }

    @Test
    fun sourceAndStorageFailuresAreLocalAndNotRetriedAsProviderFailures() {
        for (category in listOf(AnalysisErrorCategory.SOURCE_UNAVAILABLE, AnalysisErrorCategory.LOCAL_STORAGE)) {
            for (failure in listOf(IOException("local failure"), SecurityException("denied"))) {
                val error = assertThrows(AnalysisProviderException::class.java) {
                    OriginalMediaPreparer.localAccess(category) { throw failure }
                }
                assertEquals(category, error.category)
                assertEquals(false, AnalysisExecutionPolicy.shouldRetryRequest(OpenAiProvenanceProvider.PROVIDER_ID, error))
            }
        }
    }

    @Test
    fun cancellationAndTypedSizeFailuresAreNotReclassified() {
        val cancellation = CancellationException()
        val actual = assertThrows(CancellationException::class.java) {
            OriginalMediaPreparer.localAccess(AnalysisErrorCategory.LOCAL_STORAGE) { throw cancellation }
        }
        assertEquals(cancellation, actual)
        val size = AnalysisProviderException("too large", category = AnalysisErrorCategory.FILE_TOO_LARGE)
        val actualSize = assertThrows(AnalysisProviderException::class.java) {
            OriginalMediaPreparer.localAccess(AnalysisErrorCategory.SOURCE_UNAVAILABLE) { throw size }
        }
        assertEquals(size, actualSize)
    }
}
