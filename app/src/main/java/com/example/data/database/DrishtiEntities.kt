package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: Int = 1, // Single active user
    val name: String,
    val email: String = "",
    val onboardingCompleted: Boolean = false
)

@Entity(tableName = "guardians")
data class GuardianEntity(
    @PrimaryKey val id: Int = 1, // Single emergency contact for MVP
    val userId: Int = 1,
    val name: String,
    val email: String,
    val phone: String
)

@Entity(tableName = "sos_alerts")
data class SosAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int = 1,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val userId: Int = 1,
    val speechRate: Float = 1.0f,
    val voiceEnabled: Boolean = true,
    val ruviewEnabled: Boolean = false,
    val ruviewServerUrl: String = "http://10.0.2.2:3000",
    val smtpEnabled: Boolean = false,
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 465,
    val smtpEmail: String = "",
    val smtpPassword: String = ""
)

@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val imagePath: String = "", // Local image cache path
    val description: String = "", // Spoken visual description of building/gate
    val landmarks: String = "", // Spoken visual description of surrounding landmarks
    val createdAt: Long = System.currentTimeMillis()
)

