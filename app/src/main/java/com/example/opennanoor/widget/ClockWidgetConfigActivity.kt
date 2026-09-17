package com.example.opennanoor.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.opennanoor.ui.theme.OpenNaNoorTheme

private const val MAX_CITIES = 4

/**
 * The system launches this whenever a ClockWidget instance is placed (and
 * again on re-tap, via ClockWidgetProvider's own click intent) - the
 * contract for a widget declaring android:configure is that this activity
 * MUST set a result (OK with EXTRA_APPWIDGET_ID on save, CANCELED
 * otherwise) or the system throws the placement away entirely.
 */
class ClockWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            OpenNaNoorTheme {
                var selected by remember {
                    mutableStateOf(ClockWidgetStore.citiesFor(this, appWidgetId))
                }
                var query by remember { mutableStateOf("") }

                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("Clock cities", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Pick up to $MAX_CITIES - e.g. New Zealand for 9pm there right now.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))

                        if (selected.isNotEmpty()) {
                            Text("Selected", style = MaterialTheme.typography.labelLarge)
                            selected.forEach { city ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(city.label, style = MaterialTheme.typography.bodyMedium)
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.clickable {
                                            selected = selected.filterNot { it == city }
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search cities") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        val results = remember(query, selected) {
                            CLOCK_CITIES.filter { city ->
                                selected.none { it.zoneId == city.zoneId } &&
                                    (query.isBlank() || city.label.contains(query, ignoreCase = true))
                            }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(results) { city ->
                                Text(
                                    city.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = selected.size < MAX_CITIES) {
                                            selected = selected + city
                                            query = ""
                                        }
                                        .padding(vertical = 12.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                ClockWidgetStore.setCitiesFor(this@ClockWidgetConfigActivity, appWidgetId, selected)
                                ClockWidgetProvider.refresh(this@ClockWidgetConfigActivity, appWidgetId)
                                setResult(
                                    Activity.RESULT_OK,
                                    android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                )
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
