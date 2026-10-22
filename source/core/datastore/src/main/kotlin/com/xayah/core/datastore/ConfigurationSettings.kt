package com.xayah.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.xayah.core.model.ConfigurationSettings
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.ThemeType
import kotlinx.coroutines.flow.first

suspend fun Context.readConfigurationSettings(): ConfigurationSettings {
    return ConfigurationSettings(
        monet = readMonet().first(),
        themeType = readThemeType().first().name,
        language = readLanguage().first(),
        autoScreenOff = readAutoScreenOff().first(),
        loadSystemApps = readLoadSystemApps().first(),
        compressionLevel = readCompressionLevel().first(),
        killAppOption = readKillAppOption().first().name,
        fastSameVersionBackup = readFastSameVersionBackup().first(),
        backupItself = readBackupItself().first(),
        backupConfigs = readBackupConfigs().first(),
        compressionTest = readCompressionTest().first(),
        followSymlinks = readFollowSymlinks().first(),
        cleanRestoring = readCleanRestoring().first(),
        restorePermissions = readRestorePermissions().first(),
        restoreSsaid = readRestoreSsaid().first(),
    )
}

suspend fun Context.saveConfigurationSettings(settings: ConfigurationSettings) {
    dataStore.edit { preferences ->
        preferences[KeyMonet] = settings.monet
        preferences[KeyThemeType] = ThemeType.valueOf(settings.themeType).name
        preferences[KeyLanguage] = settings.language.trim()
        preferences[KeyAutoScreenOff] = settings.autoScreenOff
        preferences[KeyLoadSystemApps] = settings.loadSystemApps
        preferences[KeyCompressionLevel] = settings.compressionLevel
        preferences[KeyKillAppOption] = KillAppOption.valueOf(settings.killAppOption).name
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
