package com.secureguard.mdm.screentime.vm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureguard.mdm.appblocker.AppInfo
import com.secureguard.mdm.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * מצב המסך של הגדרות הגבלת זמן המסך.
 * ההגדרה גלובלית: אותה מגבלת דקות יומית ואותו חלון שעות מותרות
 * חלים על כל האפליקציות שנבחרו.
 */
data class ScreenTimeSettingsState(
    val isLoading: Boolean = true,
    val isEnabled: Boolean = false,
    val allApps: List<AppInfo> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val dailyLimitMinutes: Int = 60,
    val allowedStartHour: Int = 16,
    val allowedEndHour: Int = 20,
    val searchQuery: String = ""
) {
    val filteredApps: List<AppInfo>
        get() = if (searchQuery.isBlank()) {
            allApps
        } else {
            allApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
        }
}

@HiltViewModel
class ScreenTimeSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScreenTimeSettingsState())
    val uiState = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        viewModelScope.launch {
            val isEnabled = settingsRepository.isScreenTimeEnabled()
            val selected = settingsRepository.getScreenTimeAppPackages()
            val minutes = settingsRepository.getScreenTimeDailyLimitMinutes()
            val startHour = settingsRepository.getScreenTimeAllowedStartHour()
            val endHour = settingsRepository.getScreenTimeAllowedEndHour()
            val apps = getInstalledUserApps()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    isEnabled = isEnabled,
                    allApps = apps,
                    selectedPackages = selected,
                    dailyLimitMinutes = minutes,
                    allowedStartHour = startHour,
                    allowedEndHour = endHour
                )
            }
        }
    }

    private suspend fun getInstalledUserApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                appInfo.packageName != context.packageName &&
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(appInfo.packageName) != null)
            }
            .map { appInfo ->
                AppInfo(
                    appName = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm),
                    isBlocked = false,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isLauncherApp = pm.getLaunchIntentForPackage(appInfo.packageName) != null,
                    isSuspended = false,
                    isInstalled = true
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    fun onToggleEnabled(isEnabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setScreenTimeEnabled(isEnabled)
            _uiState.update { it.copy(isEnabled = isEnabled) }
            if (isEnabled) {
                com.secureguard.mdm.utils.JobSchedulerHelper.scheduleScreenTimeEnforcer(context)
            } else {
                com.secureguard.mdm.utils.JobSchedulerHelper.cancelScreenTimeEnforcer(context)
                // כובה - משחררים מיד כל אפליקציה שהושעתה ע"י הפיצ'ר הזה
                releaseAllScreenTimeSuspensions()
            }
        }
    }

    fun toggleApp(packageName: String) {
        viewModelScope.launch {
            val current = _uiState.value.selectedPackages
            val updated = if (current.contains(packageName)) current - packageName else current + packageName
            settingsRepository.setScreenTimeAppPackages(updated)
            _uiState.update { it.copy(selectedPackages = updated) }
        }
    }

    fun onDailyLimitChanged(minutes: Int) {
        viewModelScope.launch {
            val safeMinutes = minutes.coerceIn(5, 24 * 60)
            settingsRepository.setScreenTimeDailyLimitMinutes(safeMinutes)
            _uiState.update { it.copy(dailyLimitMinutes = safeMinutes) }
        }
    }

    fun onAllowedHoursChanged(startHour: Int, endHour: Int) {
        viewModelScope.launch {
            val safeStart = startHour.coerceIn(0, 23)
            val safeEnd = endHour.coerceIn(0, 23)
            settingsRepository.setScreenTimeAllowedHours(safeStart, safeEnd)
            _uiState.update { it.copy(allowedStartHour = safeStart, allowedEndHour = safeEnd) }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    private suspend fun releaseAllScreenTimeSuspensions() {
        com.secureguard.mdm.services.ScreenTimeEnforcer.releaseAllSuspensions(context, settingsRepository)
    }
}
