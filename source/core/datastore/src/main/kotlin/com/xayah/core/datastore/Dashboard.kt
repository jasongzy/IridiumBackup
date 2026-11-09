package com.xayah.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val KeyDashboardSortField = stringPreferencesKey("dashboard_sort_field")
private val KeyDashboardSortAscending = booleanPreferencesKey("dashboard_sort_ascending")
private val KeyDashboardSystemApps = booleanPreferencesKey("dashboard_filter_system_apps")
private val KeyDashboardNonSystemApps = booleanPreferencesKey("dashboard_filter_non_system_apps")
private val KeyDashboardFrozenApps = booleanPreferencesKey("dashboard_filter_frozen_apps")
private val KeyDashboardUnfrozenApps = booleanPreferencesKey("dashboard_filter_unfrozen_apps")
private val KeyDashboardXposedModules = booleanPreferencesKey("dashboard_filter_xposed_modules")
private val KeyDashboardNonXposedModules = booleanPreferencesKey("dashboard_filter_non_xposed_modules")
private val KeyDashboardHasBackups = booleanPreferencesKey("dashboard_filter_has_backups")
private val KeyDashboardHasNoBackups = booleanPreferencesKey("dashboard_filter_has_no_backups")
private val KeyDashboardSingleBackup = booleanPreferencesKey("dashboard_filter_single_backup")
private val KeyDashboardMultipleBackups = booleanPreferencesKey("dashboard_filter_multiple_backups")
private val KeyDashboardInstalledApps = booleanPreferencesKey("dashboard_filter_installed_apps")
private val KeyDashboardNotInstalledApps = booleanPreferencesKey("dashboard_filter_not_installed_apps")
private val KeyDashboardHasApkBackup = booleanPreferencesKey("dashboard_filter_has_apk_backup")
private val KeyDashboardHasNoApkBackup = booleanPreferencesKey("dashboard_filter_has_no_apk_backup")
private val KeyDashboardHasDataBackup = booleanPreferencesKey("dashboard_filter_has_data_backup")
private val KeyDashboardHasNoDataBackup = booleanPreferencesKey("dashboard_filter_has_no_data_backup")
private val KeyDashboardHasNonMatchingApkBackup = booleanPreferencesKey("dashboard_filter_has_non_matching_apk_backup")
private val KeyDashboardMatchAllLabels = booleanPreferencesKey("dashboard_filter_match_all_labels")
private val KeyDashboardLabelFilters = stringSetPreferencesKey("dashboard_label_filters")

data class DashboardSortPreference(
    val field: DashboardSortField,
    val ascending: Boolean,
)

enum class DashboardSortField {
    NAME,
    INSTALLED,
    DATA_SIZE,
    UPDATED,
    BACKED_UP,
}

data class DashboardFilterPreference(
    val systemApps: Boolean = true,
    val nonSystemApps: Boolean = true,
    val frozenApps: Boolean = true,
    val unfrozenApps: Boolean = true,
    val xposedModules: Boolean = false,
    val nonXposedModules: Boolean = false,
    val hasBackups: Boolean = true,
    val hasNoBackups: Boolean = true,
    val singleBackup: Boolean = false,
    val multipleBackups: Boolean = false,
    val installedApps: Boolean = true,
    val notInstalledApps: Boolean = true,
    val hasApkBackup: Boolean = false,
    val hasNoApkBackup: Boolean = false,
    val hasDataBackup: Boolean = false,
    val hasNoDataBackup: Boolean = false,
    val hasNonMatchingApkBackup: Boolean = false,
    val matchAllLabels: Boolean = false,
)

enum class LabelFilterMode { INCLUDE, EXCLUDE }

fun Context.readDashboardSortPreference() = dataStore.data.map { preferences ->
    DashboardSortPreference(
        field = preferences[KeyDashboardSortField]
            ?.let { runCatching { DashboardSortField.valueOf(it) }.getOrNull() }
            ?: DashboardSortField.UPDATED,
        ascending = preferences[KeyDashboardSortAscending] ?: false,
    )
}

suspend fun Context.saveDashboardSortPreference(field: DashboardSortField, ascending: Boolean) {
    dataStore.edit { preferences ->
        preferences[KeyDashboardSortField] = field.name
        preferences[KeyDashboardSortAscending] = ascending
    }
}

