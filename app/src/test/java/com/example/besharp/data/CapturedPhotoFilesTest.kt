package com.example.besharp.data

import com.example.besharp.data.local.createCapturedPhotoFile
import com.example.besharp.data.local.markCaptureReady
import com.example.besharp.data.local.recoverCapturedPhotos
import com.example.besharp.data.local.cancelCapturedPhoto
import com.example.besharp.data.local.acknowledgeCapturedPhoto
import com.example.besharp.data.local.listRecoverableCaptures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapturedPhotoFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun consecutiveCapturesUseDifferentReservedFiles() {
        val directory = temporary.newFolder("photos")
        val first = createCapturedPhotoFile(directory)
        val second = createCapturedPhotoFile(directory)

        assertNotEquals(first.name, second.name)
        assertTrue(first.isFile)
        assertTrue(second.isFile)
        first.delete()
        assertTrue(second.isFile)
    }

    @Test
    fun creatingAnotherCaptureDoesNotOverwriteAnExistingPhoto() {
        val directory = temporary.newFolder("photos")
        val existing = createCapturedPhotoFile(directory)
        val contents = byteArrayOf(1, 2, 3)
        existing.writeBytes(contents)
        createCapturedPhotoFile(directory)

        assertTrue(existing.readBytes().contentEquals(contents))
    }

    @Test
    fun completedCaptureIsRecoveredOnceAfterProcessRestart() = runTest {
        val directory = temporary.newFolder("photos")
        val photo = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        markCaptureReady(photo)
        val registered = mutableListOf<File>()
        recoverCapturedPhotos(directory) { registered += it }
        recoverCapturedPhotos(directory) { registered += it }

        assertTrue(photo.isFile)
        org.junit.Assert.assertEquals(listOf(photo), registered)
        org.junit.Assert.assertFalse(File(directory, "${photo.name}.ready").exists())
    }

    @Test
    fun pendingAndUntrackedPhotosAreNeverDeletedOrImported() = runTest {
        val directory = temporary.newFolder("photos")
        val pending = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1)) }
        val untracked = File(directory, "BESHARP_old.jpg").apply { writeBytes(byteArrayOf(2)) }
        recoverCapturedPhotos(directory) { error("No debe importar una captura sin confirmar") }

        assertTrue(pending.isFile)
        assertTrue(untracked.isFile)
        assertTrue(File(directory, "${pending.name}.pending").isFile)
    }

    @Test
    fun failedRegistrationKeepsThePhotoAndRecoveryMarker() = runTest {
        val directory = temporary.newFolder("photos")
        val photo = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1)) }
        markCaptureReady(photo)
        try {
            recoverCapturedPhotos(directory) { error("database failed") }
            org.junit.Assert.fail("La recuperacion debe propagar el fallo")
        } catch (_: IllegalStateException) {
            assertTrue(photo.isFile)
            assertTrue(File(directory, "${photo.name}.ready").isFile)
        }
    }

    @Test
    fun explicitlyCancelledCaptureIsRemovedAndDoesNotAffectAnotherPhoto() = runTest {
        val directory = temporary.newFolder("photos")
        val cancelled = createCapturedPhotoFile(directory)
        val pending = createCapturedPhotoFile(directory)
        // Simula el cierre del proceso despues de guardar la cancelacion.
        File(directory, "${cancelled.name}.cancelled").createNewFile()
        recoverCapturedPhotos(directory) { error("No debe registrar capturas canceladas") }

        org.junit.Assert.assertFalse(cancelled.exists())
        assertTrue(pending.isFile)
        cancelCapturedPhoto(pending)
        org.junit.Assert.assertFalse(pending.exists())
    }

    @Test
    fun completedCaptureCannotBeDeletedByALateCancellation() {
        val directory = temporary.newFolder("photos")
        val photo = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1)) }
        markCaptureReady(photo)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { cancelCapturedPhoto(photo) }
        acknowledgeCapturedPhoto(photo)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { cancelCapturedPhoto(photo) }
        assertTrue(photo.isFile)
    }

    @Test
    fun oneFailedCaptureDoesNotPreventRecoveringOtherPhotos() = runTest {
        val directory = temporary.newFolder("photos")
        val failed = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1)) }
        val successful = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(2)) }
        markCaptureReady(failed)
        markCaptureReady(successful)
        val registered = mutableListOf<File>()
        try {
            recoverCapturedPhotos(directory) { file ->
                if (file == failed) error("database failed")
                registered += file
            }
            org.junit.Assert.fail("Debe informar del fallo parcial")
        } catch (_: IllegalStateException) {
            org.junit.Assert.assertEquals(listOf(successful), registered)
            assertTrue(File(directory, "${failed.name}.ready").isFile)
            org.junit.Assert.assertFalse(File(directory, "${successful.name}.ready").exists())
        }
    }

    @Test
    fun coroutineCancellationStopsRecoveryWithoutRemovingMarkers() = runTest {
        val directory = temporary.newFolder("photos")
        val photo = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1)) }
        markCaptureReady(photo)
        try {
            recoverCapturedPhotos(directory) { throw CancellationException() }
            org.junit.Assert.fail("Debe propagar la cancelacion")
        } catch (_: CancellationException) {
            assertTrue(photo.isFile)
            assertTrue(File(directory, "${photo.name}.ready").isFile)
        }
    }

    @Test
    fun pendingCaptureRequiresAnExplicitDecisionEvenWhenItContainsAPhoto() = runTest {
        val directory = temporary.newFolder("photos")
        val photo = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        photo.setLastModified(1L)
        org.junit.Assert.assertNull(listRecoverableCaptures(directory).single().result)
        recoverCapturedPhotos(directory) { error("La antiguedad no confirma abandono") }
        markCaptureReady(photo) // Decision explicita de conservar la foto.
        var registered = false
        recoverCapturedPhotos(directory) { registered = true }
        assertTrue(registered)
        assertTrue(photo.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun emptyPendingCaptureCannotBeRecoveredButCanBeExplicitlyDiscarded() {
        val photo = createCapturedPhotoFile(temporary.newFolder("photos"))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { markCaptureReady(photo) }
        org.junit.Assert.assertNull(listRecoverableCaptures(photo.parentFile!!).single().result)
        assertTrue(photo.isFile)
        cancelCapturedPhoto(photo)
        org.junit.Assert.assertFalse(photo.exists())
    }

    @Test
    fun failedPendingMarkerRemovalKeepsReadyAndPreventsDiscard() = runTest {
        val directory = temporary.newFolder("photos")
        val photo = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1)) }
        val pending = File(directory, "${photo.name}.pending")
        assertTrue(pending.delete())
        assertTrue(pending.mkdir())
        File(pending, "blocker").writeText("x")
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { markCaptureReady(photo) }
        org.junit.Assert.assertEquals(true, listRecoverableCaptures(directory).single().result)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { cancelCapturedPhoto(photo) }
        try {
            recoverCapturedPhotos(directory) { }
            org.junit.Assert.fail("Debe propagar el fallo al borrar pending")
        } catch (_: IllegalStateException) {
            assertTrue(File(directory, "${photo.name}.ready").isFile)
            assertTrue(photo.isFile)
        }
        File(pending, "blocker").delete()
        recoverCapturedPhotos(directory) { }
        assertTrue(listRecoverableCaptures(directory).isEmpty())
        assertTrue(photo.isFile)
    }

    @Test
    fun failedReadyMarkerRemovalPreservesRegisteredPhotoAndRetryState() = runTest {
        val directory = temporary.newFolder("photos")
        val photo = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1)) }
        markCaptureReady(photo)
        val ready = File(directory, "${photo.name}.ready")
        assertTrue(ready.delete())
        assertTrue(ready.mkdir())
        File(ready, "blocker").writeText("x")
        var registrations = 0
        try {
            recoverCapturedPhotos(directory) { registrations++ }
            org.junit.Assert.fail("Debe propagar el fallo al borrar ready")
        } catch (_: IllegalStateException) {
            org.junit.Assert.assertEquals(1, registrations)
            assertTrue(photo.isFile)
            assertTrue(ready.exists())
        }
        File(ready, "blocker").delete()
        recoverCapturedPhotos(directory) { registrations++ }
        org.junit.Assert.assertEquals(2, registrations)
        assertTrue(photo.isFile)
        assertTrue(listRecoverableCaptures(directory).isEmpty())
    }

    @Test
    fun failedCancelledMarkerCleanupDoesNotBlockOtherRegistrations() = runTest {
        val directory = temporary.newFolder("photos")
        val discarded = createCapturedPhotoFile(directory)
        val cancelled = File(directory, "${discarded.name}.cancelled").apply { mkdir() }
        File(cancelled, "blocker").writeText("x")
        val kept = createCapturedPhotoFile(directory).apply { writeBytes(byteArrayOf(1)) }
        markCaptureReady(kept)
        val registered = mutableListOf<File>()
        try {
            recoverCapturedPhotos(directory) { registered += it }
            org.junit.Assert.fail("Debe propagar el fallo al borrar cancelled")
        } catch (_: IllegalStateException) {
            org.junit.Assert.assertEquals(listOf(kept), registered)
            assertTrue(kept.isFile)
            assertTrue(cancelled.exists())
            org.junit.Assert.assertEquals(false, listRecoverableCaptures(directory).single().result)
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { markCaptureReady(discarded) }
    }

    @Test
    fun unreadableCaptureDirectoryIsNotTreatedAsAnEmptyList() = runTest {
        val notDirectory = temporary.newFile("photos")
        try {
            recoverCapturedPhotos(notDirectory) { }
            org.junit.Assert.fail("Debe informar de que no se pudieron listar las capturas")
        } catch (_: IllegalStateException) {
            assertTrue(notDirectory.isFile)
        }
    }
}
