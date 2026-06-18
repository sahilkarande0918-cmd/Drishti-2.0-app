package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

@Composable
fun GoogleLoginScreen(
    webClientId: String,
    onLoginCompleted: (email: String, name: String, idToken: String) -> Unit,
    onSpeakMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    // Configure Google Sign-In options
    val gso = remember(webClientId) {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestProfile()
            .build()
    }
    val googleSignInClient = remember(gso) {
        GoogleSignIn.getClient(context, gso)
    }

    // Google Sign-In launcher to capture activity results
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val email = account?.email ?: ""
            val name = account?.displayName ?: ""
            val idToken = account?.idToken ?: ""

            if (email.isNotBlank() && idToken.isNotBlank()) {
                statusText = "Authenticating..."
                onLoginCompleted(email, name, idToken)
            } else {
                isLoading = false
                Toast.makeText(context, "Google Account is missing credentials.", Toast.LENGTH_LONG).show()
                onSpeakMessage("Sign-in failed. Missing secure profile credentials.")
            }
        } catch (e: ApiException) {
            isLoading = false
            val code = e.statusCode
            Toast.makeText(context, "Handshake error. Code: $code", Toast.LENGTH_LONG).show()
            onSpeakMessage("Google server authentication declined. Code $code.")
        }
    }

    LaunchedEffect(Unit) {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            onSpeakMessage(
                "Access key required. Drishti requests Google Sign In. " +
                "Double tap the center of the screen to activate account selection."
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Aesthetic Ambient background glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(
                                TertiaryAmber.copy(alpha = 0.12f),
                                Color.Transparent
                            )
                        )
                    )
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(28.dp))

            // Google Colorful Integration Banner Top Icon
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = PrimarySafetyYellow,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "SIGN IN WITH GOOGLE",
                fontSize = 12.sp,
                color = SecondaryTactileCyan,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Text(
                text = "Secure Verification",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Text(
                text = "Drishti integrates with your Google account to secure emergency details and sync voice-guided profile data.",
                fontSize = 14.sp,
                color = TextSecondaryDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 48.dp)
            )

            if (isLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, PrimarySafetyYellow),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = PrimarySafetyYellow)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = statusText.ifEmpty { "Authorizing with Google..." },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Giant High-Contrast Accessible Button for Blind Users
                Button(
                    onClick = {
                        isLoading = true
                        statusText = "Launching account selector..."
                        onSpeakMessage("Launching Google account chooser. Select your profile.")
                        googleSignInClient.signOut().addOnCompleteListener {
                            val intent = googleSignInClient.signInIntent
                            signInLauncher.launch(intent)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .testTag("google_authorize_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimarySafetyYellow,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, Color.White)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Sign in with Google",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
