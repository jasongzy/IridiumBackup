package com.xayah.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.map

// -----------------------------------------Keys-----------------------------------------
val KeyMonet = booleanPreferencesKey("monet")
val KeyBackupItself = booleanPreferencesKey("backup_itself")
val KeyCompressionTest = booleanPreferencesKey("compression_test")
val KeyFollowSymlinks = booleanPreferencesKey("follow_symlinks")
val KeyCleanRestoring = booleanPreferencesKey("clean_restoring")
val KeyReloadDumpApk = booleanPreferencesKey("reload_dump_apk")
val KeyAutoScreenOff = booleanPreferencesKey("auto_screen_off")
val KeyLoadSystemApps = booleanPreferencesKey("load_system_apps")
val KeyBackupConfigs = booleanPreferencesKey("backup_configs")
val KeyRestorePermissions = booleanPreferencesKey("restore_permissions")
val KeyRestoreSsaid = booleanPreferencesKey("restore_ssaid")
val KeyFastSameVersionBackup = booleanPreferencesKey("fast_same_version_backup")

// -----------------------------------------Read-----------------------------------------
internal val Preferences.monet get() = this[KeyMonet] ?: true
internal val Preferences.backupItself get() = this[KeyBackupItself] ?: true
internal val Preferences.compressionTest get() = this[KeyCompressionTest] ?: true
internal val Preferences.followSymlinks get() = this[KeyFollowSymlinks] ?: false
internal val Preferences.cleanRestoring get() = this[KeyCleanRestoring] ?: true
internal val Preferences.loadSystemApps get() = this[KeyLoadSystemApps] ?: true
internal val Preferences.autoScreenOff get() = this[KeyAutoScreenOff] ?: false
internal val Preferences.backupConfigs get() = this[KeyBackupConfigs] ?: true
internal val Preferences.restorePermissions get() = this[KeyRestorePermissions] ?: false
internal val Preferences.restoreSsaid get() = this[KeyRestoreSsaid] ?: true
internal val Preferences.fastSameVersionBackup get() = this[KeyFastSameVersionBackup] ?: true

fun Context.readMonet() = dataStore.data.map { it.monet }
fun Context.readBackupItself() = dataStore.data.map { it.backupItself }
fun Context.readCompressionTest() = dataStore.data.map { it.compressionTest }
fun Context.readFollowSymlinks() = dataStore.data.map { it.followSymlinks }
fun Context.readCleanRestoring() = dataStore.data.map { it.cleanRestoring }
fun Context.readLoadSystemApps() = dataStore.data.map { it.loadSystemApps }
fun Context.readReloadDumpApk() = readStoreBoolean(key = KeyReloadDumpApk, defValue = true)
fun Context.readAutoScreenOff() = dataStore.data.map { it.autoScreenOff }
fun Context.readBackupConfigs() = dataStore.data.map { it.backupConfigs }
fun Context.readRestorePermissions() = dataStore.data.map { it.restorePermissions }
fun Context.readRestoreSsaid() = dataStore.data.map { it.restoreSsaid }
fun Context.readFastSameVersionBackup() = dataStore.data.map { it.fastSameVersionBackup }

// -----------------------------------------Write-----------------------------------------
suspend fun Context.saveMonet(value: Boolean) = saveStoreBoolean(key = KeyMonet, value = value)
suspend fun Context.saveBackupItself(value: Boolean) = saveStoreBoolean(key = KeyBackupItself, value = value)
suspend fun Context.saveCompressionTest(value: Boolean) = saveStoreBoolean(key = KeyCompressionTest, value = value)
suspend fun Context.saveFollowSymlinks(value: Boolean) = saveStoreBoolean(key = KeyFollowSymlinks, value = value)
suspend fun Context.saveCleanRestoring(value: Boolean) = saveStoreBoolean(key = KeyCleanRestoring, value = value)
suspend fun Context.saveLoadSystemApps(value: Boolean) = saveStoreBoolean(key = KeyLoadSystemApps, value = value)
suspend fun Context.saveReloadDumpApk(value: Boolean) = saveStoreBoolean(key = KeyReloadDumpApk, value = value)
suspend fun Context.saveAutoScreenOff(value: Boolean) = saveStoreBoolean(key = KeyAutoScreenOff, value = value)
suspend fun Context.saveBackupConfigs(value: Boolean) = saveStoreBoolean(key = KeyBackupConfigs, value = value)
suspend fun Context.saveRestorePermissions(value: Boolean) = saveStoreBoolean(key = KeyRestorePermissions, value = value)
suspend fun Context.saveRestoreSsaid(value: Boolean) = saveStoreBoolean(key = KeyRestoreSsaid, value = value)
