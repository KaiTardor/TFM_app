package com.example.besharp.data.repository

// Sincroniza las fotos accesibles con Room y recupera las capturas privadas.

import android.content.Context
import com.example.besharp.data.local.MediaStoreHelper
import com.example.besharp.data.local.dao.PhotoDao
import com.example.besharp.data.local.entity.PhotoEntity
import com.example.besharp.domain.model.GalleryPhoto
import com.example.besharp.data.local.recoverCapturedPhotos
import com.example.besharp.data.local.acknowledgeCapturedPhoto
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GalleryRepository(
    private val context: Context,
    private val photoDao: PhotoDao
) {
    private val captureRegistrationMutex = Mutex()
    fun observePhotos(): Flow<List<GalleryPhoto>> = photoDao.getAllPhotosFlow().map { photos ->
        photos.map(PhotoEntity::toDomain)
    }
    fun observePhoto(photoId: Long): Flow<GalleryPhoto?> = photoDao.observePhoto(photoId).map {
        it?.toDomain()
    }

    // Con acceso parcial oculta fotos no autorizadas, sin tratarlas como eliminadas del dispositivo.
    suspend fun synchronize(partialAccess: Boolean, onProgress: (Float) -> Unit = {}) {
        recoverCaptures()
        val localImages = MediaStoreHelper.getAllImages(context)
        val storedPhotos = photoDao.getAllPhotosSync()
        onProgress(0.4f)

        val storedByUri = storedPhotos.associateBy(PhotoEntity::contentUri)
        val localUris = localImages.mapTo(hashSetOf()) { it.uriString }
        val now = System.currentTimeMillis()
        val synchronized = localImages.map { image ->
            val stored = storedByUri[image.uriString]
            PhotoEntity(
                photoId = stored?.photoId ?: 0,
                contentUri = image.uriString,
                dateTaken = image.dateTaken,
                displayName = image.displayName,
                mimeType = image.mimeType,
                isVisible = true,
                createdAt = stored?.createdAt ?: now,
                updatedAt = if (stored == null ||
                    stored.dateTaken != image.dateTaken ||
                    stored.displayName != image.displayName ||
                    stored.mimeType != image.mimeType
                ) now else stored.updatedAt
            )
        }
        val deletedIds = if (partialAccess) {
            emptyList()
        } else {
            storedPhotos
                .filter { it.contentUri.startsWith("content://media/") && it.contentUri !in localUris }
                .map(PhotoEntity::photoId)
        }
        photoDao.synchronize(synchronized, deletedIds, partialAccess)
    }

    // Registra la foto antes de retirar sus marcadores y evita solaparse con la recuperacion automatica.
    suspend fun insertCapturedPhoto(uri: String, fileName: String) = captureRegistrationMutex.withLock {
        val file = File(context.filesDir, "photos/$fileName")
        check(!File(file.parentFile, "${file.name}.cancelled").exists()) {
            "La captura ya esta descartada"
        }
        insertCapturedPhotoRecord(uri, file)
        acknowledgeCapturedPhoto(file)
    }

    private suspend fun insertCapturedPhotoRecord(uri: String, file: File) {
        check(file.isFile && file.length() > 0) { "La captura esta vacia o no existe" }
        val now = System.currentTimeMillis()
        photoDao.insertPhoto(
            PhotoEntity(
                contentUri = uri,
                dateTaken = file.lastModified().takeIf { it > 0 } ?: now,
                displayName = file.name,
                mimeType = "image/jpeg",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun recoverCaptures() = captureRegistrationMutex.withLock {
        recoverCapturedPhotos(File(context.filesDir, "photos")) { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            insertCapturedPhotoRecord(uri.toString(), file)
        }
    }
}

private fun PhotoEntity.toDomain() = GalleryPhoto(
    photoId = photoId,
    contentUri = contentUri,
    dateTaken = dateTaken,
    displayName = displayName,
    mimeType = mimeType
)