fun Context.readDashboardFilterPreference() = dataStore.data.map { preferences ->
    DashboardFilterPreference(
        systemApps = preferences[KeyDashboardSystemApps] ?: true,
        nonSystemApps = preferences[KeyDashboardNonSystemApps] ?: true,
        frozenApps = preferences[KeyDashboardFrozenApps] ?: true,
        unfrozenApps = preferences[KeyDashboardUnfrozenApps] ?: true,
        xposedModules = preferences[KeyDashboardXposedModules] ?: false,
        nonXposedModules = preferences[KeyDashboardNonXposedModules] ?: false,
        hasBackups = preferences[KeyDashboardHasBackups] ?: true,
        hasNoBackups = preferences[KeyDashboardHasNoBackups] ?: true,
        singleBackup = preferences[KeyDashboardSingleBackup] ?: false,
        multipleBackups = preferences[KeyDashboardMultipleBackups] ?: false,
        installedApps = preferences[KeyDashboardInstalledApps] ?: true,
        notInstalledApps = preferences[KeyDashboardNotInstalledApps] ?: true,
        hasApkBackup = preferences[KeyDashboardHasApkBackup] ?: false,
        hasNoApkBackup = preferences[KeyDashboardHasNoApkBackup] ?: false,
        hasDataBackup = preferences[KeyDashboardHasDataBackup] ?: false,
        hasNoDataBackup = preferences[KeyDashboardHasNoDataBackup] ?: false,
        hasNonMatchingApkBackup = preferences[KeyDashboardHasNonMatchingApkBackup] ?: false,
        matchAllLabels = preferences[KeyDashboardMatchAllLabels] ?: false,
    )
}

private fun Preferences.labelFilters(): Map<String, LabelFilterMode> =
    this[KeyDashboardLabelFilters].orEmpty().mapNotNull { entry ->
        val separator = entry.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val mode = LabelFilterMode.entries.find { it.name == entry.substring(0, separator) } ?: return@mapNotNull null
        entry.substring(separator + 1) to mode
    }.toMap()

fun Context.readDashboardLabelFilters() = dataStore.data.map { it.labelFilters() }.distinctUntilChanged()

fun Map<String, LabelFilterMode>.renameLabelFilter(oldLabel: String, newLabel: String?): Map<String, LabelFilterMode> {
    val mode = this[oldLabel] ?: return this
    val remaining = this - oldLabel
    return if (newLabel == null) remaining else remaining + (newLabel to mode)
}

internal fun MutablePreferences.editLabelFilters(edit: (Map<String, LabelFilterMode>) -> Map<String, LabelFilterMode>) {
    val filters = edit(labelFilters())
    if (filters.isEmpty()) remove(KeyDashboardLabelFilters)
    else this[KeyDashboardLabelFilters] = filters.mapTo(mutableSetOf()) { (label, mode) -> "${mode.name}:$label" }
}

suspend fun Context.editDashboardLabelFilters(edit: (Map<String, LabelFilterMode>) -> Map<String, LabelFilterMode>) {
    dataStore.edit { it.editLabelFilters(edit) }
}

suspend fun Context.saveDashboardFilterPreference(preference: DashboardFilterPreference, resetLabels: Boolean = false) {
    dataStore.edit { preferences ->
        preferences[KeyDashboardSystemApps] = preference.systemApps
        preferences[KeyDashboardNonSystemApps] = preference.nonSystemApps
        preferences[KeyDashboardFrozenApps] = preference.frozenApps
        preferences[KeyDashboardUnfrozenApps] = preference.unfrozenApps
        preferences[KeyDashboardXposedModules] = preference.xposedModules
        preferences[KeyDashboardNonXposedModules] = preference.nonXposedModules
        preferences[KeyDashboardHasBackups] = preference.hasBackups
        preferences[KeyDashboardHasNoBackups] = preference.hasNoBackups
        preferences[KeyDashboardSingleBackup] = preference.singleBackup
        preferences[KeyDashboardMultipleBackups] = preference.multipleBackups
        preferences[KeyDashboardInstalledApps] = preference.installedApps
        preferences[KeyDashboardNotInstalledApps] = preference.notInstalledApps
        preferences[KeyDashboardHasApkBackup] = preference.hasApkBackup
        preferences[KeyDashboardHasNoApkBackup] = preference.hasNoApkBackup
        preferences[KeyDashboardHasDataBackup] = preference.hasDataBackup
        preferences[KeyDashboardHasNoDataBackup] = preference.hasNoDataBackup
        preferences[KeyDashboardHasNonMatchingApkBackup] = preference.hasNonMatchingApkBackup
        preferences[KeyDashboardMatchAllLabels] = preference.matchAllLabels
        if (resetLabels) preferences.remove(KeyDashboardLabelFilters)
    }
}
