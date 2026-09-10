package com.example.opennanoor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.opennanoor.core.Feature
import com.example.opennanoor.core.Requirement
import com.example.opennanoor.launcher.IconPackInfo

data class FeatureState(
    val feature: Feature,
    val enabled: Boolean,
    val permissionGranted: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    features: List<FeatureState>,
    foregroundApp: String?,
    iconPacks: List<IconPackInfo>,
    activePack: String?,
    isDefaultHome: Boolean,
    dockIconCount: Int,
    onToggle: (Feature, Boolean) -> Unit,
    onGrant: (Requirement) -> Unit,
    onSelectPack: (String?) -> Unit,
    onOpenHomeSettings: () -> Unit,
    onSetDockIconCount: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("OpenNaNoor") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                HomeSwitcherCard(
                    isDefaultHome = isDefaultHome,
                    onOpenHomeSettings = onOpenHomeSettings
                )
            }

            if (foregroundApp != null) {
                item { ForegroundAppCard(foregroundApp) }
            }

            items(features, key = { it.feature.id }) { state ->
                FeatureCard(
                    state = state,
                    onToggle = { onToggle(state.feature, it) },
                    onGrant = { onGrant(state.feature.requirement) }
                )
            }

            item {
                IconPackCard(
                    packs = iconPacks,
                    active = activePack,
                    onSelect = onSelectPack
                )
            }

            item {
                DockIconCountCard(
                    count = dockIconCount,
                    onChange = onSetDockIconCount
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HomeSwitcherCard(
    isDefaultHome: Boolean,
    onOpenHomeSettings: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDefaultHome) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Home screen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isDefaultHome) {
                        "OpenNaNoor is your home screen."
                    } else {
                        "The Pixel launcher is your home screen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpenHomeSettings) {
                Text(if (isDefaultHome) "Switch back" else "Switch to OpenNaNoor")
            }
        }
    }
}

@Composable
private fun IconPackCard(
    packs: List<IconPackInfo>,
    active: String?,
    onSelect: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val activeLabel = packs.firstOrNull { it.packageName == active }?.label
        ?: "System icons"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Icon pack", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (packs.isEmpty()) {
                    "No icon packs installed. Any pack that works with Nova or ADW will work here."
                } else {
                    "Artwork used on the OpenNaNoor home screen."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Box {
                TextButton(onClick = { open = true }, enabled = packs.isNotEmpty()) {
                    Text(activeLabel)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("System icons") },
                        onClick = { onSelect(null); open = false }
                    )
                    packs.forEach { pack ->
                        DropdownMenuItem(
                            text = { Text(pack.label) },
                            onClick = { onSelect(pack.packageName); open = false }
                        )
                    }
                }
            }
        }
    }
}

/**
 * How many icons the dock fits, 1-5. Independent of the page grid's own
 * icon size - the dock keeps its own outer size no matter what's picked
 * here; more icons just makes each one smaller within it.
 *
 * Only 4 (today's size, unchanged) and 5 (fitting one more at the same
 * total footprint) are wired up to actually apply yet - 1 to 3 are visible
 * on the slider but don't change anything yet, so the full range the
 * eventual feature needs is already in place without waiting on the
 * smaller sizes to be built out first.
 */
@Composable
private fun DockIconCountCard(count: Int, onChange: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Dock icon count", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (count in 4..5) {
                    "$count icons in the dock. The dock itself stays the same size - more icons just makes each one smaller."
                } else {
                    "1-3 aren't wired up yet - pick 4 or 5."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Slider(
                value = count.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (1..5).forEach { n ->
                    Text(
                        text = "$n",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (n == count) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ForegroundAppCard(packageName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Currently in front", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun FeatureCard(
    state: FeatureState,
    onToggle: (Boolean) -> Unit,
    onGrant: () -> Unit
) {
    val feature = state.feature
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(feature.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = feature.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onToggle,
                    enabled = feature.available && state.permissionGranted
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                !feature.available -> Text(
                    text = "Not built yet - needs ${feature.requirement.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )

                !state.permissionGranted -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Needs ${feature.requirement.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onGrant) { Text("Grant") }
                }

                feature.requirement == Requirement.NONE -> Text(
                    text = "Ready",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )

                else -> Text(
                    text = feature.requirement.label + " granted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
