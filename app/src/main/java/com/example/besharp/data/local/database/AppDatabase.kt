package com.example.besharp.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.besharp.data.local.dao.AnalysisQueueDao
import com.example.besharp.data.local.dao.PhotoDao
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.example.besharp.data.local.entity.AuthenticityResultEntity
import com.example.besharp.data.local.entity.PhotoEntity
import com.example.besharp.data.local.entity.EvidenceSignalEntity
import com.example.besharp.data.local.entity.ImageAssessmentEntity

/**
 * Base de datos Room para la aplicación. Contiene las entidades y DAOs necesarios para almacenar y recuperar datos locales.
 */
@Database(
    entities = [
        PhotoEntity::class,
        ImageAssessmentEntity::class,
        AnalysisJobEntity::class,
        AuthenticityResultEntity::class,
        EvidenceSignalEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun analysisQueueDao(): AnalysisQueueDao

    companion object {
        private const val DATABASE_NAME = "besharp_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).build().also { INSTANCE = it }
            }
        }

    }
}
