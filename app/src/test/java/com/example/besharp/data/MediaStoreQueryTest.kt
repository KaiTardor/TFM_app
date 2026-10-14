package com.example.besharp.data

import com.example.besharp.data.local.MediaStoreHelper
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreQueryTest {
    @Test
    fun nullCursorIsAFailureNotAnEmptyGallery() = runTest {
        var failed = false
        try {
            MediaStoreHelper.readImages(null)
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
    }
}
