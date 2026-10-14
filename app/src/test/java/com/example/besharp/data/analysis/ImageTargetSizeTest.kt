package com.example.besharp.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageTargetSizeTest {
    @Test
    fun extremeAspectRatiosNeverProduceZeroDimensions() {
        assertEquals(1 to 1600, AnalysisImagePreprocessor.targetSize(1, 3000))
        assertEquals(1600 to 1, AnalysisImagePreprocessor.targetSize(3000, 1))
    }

    @Test
    fun smallImagesAreNotUpscaledAndLargeImagesKeepTheirRatio() {
        assertEquals(32 to 24, AnalysisImagePreprocessor.targetSize(32, 24))
        assertEquals(1600 to 800, AnalysisImagePreprocessor.targetSize(4000, 2000))
        assertThrows(IllegalArgumentException::class.java) { AnalysisImagePreprocessor.targetSize(0, 100) }
    }
}
