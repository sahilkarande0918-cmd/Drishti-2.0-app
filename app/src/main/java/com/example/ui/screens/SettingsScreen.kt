package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.GuardianEntity
import com.example.data.database.UserSettingsEntity
import com.example.ui.theme.*
import com.example.util.ContactHelper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsScreen(
    userSettings: UserSettingsEntity?,
    guardian: GuardianEntity?,
    groqApiKey: String,
    geminiApiKey: String,
    onUpdateSpeechRate: (Float) -> Unit,
    onToggleVoiceFeedback: (Boolean) -> Unit,
    onUpdateGuardian: (name: String, email: String, phone: String) -> Unit,
    onUpdateGroqApiKey: (String) -> Unit,
    onUpdateGeminiApiKey: (String) -> Unit,
    onUpdateRuviewSettings: (Boolean, String) -> Unit,
    onUpdateSmtpSettings: (Boolean, String, Int, String, String) -> Unit,
    onTestVoice: () -> Unit,
    currentGender: String = "",
    onUpdateGender: (String) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // Guardian local state
    var gName by remember { mutableStateOf("") }
    var gEmail by remember { mutableStateOf("") }
    var gPhone by remember { mutableStateOf("") }

    // SMTP local state
    var smtpEnabled by remember { mutableStateOf(userSettings?.smtpEnabled ?: false) }
    var smtpHost by remember { mutableStateOf(userSettings?.smtpHost ?: "smtp.gmail.com") }
    var smtpPort by remember { mutableStateOf((userSettings?.smtpPort ?: 465).toString()) }
    var smtpEmail by remember { mutableStateOf(userSettings?.smtpEmail ?: "") }
    var smtpPassword by remember { mutableStateOf(userSettings?.smtpPassword ?: "") }

    LaunchedEffect(userSettings) {
        if (userSettings != null) {
            smtpEnabled = userSettings.smtpEnabled
            smtpHost = userSettings.smtpHost
            smtpPort = userSettings.smtpPort.toString()
            smtpEmail = userSettings.smtpEmail
            smtpPassword = userSettings.smtpPassword
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        uri?.let {
            val details = ContactHelper.getContactDetails(context, it)
            if (details != null) {
                gName = details.name
                if (details.phone.isNotBlank()) gPhone = details.phone
                if (details.email.isNotBlank()) gEmail = details.email
                Toast.makeText(context, "Imported: ${details.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not read contact data.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            contactPickerLauncher.launch(null)
        } else {
            Toast.makeText(context, "Contacts permission required to import info.", Toast.LENGTH_LONG).show()
        }
    }

    val onImportContactClick = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            contactPickerLauncher.launch(null)
        } else {
            contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }


    // Sync state when loaded
    LaunchedEffect(guardian) {
        if (guardian != null) {
            gName = guardian.name
            gEmail = guardian.email
            gPhone = guardian.phone
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "SYSTEM CONTROLS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SecondaryTactileCyan,
                letterSpacing = 1.5.sp
            )

            Text(
                text = "Preferences Settings",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = PrimarySafetyYellow,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // Real-Time database card representing Firestore transition
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SecondaryTactileCyan.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = SecondaryTactileCyan
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Cloud Database Sync",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color.Green)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Real-Time Firebase Firestore is Active",
                            fontSize = 13.sp,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your profile, settings, guardian details, and SOS emergency alerts are seamlessly synced over Firebase Cloud Firestore for real-time guardian oversight.",
                        fontSize = 12.sp,
                        color = TextSecondaryDark,
                        lineHeight = 16.sp
                    )
                }
            }



            // Speech Synthesis Preferences Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SurfaceCardDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = PrimarySafetyYellow)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Speech Settings",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Speech Rate Slider
                    val currentRate = userSettings?.speechRate ?: 1.0f
                    Text(
                        text = "Vocal Velocity rate: ${"%.1f".format(currentRate)}x",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Slider(
                        value = currentRate,
                        onValueChange = { onUpdateSpeechRate(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimarySafetyYellow,
                            activeTrackColor = PrimarySafetyYellow,
                            inactiveTrackColor = SurfaceCardDark
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("speech_rate_slider")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Speech Toggle Switch
                    val voiceFeedbackEnabled = userSettings?.voiceEnabled ?: true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Voice Feedback Mode",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "Read aloud AI scene/nav instructions",
                                fontSize = 12.sp,
                                color = TextSecondaryDark
                            )
                        }

                        Switch(
                            checked = voiceFeedbackEnabled,
                            onCheckedChange = { onToggleVoiceFeedback(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimarySafetyYellow,
                                checkedTrackColor = PrimarySafetyYellow.copy(alpha = 0.4f),
                                uncheckedThumbColor = IdleGray,
                                uncheckedTrackColor = SurfaceCardDark
                            ),
                            modifier = Modifier.testTag("voice_feedback_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onTestVoice,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("test_speech_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = SecondaryTactileCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Speech Synthesis Engine", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    }
                }
            }


            // How Drishti Addresses You (manual gender) Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SurfaceCardDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Face, contentDescription = null, tint = SecondaryTactileCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "How Drishti Talks To You",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Choose how Drishti addresses you, or type 'female', 'male', or 'auto'. Female gets sister-style words (behen, didi, tai, अगं); male gets buddy-style (bro, bhava, अरे).",
                        fontSize = 12.sp,
                        color = TextSecondaryDark
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick-select buttons (easier than typing for accessibility)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val options = listOf("Female" to "female", "Male" to "male", "Auto" to "")
                        options.forEach { (label, value) ->
                            val selected = currentGender == value
                            Button(
                                onClick = { onUpdateGender(value) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) PrimarySafetyYellow else SurfaceCardDark
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Color.Black else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    var genderText by remember(currentGender) { mutableStateOf(currentGender) }
                    OutlinedTextField(
                        value = genderText,
                        onValueChange = { genderText = it },
                        label = { Text("Or type: female / male / auto") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = SecondaryTactileCyan
                        ),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { onUpdateGender(genderText) }) {
                                Icon(Icons.Default.Check, contentDescription = "Save gender", tint = SecondaryTactileCyan)
                            }
                        }
                    )
                }
            }


            // RuView Sensor API Preferences Card
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SurfaceCardDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = SecondaryTactileCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "RuView Person Detection",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val isRuviewEnabled = userSettings?.ruviewEnabled ?: false
                    var ruviewUrl by remember { mutableStateOf(userSettings?.ruviewServerUrl ?: "http://10.0.2.2:3000") }

                    LaunchedEffect(userSettings?.ruviewServerUrl) {
                        if (userSettings != null) {
                            ruviewUrl = userSettings.ruviewServerUrl
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Activate Room Scanning",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "Trigger occupancy check on 'detect people' command",
                                fontSize = 12.sp,
                                color = TextSecondaryDark
                            )
                        }

                        Switch(
                            checked = isRuviewEnabled,
                            onCheckedChange = { onUpdateRuviewSettings(it, ruviewUrl) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimarySafetyYellow,
                                checkedTrackColor = PrimarySafetyYellow.copy(alpha = 0.4f),
                                uncheckedThumbColor = IdleGray,
                                uncheckedTrackColor = SurfaceCardDark
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = ruviewUrl,
                        onValueChange = { ruviewUrl = it },
                        label = { Text("RuView Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = SecondaryTactileCyan
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { onUpdateRuviewSettings(isRuviewEnabled, ruviewUrl) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = SecondaryTactileCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Server Configuration", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Emergency SMTP Server Configuration Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_smtp_card"),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SurfaceCardDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Email, contentDescription = null, tint = SecondaryTactileCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Direct Email Sender (SMTP)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Send directly from your account",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "Uses your personal SMTP server to send SOS alerts instead of FormSubmit.",
                                fontSize = 12.sp,
                                color = TextSecondaryDark
                            )
                        }

                        Switch(
                            checked = smtpEnabled,
                            onCheckedChange = { 
                                smtpEnabled = it
                                onUpdateSmtpSettings(it, smtpHost, smtpPort.toIntOrNull() ?: 465, smtpEmail, smtpPassword)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimarySafetyYellow,
                                checkedTrackColor = PrimarySafetyYellow.copy(alpha = 0.4f),
                                uncheckedThumbColor = IdleGray,
                                uncheckedTrackColor = SurfaceCardDark
                            )
                        )
                    }

                    if (smtpEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = smtpHost,
                            onValueChange = { smtpHost = it },
                            label = { Text("SMTP Host Server") },
                            placeholder = { Text("e.g. smtp.gmail.com") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = SecondaryTactileCyan,
                                unfocusedBorderColor = SurfaceCardDark,
                                focusedLabelColor = SecondaryTactileCyan
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = smtpPort,
                            onValueChange = { smtpPort = it },
                            label = { Text("SMTP Port (SSL recommended: 465)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = SecondaryTactileCyan,
                                unfocusedBorderColor = SurfaceCardDark,
                                focusedLabelColor = SecondaryTactileCyan
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = smtpEmail,
                            onValueChange = { smtpEmail = it },
                            label = { Text("Your Email Address") },
                            placeholder = { Text("e.g. sahilkarande0918@gmail.com") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = SecondaryTactileCyan,
                                unfocusedBorderColor = SurfaceCardDark,
                                focusedLabelColor = SecondaryTactileCyan
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = smtpPassword,
                            onValueChange = { smtpPassword = it },
                            label = { Text("Your App Password / Password") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = SecondaryTactileCyan,
                                unfocusedBorderColor = SurfaceCardDark,
                                focusedLabelColor = SecondaryTactileCyan
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { 
                                val portInt = smtpPort.toIntOrNull() ?: 465
                                onUpdateSmtpSettings(smtpEnabled, smtpHost, portInt, smtpEmail, smtpPassword)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, tint = SecondaryTactileCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save SMTP Configuration", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Guardian Update Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SurfaceCardDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = SecondaryTactileCyan)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Guardian Credentials",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        IconButton(onClick = onImportContactClick) {
                            Icon(
                                imageVector = Icons.Default.Contacts,
                                contentDescription = "Import from Contacts List",
                                tint = PrimarySafetyYellow
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = gName,
                        onValueChange = { gName = it },
                        label = { Text("Contact Name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("settings_guardian_name"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = SecondaryTactileCyan
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = gEmail,
                        onValueChange = { gEmail = it },
                        label = { Text("Contact Email Address") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("settings_guardian_email"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = SecondaryTactileCyan
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = gPhone,
                        onValueChange = { gPhone = it },
                        label = { Text("Contact Phone Number") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_guardian_phone"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = SecondaryTactileCyan
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (gName.isNotBlank() && gEmail.isNotBlank() && gPhone.isNotBlank()) {
                                onUpdateGuardian(gName, gEmail, gPhone)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("update_guardian_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SecondaryTactileCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update Guardian Info", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}
