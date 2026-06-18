package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = 1")
    fun getUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = 1")
    suspend fun getUserOneShot(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("DELETE FROM users")
    suspend fun clearAll()
}

@Dao
interface GuardianDao {
    @Query("SELECT * FROM guardians WHERE id = 1")
    fun getGuardian(): Flow<GuardianEntity?>

    @Query("SELECT * FROM guardians WHERE id = 1")
    suspend fun getGuardianOneShot(): GuardianEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGuardian(guardian: GuardianEntity)
}

@Dao
interface SosAlertDao {
    @Query("SELECT * FROM sos_alerts ORDER BY createdAt DESC")
    fun getAllAlerts(): Flow<List<SosAlertEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: SosAlertEntity)

    @Query("DELETE FROM sos_alerts")
    suspend fun clearAlertHistory()
}

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1")
    fun getSettings(): Flow<UserSettingsEntity?>

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun getSettingsOneShot(): UserSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: UserSettingsEntity)
}

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY createdAt DESC")
    fun getAllSavedPlaces(): Flow<List<SavedPlaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlace(place: SavedPlaceEntity)

    @Delete
    suspend fun deletePlace(place: SavedPlaceEntity)

    @Query("SELECT * FROM saved_places WHERE name = :name LIMIT 1")
    suspend fun getPlaceByName(name: String): SavedPlaceEntity?

    @Query("DELETE FROM saved_places")
    suspend fun clearAllSavedPlaces()
}

