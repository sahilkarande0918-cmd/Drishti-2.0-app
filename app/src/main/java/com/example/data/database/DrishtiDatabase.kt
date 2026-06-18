package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserEntity::class,
        GuardianEntity::class,
        SosAlertEntity::class,
        UserSettingsEntity::class,
        SavedPlaceEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class DrishtiDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun guardianDao(): GuardianDao
    abstract fun sosAlertDao(): SosAlertDao
    abstract fun userSettingsDao(): UserSettingsDao
    abstract fun savedPlaceDao(): SavedPlaceDao

    companion object {
        @Volatile
        private var INSTANCE: DrishtiDatabase? = null

        fun getDatabase(context: Context): DrishtiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DrishtiDatabase::class.java,
                    "drishti_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
