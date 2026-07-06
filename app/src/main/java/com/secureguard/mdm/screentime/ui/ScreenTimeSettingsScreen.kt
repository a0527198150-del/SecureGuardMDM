package com.secureguard.mdm.screentime.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.secureguard.mdm.R
import com.secureguard.mdm.appblocker.AppInfo
import com.secureguard.mdm.screentime.vm.ScreenTimeSettingsViewModel
import com.secureguard.mdm.services.ScreenTimeEnforcer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTimeSettingsScreen(
    viewModel: ScreenTimeSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val hasUsageAccess = remember { mutableStateOf(ScreenTimeEnforcer.hasUsageAccessPermission(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.screen_time_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.screen_time_enable_toggle),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Switch(
                        checked = uiState.isEnabled,
                        onCheckedChange = { viewModel.onToggleEnabled(it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!hasUsageAccess.value) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(id = R.string.screen_time_usage_access_warning),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }) {
                                Text(stringResource(id = R.string.screen_time_usage_access_button))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            item {
                Text(
                    text = stringResource(id = R.string.screen_time_daily_limit_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = uiState.dailyLimitMinutes.toFloat(),
                        onValueChange = { viewModel.onDailyLimitChanged(it.toInt()) },
                        valueRange = 5f..240f,
                        steps = 46,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${uiState.dailyLimitMinutes} ${stringResource(id = R.string.screen_time_minutes_suffix)}")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text(
                    text = stringResource(id = R.string.screen_time_allowed_hours_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    HourPicker(
                        label = stringResource(id = R.string.screen_time_allowed_hours_from),
                        hour = uiState.allowedStartHour,
                        onHourChange = { viewModel.onAllowedHoursChanged(it, uiState.allowedEndHour) }
                    )
                    HourPicker(
                        label = stringResource(id = R.string.screen_time_allowed_hours_to),
                        hour = uiState.allowedEndHour,
                        onHourChange = { viewModel.onAllowedHoursChanged(uiState.allowedStartHour, it) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text(
                    text = stringResource(id = R.string.screen_time_select_apps_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text(stringResource(id = R.string.screen_time_search_apps_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(uiState.filteredApps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    isSelected = uiState.selectedPackages.contains(app.packageName),
                    onClick = { viewModel.toggleApp(app.packageName) }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HourPicker(label: String, hour: Int, onHourChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onHourChange(((hour - 1) + 24) % 24) }) {
                Text("-", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = String.format("%02d:00", hour),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(onClick = { onHourChange((hour + 1) % 24) }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberDrawablePainter(drawable = app.icon),
            contentDescription = app.appName,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = app.appName,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Checkbox(checked = isSelected, onCheckedChange = { onClick() })
    }
}
