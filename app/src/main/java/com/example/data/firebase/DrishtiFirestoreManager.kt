package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.database.UserEntity
import com.example.data.database.GuardianEntity
import com.example.data.database.SosAlertEntity
import com.example.data.database.UserSettingsEntity
import com.example.data.database.SavedPlaceEntity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object DrishtiFirestoreManager {
    private const val TAG = "DrishtiFirestore"
    
    private var firestoreInstance: FirebaseFirestore? = null

    fun initialize(context: Context) {
        try {
            // Check if Firebase is already initialized automatically via resource files
            val app = if (FirebaseApp.getApps(context).isEmpty()) {
                // Initialize programmatically using settings from BuildConfig or default to compiling fallbacks
                val options = FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID.ifBlank { "1:555555555555:android:a1b2c3d4e5f6g7" })
                    .setApiKey(BuildConfig.FIREBASE_API_KEY.ifBlank { "AIzaSyFakeKeyForCompilingDrishtiVocalMapClient" })
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID.ifBlank { "drishti-accessible" })
                    .build()
                FirebaseApp.initializeApp(context, options)
            } else {
                FirebaseApp.getInstance()
            }
            firestoreInstance = FirebaseFirestore.getInstance(app)
            Log.d(TAG, "Firebase Firestore initialized successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase: ${e.localizedMessage}")
        }
    }

    private fun getFirestore(): FirebaseFirestore? {
        return firestoreInstance
    }

    // Real-time listener for the User Profile
    fun observeUserProfile(docId: String): Flow<UserEntity?> = callbackFlow {
        val firestore = getFirestore()
        if (firestore == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val normalizedDocId = docId.trim().lowercase()
        val docRef = firestore.collection("users").document(normalizedDocId)
        val listenerReg = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Observe User failed: ${error.localizedMessage}")
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val name = snapshot.getString("name") ?: ""
                val email = (snapshot.getString("email") ?: "").trim().lowercase()
                val onboardingCompleted = snapshot.getBoolean("onboardingCompleted") ?: false
                trySend(UserEntity(id = 1, name = name, email = email, onboardingCompleted = onboardingCompleted))
            } else {
                trySend(null)
            }
        }
        awaitClose { listenerReg.remove() }
    }

    // Save user profile in real-time
    fun saveUserProfile(user: UserEntity) {
        val firestore = getFirestore() ?: return
        val normalizedEmail = user.email.trim().lowercase()
        val data = hashMapOf(
            "id" to user.id,
            "name" to user.name,
            "email" to normalizedEmail,
            "onboardingCompleted" to user.onboardingCompleted,
            "lastUpdated" to System.currentTimeMillis()
        )
        val docId = normalizedEmail.takeIf { it.isNotBlank() } ?: "1"
        firestore.collection("users").document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "User Profile saved to Firestore") }
            .addOnFailureListener { e -> Log.e(TAG, "User Profile save failed: ${e.localizedMessage}") }
    }

    // Real-time listener for the Guardian
    fun observeGuardianProfile(docId: String): Flow<GuardianEntity?> = callbackFlow {
        val firestore = getFirestore()
        if (firestore == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val normalizedDocId = docId.trim().lowercase()
        val docRef = firestore.collection("guardians").document(normalizedDocId)
        val listenerReg = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Observe Guardian failed: ${error.localizedMessage}")
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val name = snapshot.getString("name") ?: ""
                val email = (snapshot.getString("email") ?: "").trim().lowercase()
                val phone = snapshot.getString("phone") ?: ""
                trySend(GuardianEntity(id = 1, userId = 1, name = name, email = email, phone = phone))
            } else {
                trySend(null)
            }
        }
        awaitClose { listenerReg.remove() }
    }

    // Save guardian profile in real-time
    fun saveGuardianProfile(guardian: GuardianEntity, userEmail: String) {
        val firestore = getFirestore() ?: return
        val normalizedUserEmail = userEmail.trim().lowercase()
        val data = hashMapOf(
            "id" to guardian.id,
            "userId" to guardian.userId,
            "name" to guardian.name,
            "email" to guardian.email.trim().lowercase(),
            "phone" to guardian.phone,
            "lastUpdated" to System.currentTimeMillis()
        )
        val docId = normalizedUserEmail.takeIf { it.isNotBlank() } ?: "1"
        firestore.collection("guardians").document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Guardian Profile synced/saved in Firestore") }
            .addOnFailureListener { e -> Log.e(TAG, "Guardian Profile save failed: ${e.localizedMessage}") }
    }

    // Observe all SOS Alerts in real-time
    fun observeSosAlerts(): Flow<List<SosAlertEntity>> = callbackFlow {
        val firestore = getFirestore()
        if (firestore == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val collectionRef = firestore.collection("sos_alerts")
        val listenerReg = collectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Observe Sos Alerts failed: ${error.localizedMessage}")
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        val id = doc.getLong("id")?.toInt() ?: 0
                        val userId = doc.getLong("userId")?.toInt() ?: 1
                        val latitude = doc.getDouble("latitude") ?: 0.0
                        val longitude = doc.getDouble("longitude") ?: 0.0
                        val address = doc.getString("address") ?: ""
                        val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                        SosAlertEntity(id = id, userId = userId, latitude = latitude, longitude = longitude, address = address, createdAt = createdAt)
                    } catch (e: Exception) {
                        null
                    }
                }.sortedByDescending { it.createdAt }
                trySend(list)
            } else {
                trySend(emptyList())
            }
        }
        awaitClose { listenerReg.remove() }
    }

    // Add real-time SOS Alert
    fun logSosAlert(alert: SosAlertEntity) {
        val firestore = getFirestore() ?: return
        val docId = if (alert.id == 0) firestore.collection("sos_alerts").document().id else alert.id.toString()
        val data = hashMapOf(
            "id" to alert.id,
            "userId" to alert.userId,
            "latitude" to alert.latitude,
            "longitude" to alert.longitude,
            "address" to alert.address,
            "createdAt" to alert.createdAt
        )
        firestore.collection("sos_alerts").document(docId)
            .set(data)
            .addOnSuccessListener { Log.d(TAG, "SOS Alert synced to Firestore!") }
            .addOnFailureListener { e -> Log.e(TAG, "SOS Alert sync failed: ${e.localizedMessage}") }
    }

    // Clear all alerts in real-time
    fun clearSosHistory() {
        val firestore = getFirestore() ?: return
        firestore.collection("sos_alerts").get()
            .addOnSuccessListener { snapshot ->
                val batch = firestore.batch()
                snapshot.documents.forEach { doc ->
                    batch.delete(doc.reference)
                }
                batch.commit()
                    .addOnSuccessListener { Log.d(TAG, "All Firestore SOS Alerts deleted") }
            }
    }

    // Real-time listener for Settings
    fun observeUserSettings(docId: String): Flow<UserSettingsEntity?> = callbackFlow {
        val firestore = getFirestore()
        if (firestore == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val normalizedDocId = docId.trim().lowercase()
        val docRef = firestore.collection("user_settings").document(normalizedDocId)
        val listenerReg = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Observe Settings failed: ${error.localizedMessage}")
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val speechRate = snapshot.getDouble("speechRate")?.toFloat() ?: 1.0f
                val voiceEnabled = snapshot.getBoolean("voiceEnabled") ?: true
                val ruviewEnabled = snapshot.getBoolean("ruviewEnabled") ?: false
                val ruviewServerUrl = snapshot.getString("ruviewServerUrl") ?: "http://10.0.2.2:3000"
                val smtpEnabled = snapshot.getBoolean("smtpEnabled") ?: false
                val smtpHost = snapshot.getString("smtpHost") ?: "smtp.gmail.com"
                val smtpPort = snapshot.getLong("smtpPort")?.toInt() ?: 465
                val smtpEmail = snapshot.getString("smtpEmail") ?: ""
                val smtpPassword = snapshot.getString("smtpPassword") ?: ""
                trySend(UserSettingsEntity(
                    id = 1,
                    userId = 1,
                    speechRate = speechRate,
                    voiceEnabled = voiceEnabled,
                    ruviewEnabled = ruviewEnabled,
                    ruviewServerUrl = ruviewServerUrl,
                    smtpEnabled = smtpEnabled,
                    smtpHost = smtpHost,
                    smtpPort = smtpPort,
                    smtpEmail = smtpEmail,
                    smtpPassword = smtpPassword
                ))
            } else {
                trySend(null)
            }
        }
        awaitClose { listenerReg.remove() }
    }

    // Save settings in real-time
    fun saveUserSettings(settings: UserSettingsEntity, userEmail: String) {
        val firestore = getFirestore() ?: return
        val normalizedUserEmail = userEmail.trim().lowercase()
        val data = hashMapOf(
            "id" to settings.id,
            "userId" to settings.userId,
            "speechRate" to settings.speechRate,
            "voiceEnabled" to settings.voiceEnabled,
            "ruviewEnabled" to settings.ruviewEnabled,
            "ruviewServerUrl" to settings.ruviewServerUrl,
            "smtpEnabled" to settings.smtpEnabled,
            "smtpHost" to settings.smtpHost,
            "smtpPort" to settings.smtpPort,
            "smtpEmail" to settings.smtpEmail,
            "smtpPassword" to settings.smtpPassword,
            "lastUpdated" to System.currentTimeMillis()
        )
        val docId = normalizedUserEmail.takeIf { it.isNotBlank() } ?: "1"
        firestore.collection("user_settings").document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Settings saved to Firestore") }
            .addOnFailureListener { e -> Log.e(TAG, "Settings save failed: ${e.localizedMessage}") }
    }

    // Observe Saved Places in real-time
    fun observeSavedPlaces(userEmail: String): Flow<List<SavedPlaceEntity>> = callbackFlow {
        val firestore = getFirestore()
        if (firestore == null || userEmail.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val normalizedUserEmail = userEmail.trim().lowercase()
        val collectionRef = firestore.collection("users").document(normalizedUserEmail).collection("saved_places")
        val listenerReg = collectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Observe Saved Places failed: ${error.localizedMessage}")
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        val id = doc.getLong("id")?.toInt() ?: 0
                        val name = doc.getString("name") ?: ""
                        val latitude = doc.getDouble("latitude") ?: 0.0
                        val longitude = doc.getDouble("longitude") ?: 0.0
                        val address = doc.getString("address") ?: ""
                        val imagePath = doc.getString("imagePath") ?: ""
                        val description = doc.getString("description") ?: ""
                        val landmarks = doc.getString("landmarks") ?: ""
                        val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                        SavedPlaceEntity(
                            id = id,
                            name = name,
                            latitude = latitude,
                            longitude = longitude,
                            address = address,
                            imagePath = imagePath,
                            description = description,
                            landmarks = landmarks,
                            createdAt = createdAt
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedByDescending { it.createdAt }
                trySend(list)
            } else {
                trySend(emptyList())
            }
        }
        awaitClose { listenerReg.remove() }
    }

    // Save Saved Place to Firestore
    fun saveSavedPlace(place: SavedPlaceEntity, userEmail: String) {
        val firestore = getFirestore() ?: return
        if (userEmail.isBlank()) return
        val normalizedUserEmail = userEmail.trim().lowercase()
        val docId = place.id.toString()
        val data = hashMapOf(
            "id" to place.id,
            "name" to place.name,
            "latitude" to place.latitude,
            "longitude" to place.longitude,
            "address" to place.address,
            "imagePath" to place.imagePath,
            "description" to place.description,
            "landmarks" to place.landmarks,
            "createdAt" to place.createdAt,
            "lastUpdated" to System.currentTimeMillis()
        )
        firestore.collection("users").document(normalizedUserEmail).collection("saved_places").document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Saved Place synced to Firestore: ${place.name}") }
            .addOnFailureListener { e -> Log.e(TAG, "Saved Place sync failed: ${e.localizedMessage}") }
    }

    // Delete Saved Place from Firestore
    fun deleteSavedPlace(placeId: Int, userEmail: String) {
        val firestore = getFirestore() ?: return
        if (userEmail.isBlank()) return
        val normalizedUserEmail = userEmail.trim().lowercase()
        val docId = placeId.toString()
        firestore.collection("users").document(normalizedUserEmail).collection("saved_places").document(docId)
            .delete()
            .addOnSuccessListener { Log.d(TAG, "Saved Place deleted from Firestore: $placeId") }
            .addOnFailureListener { e -> Log.e(TAG, "Saved Place delete failed: ${e.localizedMessage}") }
    }
}

