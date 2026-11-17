package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.BlockDao
import com.example.data.local.dao.CandidateDao
import com.example.data.local.dao.ProjectDao
import com.example.data.local.entity.BlockEntity
import com.example.data.local.entity.CandidateMomentEntity
import com.example.data.local.entity.ProjectEntity

@Database(
    entities = [
        ProjectEntity::class,
        BlockEntity::class,
        CandidateMomentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun blockDao(): BlockDao
    abstract fun candidateDao(): CandidateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cortaja_database.db"
                ).fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
