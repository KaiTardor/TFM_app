package com.example.besharp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.besharp.data.local.entity.PhotoEntity
import kotlinx.coroutines.flow.Flow

/**
 * Interfaz DAO para la entidad PhotoEntity, que proporciona métodos para interactuar con la base de datos.
 */
@Dao
interface PhotoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPhoto(photo: PhotoEntity): Long

    @Upsert
    suspend fun upsertPhotos(photos: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE photoId IN (:photoIds)")
    suspend fun deletePhotos(photoIds: List<Long>)

    @Query("UPDATE photos SET isVisible = 0 WHERE contentUri LIKE 'content://media/%'")
    suspend fun hideMediaStorePhotos()

    @Transaction
    suspend fun synchronize(
        photos: List<PhotoEntity>,
        deletedPhotoIds: List<Long>,
        partialAccess: Boolean
    ) {
        if (partialAccess) hideMediaStorePhotos()
        if (deletedPhotoIds.isNotEmpty()) deletePhotos(deletedPhotoIds)
        if (photos.isNotEmpty()) upsertPhotos(photos)
    }

    @Query("SELECT * FROM photos WHERE isVisible = 1 ORDER BY dateTaken DESC")
    fun getAllPhotosFlow(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE photoId = :photoId AND isVisible = 1 LIMIT 1")
    fun observePhoto(photoId: Long): Flow<PhotoEntity?>

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotosSync(): List<PhotoEntity>
}
