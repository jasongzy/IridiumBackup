package com.xayah.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.xayah.core.model.ConfigurationSettings
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.ThemeType
import com.xayah.core.model.util.of
import kotlinx.coroutines.flow.first

suspend fun Context.readConfigurationSettings(): ConfigurationSettings {
    val snapshot = dataStore.data.first()
    return ConfigurationSettings(
        monet = snapshot.monet,
        themeType = ThemeType.of(snapshot[KeyThemeType]).name,
        language = snapshot[KeyLanguage] ?: ConstantUtil.LANGUAGE_SYSTEM,
        autoScreenOff = snapshot.autoScreenOff,
        loadSystemApps = snapshot.loadSystemApps,
        compressionLevel = snapshot[KeyCompressionLevel] ?: DEFAULT_COMPRESSION_LEVEL,
        killAppOption = KillAppOption.of(snapshot[KeyKillAppOption]).name,
        fastSameVersionBackup = snapshot.fastSameVersionBackup,
        backupItself = snapshot.backupItself,
        backupConfigs = snapshot.backupConfigs,
        compressionTest = snapshot.compressionTest,
        followSymlinks = snapshot.followSymlinks,
        cleanRestoring = snapshot.cleanRestoring,
        restorePermissions = snapshot.restorePermissions,
        restoreSsaid = snapshot.restoreSsaid,
    )
}

fun ConfigurationSettings.validatePreferences() {
    ThemeType.valueOf(themeType)
    KillAppOption.valueOf(killAppOption)
    require(language.trim() == ConstantUtil.LANGUAGE_SYSTEM || language.trim() in ConstantUtil.SupportedLanguages) { "Unsupported language: $language" }
    require(compressionLevel in ConstantUtil.CompressionLevels) { "Invalid compression level: $compressionLevel" }
}

suspend fun Context.saveConfigurationSettings(settings: ConfigurationSettings) {
    settings.validatePreferences()
    dataStore.edit { preferences ->
        preferences[KeyMonet] = settings.monet
        preferences[KeyThemeType] = settings.themeType
        preferences[KeyLanguage] = settings.language.trim()
        preferences[KeyAutoScreenOff] = settings.autoScreenOff
        preferences[KeyLoadSystemApps] = settings.loadSystemApps
        preferences[KeyCompressionLevel] = settings.compressionLevel
        preferences[KeyKillAppOption] = settings.killAppOption
        preferences[KeyFastSameVersionBackup] = settings.fastSameVersionBackup
        preferences[KeyBackupItself] = settings.backupItself
        preferences[KeyBackupConfigs] = settings.backupConfigs
        preferences[KeyCompressionTest] = settings.compressionTest
        preferences[KeyFollowSymlinks] = settings.followSymlinks
        preferences[KeyCleanRestoring] = settings.cleanRestoring
        preferences[KeyRestorePermissions] = settings.restorePermissions
        preferences[KeyRestoreSsaid] = settings.restoreSsaid
    }
}
