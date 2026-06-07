package com.clothcall.ui.screens

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.clothcall.data.db.CaregiverProfile
import com.clothcall.ui.navigation.Route
import com.clothcall.ui.viewmodels.HomeViewModel
import java.util.Locale

private val SELECTED_MODE_COLOR = Color(0xFF0D47A1)
private val UNSELECTED_MODE_COLOR = Color(0xFFE0E0E0)
private val UNSELECTED_MODE_TEXT_COLOR = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel
) {
    val profiles by viewModel.profiles.collectAsState()
    var outMode by remember { mutableStateOf(viewModel.isOutMode) }
    var profileDropdownExpanded by remember { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.isActive } ?: profiles.firstOrNull()

    // Keep DB in sync with what the bar shows. If no profile is flagged active in the DB
    // (e.g. fresh install, first profile not yet activated, or Sara was deleted), activate
    // whichever one the bar is currently displaying so ScanViewModel always finds the right person.
    LaunchedEffect(activeProfile) {
        if (activeProfile != null && !activeProfile.isActive) {
            viewModel.setActiveProfile(activeProfile.id)
        }
    }

    val context = LocalContext.current

    // Spoken once per app process launch — only when TalkBack (touch exploration)
    // is off, since TalkBack already announces focused elements itself.
    // hasSpokenWelcome is reset to false in ClothCallApplication.onCreate, so this
    // fires on first HomeScreen visit after a fresh launch and never again until
    // the app process is fully restarted — not on every return to HomeScreen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager?.isTouchExplorationEnabled != true && !viewModel.hasSpokenWelcome) {
            engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS && !viewModel.hasSpokenWelcome) {
                    viewModel.hasSpokenWelcome = true
                    engine?.setLanguage(Locale.US)
                    engine?.speak(
                        "Tap Check My Clothes to begin.",
                        TextToSpeech.QUEUE_FLUSH, Bundle(), "home_ready"
                    )
                }
            }
        }
        // The Activity merely pauses when the app is backgrounded — Compose stays
        // alive and onDispose below won't fire — so speech must be stopped explicitly
        // on ON_PAUSE, otherwise it keeps talking through the speaker after you leave.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                engine?.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine?.stop()
            engine?.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClothCall") },
                actions = {
                    IconButton(onClick = { navController.navigate(Route.WARDROBE) }) {
                        Icon(Icons.Filled.Checkroom, contentDescription = "Wardrobe")
                    }
                    IconButton(onClick = { navController.navigate(Route.CAREGIVER_SETUP) }) {
                        Icon(Icons.Filled.Person, contentDescription = "Caregiver profiles")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Trusted person selector
            ProfileDropdown(
                profiles = profiles,
                active = activeProfile,
                expanded = profileDropdownExpanded,
                onToggle = { profileDropdownExpanded = !profileDropdownExpanded },
                onSelect = { profile ->
                    viewModel.setActiveProfile(profile.id)
                    profileDropdownExpanded = false
                }
            )

            // Home / Out toggle
            ModeToggle(
                outMode = outMode,
                onSelect = { selected -> outMode = selected; viewModel.isOutMode = selected }
            )

            // Main button — fills all remaining space
            Button(
                onClick = { navController.navigate(Route.QUICK_SCAN) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 120.dp)
                    .clearAndSetSemantics {
                        contentDescription = "Check my clothes. Double tap to start."
                        role = Role.Button
                    },
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "Check My Clothes",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

        }
    }
}

@Composable
private fun ProfileDropdown(
    profiles: List<CaregiverProfile>,
    active: CaregiverProfile?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (CaregiverProfile) -> Unit
) {
    val description = if (active != null)
        "Trusted person: ${active.name}. Tap to change."
    else
        "No trusted person set. Tap to add one."

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                },
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            Text(
                text = if (active != null) "Trusted: ${active.name}" else "No profile selected",
                fontSize = 18.sp
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onToggle
        ) {
            if (profiles.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No profiles — add one") },
                    onClick = onToggle
                )
            }
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, fontSize = 18.sp) },
                    onClick = { onSelect(profile) }
                )
            }
        }
    }
}

@Composable
private fun ModeToggle(
    outMode: Boolean,
    onSelect: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val homeDescription = if (!outMode) "Home mode, selected" else "Home mode, tap to switch"
        val outDescription = if (outMode) "Out mode, selected" else "Out mode, tap to switch"

        Button(
            onClick = { onSelect(false) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clearAndSetSemantics {
                    contentDescription = homeDescription
                    role = Role.Button
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!outMode) SELECTED_MODE_COLOR else UNSELECTED_MODE_COLOR,
                contentColor = if (!outMode) Color.White else UNSELECTED_MODE_TEXT_COLOR
            )
        ) {
            Text("Home", fontSize = 18.sp)
        }
        Button(
            onClick = { onSelect(true) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clearAndSetSemantics {
                    contentDescription = outDescription
                    role = Role.Button
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (outMode) SELECTED_MODE_COLOR else UNSELECTED_MODE_COLOR,
                contentColor = if (outMode) Color.White else UNSELECTED_MODE_TEXT_COLOR
            )
        ) {
            Text("Out", fontSize = 18.sp)
        }
    }
}
