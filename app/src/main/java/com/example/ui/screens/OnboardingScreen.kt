package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun OnboardingScreen(
    onCompleteOnboarding: (userName: String, guardianName: String, guardianEmail: String, guardianPhone: String) -> Unit,
    onSpeakMessage: (String) -> Unit
) {
    var userName by remember { mutableStateOf("") }
    var guardianName by remember { mutableStateOf("") }
    var guardianEmail by remember { mutableStateOf("") }
    var guardianPhone by remember { mutableStateOf("") }

    var hasAttemptedSubmit by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        uri?.let {
            val details = ContactHelper.getContactDetails(context, it)
            if (details != null) {
                guardianName = details.name
                if (details.phone.isNotBlank()) guardianPhone = details.phone
                if (details.email.isNotBlank()) guardianEmail = details.email
                Toast.makeText(context, "Imported: ${details.name}", Toast.LENGTH_SHORT).show()
                onSpeakMessage("Imported contact ${details.name}")
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

    // Speak onboarding prompt on entering
    LaunchedEffect(Unit) {
        onSpeakMessage("Drishti setup. Please enter your name and emergency medical contact info. Use the keyboard at the center of the screen.")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Subtitle header
            Text(
                text = "SETUP PROFILE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SecondaryTactileCyan,
                letterSpacing = 2.sp
            )

            Text(
                text = "Welcome to Drishti",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = PrimarySafetyYellow,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Text(
                text = "Configure user credentials in order to enable automatic guardian notifications when SOS triggers.",
                fontSize = 14.sp,
                color = TextSecondaryDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Step Card 1: User Profile info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, PrimarySafetyYellow.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "1. Your Information",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimarySafetyYellow,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text("Your Full Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = PrimarySafetyYellow) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_username_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PrimarySafetyYellow,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = PrimarySafetyYellow,
                            unfocusedLabelColor = TextSecondaryDark
                        ),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step Card 2: Guardian Details
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SecondaryTactileCyan.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "2. Emergency Guardian contact",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = SecondaryTactileCyan
                        )
                        IconButton(onClick = onImportContactClick) {
                            Icon(
                                imageVector = Icons.Default.Contacts,
                                contentDescription = "Import from Contacts List",
                                tint = PrimarySafetyYellow
                            )
                        }
                    }

                    OutlinedTextField(
                        value = guardianName,
                        onValueChange = { guardianName = it },
                        label = { Text("Guardian Full Name") },
                        leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null, tint = SecondaryTactileCyan) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("onboarding_guardian_name_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = SecondaryTactileCyan,
                            unfocusedLabelColor = TextSecondaryDark
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = guardianEmail,
                        onValueChange = { guardianEmail = it },
                        label = { Text("Guardian Email Address") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = SecondaryTactileCyan) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("onboarding_guardian_email_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = SecondaryTactileCyan,
                            unfocusedLabelColor = TextSecondaryDark
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = guardianPhone,
                        onValueChange = { guardianPhone = it },
                        label = { Text("Guardian Phone Number") },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = SecondaryTactileCyan) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_guardian_phone_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedLabelColor = SecondaryTactileCyan,
                            unfocusedLabelColor = TextSecondaryDark
                        ),
                        singleLine = true
                    )
                }
            }

            if (hasAttemptedSubmit && (userName.isBlank() || guardianName.isBlank() || guardianEmail.isBlank() || guardianPhone.isBlank())) {
                Text(
                    text = "* Please fill in all credentials to enable emergency support.",
                    color = EmergencyCrimson,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Done Button
            Button(
                onClick = {
                    hasAttemptedSubmit = true
                    if (userName.isNotBlank() && guardianName.isNotBlank() && guardianEmail.isNotBlank() && guardianPhone.isNotBlank()) {
                        onCompleteOnboarding(userName, guardianName, guardianEmail, guardianPhone)
                    } else {
                        onSpeakMessage("Incomplete profile inputs. Please dictating Name, email, and phone contact.")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("save_onboarding_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimarySafetyYellow,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, Color.White)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Confirm",
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Complete Profile Sync",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
