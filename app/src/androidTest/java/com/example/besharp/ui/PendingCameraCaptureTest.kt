package com.example.besharp.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.besharp.R
import com.example.besharp.data.analysis.AnalysisProviderResolver
import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.CustomApiConfigurationStore
import com.example.besharp.data.analysis.GeminiAnalysisProvider
import com.example.besharp.data.analysis.ProviderModelConfigurationStore
import com.example.besharp.data.credentials.CredentialProvider
import com.example.besharp.data.credentials.ProviderCredentialStore
import com.example.besharp.data.local.database.AppDatabase
import com.example.besharp.data.privacy.RemoteAnalysisConsentStore
import com.example.besharp.data.repository.AnalysisHistoryRepository
import com.example.besharp.data.repository.AnalysisQueueRepository
import com.example.besharp.data.local.createCapturedPhotoFile
import com.example.besharp.data.local.acknowledgeCapturedPhoto
import com.example.besharp.ui.screens.analysis.AnalysisPreparationScreen
import com.example.besharp.ui.screens.analysis.AnalysisPreparationViewModel
import com.example.besharp.work.AnalysisScheduler
import com.example.besharp.work.AnalysisWorkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class PendingCameraCaptureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anotherCaptureCannotStartUntilThePreviousResultIsProcessed() {
        withCaptureScreen { context, registry, restoration ->
            val camera = context.getString(R.string.analysis_camera)
            compose.onNodeWithText(camera).performClick()
            compose.waitUntil(10_000) { registry.launches == 1 }
            compose.onNodeWithText(camera).assertIsNotEnabled()
            assertEquals(1, registry.launches)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(camera).assertIsNotEnabled()
            assertEquals(1, registry.launches)
            compose.onNodeWithText(context.getString(R.string.analysis_camera_recover)).performScrollTo().performClick()
            compose.runOnIdle { registry.dispatchResult(registry.code, false) }
            waitForCameraEnabled(context)
        }
    }

    @Test
    fun explicitDiscardNeedsCameraClosedConfirmationAndQuarantinesLateCallback() {
        withCaptureScreen { context, registry, _ ->
            val camera = context.getString(R.string.analysis_camera)
            compose.onNodeWithText(camera).performClick()
            compose.waitUntil(10_000) { registry.launches == 1 }
            val oldCode = registry.code
            val oldPhoto = registry.files.single()
            val discard = context.getString(R.string.analysis_camera_discard)
            compose.onNodeWithText(discard).performScrollTo().performClick()
            compose.onNode(hasText(discard) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
            assertTrue(oldPhoto.exists())
            compose.onNodeWithText(context.getString(R.string.analysis_camera_closed)).performClick()
            compose.onNode(hasText(discard) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            waitForCameraEnabled(context)
            org.junit.Assert.assertFalse(oldPhoto.exists())
            compose.onNodeWithText(camera).performScrollTo().performClick()
            compose.waitUntil(10_000) { registry.launches == 2 }
            val newPhoto = registry.files.last()
            compose.runOnIdle { registry.dispatchResult(oldCode, false) }
            compose.onNodeWithText(camera).assertIsNotEnabled()
            assertTrue(newPhoto.exists())
            assertTrue(File(newPhoto.parentFile, "${newPhoto.name}.pending").exists())
            compose.runOnIdle { registry.dispatchResult(registry.code, false) }
            waitForCameraEnabled(context)
        }
    }

    @Test
    fun pendingCaptureOnDiskCanBeRecoveredAndFailedRegistrationRetriedWithoutPhotoPermission() {
        var attempts = 0
        lateinit var photo: File
        withCaptureScreen(
            beforeContent = { context ->
                createCapturedPhotoFile(File(context.filesDir, "photos"))
                    .apply { writeBytes(byteArrayOf(1, 2, 3)) }.also { photo = it }
            },
            onCapturedPhoto = { _, _ ->
                attempts++
                if (attempts == 1) error("Room no disponible")
                acknowledgeCapturedPhoto(photo)
            }
        ) { context, registry, restoration ->
            val camera = context.getString(R.string.analysis_camera)
            compose.onNodeWithText(camera).assertIsNotEnabled()
            val recover = context.getString(R.string.analysis_camera_recover)
            compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText(recover))
            assertEquals(0, registry.launches)
            compose.onNodeWithText(recover).performScrollTo().performClick()
            compose.onNode(hasText(recover) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.analysis_camera_closed)).performClick()
            compose.onNode(hasText(recover) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) { attempts == 1 }
            compose.onNodeWithText(context.getString(R.string.action_retry)).performScrollTo().assertIsEnabled()
            assertTrue(photo.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue(File(photo.parentFile, "${photo.name}.ready").exists())
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(context.getString(R.string.action_retry)).performScrollTo().performClick()
            waitForCameraEnabled(context)
            assertEquals(2, attempts)
            assertTrue(photo.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            org.junit.Assert.assertFalse(File(photo.parentFile, "${photo.name}.ready").exists())
        }
    }

    private fun waitForCameraEnabled(context: Context) {
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(context.getString(R.string.analysis_camera)))
        compose.waitUntil(10_000) {
            !compose.onNodeWithText(context.getString(R.string.analysis_camera))
                .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
    }

    private fun withCaptureScreen(
        beforeContent: (Context) -> File? = { null },
        onCapturedPhoto: suspend (String, String) -> Unit = { _, _ -> },
        exercise: (Context, CameraRegistry, StateRestorationTester) -> Unit
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val owner = ViewModelStore()
        val credentials = object : ProviderCredentialStore {
            override fun hasCredential(provider: CredentialProvider) = false
            override fun getCredential(provider: CredentialProvider): String? = null
            override fun saveCredential(provider: CredentialProvider, credential: String) = Unit
            override fun deleteCredential(provider: CredentialProvider) = Unit
        }
        val custom = object : CustomApiConfigurationStore {
            override fun getConfiguration(): CustomApiConfiguration? = null
            override fun saveConfiguration(configuration: CustomApiConfiguration) = Unit
            override fun deleteConfiguration() = Unit
        }
        val models = object : ProviderModelConfigurationStore {
            override fun getModel() = GeminiAnalysisProvider.DEFAULT_MODEL
            override fun saveModel(modelId: String) = Unit
        }
        val scheduler = object : AnalysisScheduler {
            override suspend fun enqueue(jobId: Long) = Unit
            override suspend fun cancel(jobId: Long) = Unit
            override suspend fun cancelAllRemote() = Unit
            override suspend fun workState(jobId: Long) = AnalysisWorkState.ABSENT
        }
        val registry = CameraRegistry(context)
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val model = AnalysisPreparationViewModel(
            credentials, custom, AnalysisQueueRepository(database.analysisQueueDao()),
            AnalysisHistoryRepository(database.analysisQueueDao()), AnalysisProviderResolver(credentials, custom, models),
            scheduler, RemoteAnalysisConsentStore(context)
        )
        owner.put("analysis", model)
        try {
            beforeContent(context)?.let { registry.files += it }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    MaterialTheme {
                        AnalysisPreparationScreen(
                            PaddingValues(0.dp), model, onCapturedPhoto, {}, {}, {}
                        )
                    }
                }
            }
            compose.waitUntil(10_000) { model.pendingCaptures.value != null }
            compose.waitForIdle()
            if (registry.files.isEmpty()) waitForCameraEnabled(context)
            exercise(context, registry, restoration)
        } finally {
            owner.clear()
            database.close()
            registry.files.forEach { file ->
                file.delete()
                listOf("pending", "ready", "cancelled").forEach { state ->
                    File(file.parentFile, "${file.name}.$state").delete()
                }
            }
        }
    }

    private class CameraRegistry(private val context: Context) : ActivityResultRegistry() {
        var launches = 0
        var code = 0
        val files = mutableListOf<File>()

        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?
        ) {
            code = requestCode
            files += File(context.filesDir, "photos/${(input as Uri).lastPathSegment}")
            launches++
        }
    }
}
