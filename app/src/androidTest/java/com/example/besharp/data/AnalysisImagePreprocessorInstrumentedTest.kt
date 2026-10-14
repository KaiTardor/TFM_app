package com.example.besharp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.besharp.data.analysis.AnalysisImagePreprocessor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisImagePreprocessorInstrumentedTest {
    @Test
    fun narrowImageCanBeEncodedWithoutZeroSizedBitmap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("narrow-image-", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(1, 3000, Bitmap.Config.ARGB_8888)
            try {
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val prepared = AnalysisImagePreprocessor.prepare(context, Uri.fromFile(source))
            val decoded = BitmapFactory.decodeByteArray(prepared.imageBytes, 0, prepared.imageBytes.size)!!
            try {
                assertEquals(1, decoded.width)
                assertEquals(1600, decoded.height)
            } finally {
                decoded.recycle()
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun preparesRealJpegForVisualProviders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AnalysisImagePreprocessor.clearCached(context, 1)
        val source = File.createTempFile("visual-input-", ".jpg", context.cacheDir)
        try {
            source.outputStream().use { output ->
                val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
                try {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                } finally {
                    bitmap.recycle()
                }
            }

            val prepared = AnalysisImagePreprocessor.prepareCached(
                context = context,
                assessmentId = 1,
                uri = Uri.fromFile(source)
            )

            assertEquals("image/jpeg", prepared.mimeType)
            assertTrue(prepared.imageBytes.isNotEmpty())
        } finally {
            AnalysisImagePreprocessor.clearCached(context, 1)
            source.delete()
        }
    }
}
