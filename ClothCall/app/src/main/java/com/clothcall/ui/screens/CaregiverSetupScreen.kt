package com.clothcall.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.clothcall.R
import com.clothcall.data.db.CaregiverProfile
import com.clothcall.ui.viewmodels.CaregiverViewModel

private enum class FadeRating { STILL_FINE, BORDERLINE, RETIRE }

private data class FadeLevel(
    val percent: Int,
    val label: String,
    val saturation: Float,
    val brightness: Float
)

private val FADE_LEVELS = listOf(
    FadeLevel(0, "Like New", 1.00f, 1.00f),
    FadeLevel(5, "Lightly Faded", 0.85f, 1.05f),
    FadeLevel(10, "Noticeably Faded", 0.65f, 1.12f),
    FadeLevel(20, "Heavily Faded", 0.40f, 1.20f),
    FadeLevel(30, "Very Worn", 0.15f, 1.30f)
)

private enum class CaregiverSetupStep { LIST, CREATE }

private fun initialRatingsForThreshold(threshold: Int): Map<Int, FadeRating> {
    return FADE_LEVELS.associate { level ->
        level.percent to when {
            threshold >= 30 -> FadeRating.STILL_FINE
            level.percent < threshold -> FadeRating.STILL_FINE
            level.percent == threshold -> FadeRating.BORDERLINE
            else -> FadeRating.RETIRE
        }
    }
}

@Composable
fun CaregiverSetupScreen(navController: NavController, viewModel: CaregiverViewModel) {
    val profiles by viewModel.profiles.collectAsState()
    var step by remember { mutableStateOf(CaregiverSetupStep.LIST) }
    var editingProfile by remember { mutableStateOf<CaregiverProfile?>(null) }

    when (step) {
        CaregiverSetupStep.LIST -> ProfileListScreen(
            profiles = profiles,
            onAdd = { step = CaregiverSetupStep.CREATE },
            onDelete = { viewModel.deleteProfile(it) },
            onEdit = { profile ->
                editingProfile = profile
                step = CaregiverSetupStep.CREATE
            },
            onBack = { navController.popBackStack() }
        )

        CaregiverSetupStep.CREATE -> CreateProfileScreen(
            editingProfile = editingProfile,
            onSave = { profile, name, threshold ->
                if (profile == null) {
                    viewModel.saveProfile(name, threshold)
                } else {
                    viewModel.updateProfile(profile.copy(name = name, fadeThreshold = threshold))
                }
                editingProfile = null
                step = CaregiverSetupStep.LIST
            },
            onBack = {
                editingProfile = null
                step = CaregiverSetupStep.LIST
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileListScreen(
    profiles: List<CaregiverProfile>,
    onAdd: () -> Unit,
    onDelete: (CaregiverProfile) -> Unit,
    onEdit: (CaregiverProfile) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trusted people") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = { TextButton(onClick = onAdd) { Text("Add new") } }
            )
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No trusted people added yet.\nTap 'Add new' to create a profile.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Fade tolerance: ${profile.fadeThreshold}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                IconButton(onClick = { onEdit(profile) }) {
                                    Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onDelete(profile) }) {
                                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateProfileScreen(
    editingProfile: CaregiverProfile?,
    onSave: (CaregiverProfile?, String, Int) -> Unit,
    onBack: () -> Unit
) {
    var name by remember(editingProfile?.id) { mutableStateOf(editingProfile?.name ?: "") }
    val ratings = remember(editingProfile?.id) {
        mutableStateMapOf<Int, FadeRating>().apply {
            putAll(initialRatingsForThreshold(editingProfile?.fadeThreshold ?: 30))
        }
    }
    val canSave = name.isNotBlank() && FADE_LEVELS.all { ratings.containsKey(it.percent) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }

        Text(
            if (editingProfile == null) "Add a trusted person" else "Edit trusted person",
            style = MaterialTheme.typography.headlineMedium
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (e.g. Wife, Colleague)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        HorizontalDivider()

        Text(
            "Rate the same fabric photo at five fade levels.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 12.dp)) {
            items(FADE_LEVELS) { level ->
                Card(
                    modifier = Modifier.width(240.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.fabric_base),
                            contentDescription = level.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp),
                            colorFilter = fadeFilter(level.saturation, level.brightness)
                        )

                        Text(
                            level.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        RatingButtons(
                            selected = ratings[level.percent],
                            onSelect = { ratings[level.percent] = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (!canSave) {
            Text(
                text = when {
                    name.isBlank() -> "Enter a name to continue."
                    else -> "Rate all 5 levels before saving."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(
            onClick = { onSave(editingProfile, name.trim(), computeOverallThreshold(ratings)) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Save profile")
        }
    }
}

@Composable
private fun RatingButtons(
    selected: FadeRating?,
    onSelect: (FadeRating) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        RatingButton(
            text = "Still fine",
            selected = selected == FadeRating.STILL_FINE,
            onClick = { onSelect(FadeRating.STILL_FINE) },
            modifier = Modifier.fillMaxWidth()
        )

        RatingButton(
            text = "Borderline",
            selected = selected == FadeRating.BORDERLINE,
            onClick = { onSelect(FadeRating.BORDERLINE) },
            modifier = Modifier.fillMaxWidth()
        )

        RatingButton(
            text = "Retire",
            selected = selected == FadeRating.RETIRE,
            onClick = { onSelect(FadeRating.RETIRE) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RatingButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(
            text = text,
            maxLines = 2,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun computeOverallThreshold(ratings: Map<Int, FadeRating>): Int {
    return FADE_LEVELS.firstNotNullOfOrNull { level ->
        when (ratings[level.percent]) {
            FadeRating.BORDERLINE, FadeRating.RETIRE -> level.percent
            else -> null
        }
    } ?: 30
}

private fun fadeFilter(saturation: Float, brightness: Float): ColorFilter {
    val luminosityR = 0.213f
    val luminosityG = 0.715f
    val luminosityB = 0.072f
    val inverse = 1f - saturation
    val scaled = brightness
    val matrix = floatArrayOf(
        (luminosityR * inverse + saturation) * scaled, luminosityG * inverse * scaled, luminosityB * inverse * scaled, 0f, 0f,
        luminosityR * inverse * scaled, (luminosityG * inverse + saturation) * scaled, luminosityB * inverse * scaled, 0f, 0f,
        luminosityR * inverse * scaled, luminosityG * inverse * scaled, (luminosityB * inverse + saturation) * scaled, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
    return ColorFilter.colorMatrix(ColorMatrix(matrix))
}
