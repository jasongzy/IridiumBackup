package com.xayah.core.datastore

import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.ThemeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationSettingsTest {
    @Test
    fun importsPreferencesTogetherAndPreservesThemOnInvalidInput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = context.readConfigurationSettings()
        val imported = original.copy(
            monet = !original.monet,
            themeType = ThemeType.DARK_THEME.name,
            language = "en",
            autoScreenOff = !original.autoScreenOff,
            loadSystemApps = !original.loadSystemApps,
            compressionLevel = 3,
            killAppOption = KillAppOption.OPTION_III.name,
            fastSameVersionBackup = !original.fastSameVersionBackup,
            backupItself = !original.backupItself,
            backupConfigs = !original.backupConfigs,
            compressionTest = !original.compressionTest,
            followSymlinks = !original.followSymlinks,
            cleanRestoring = !original.cleanRestoring,
            restorePermissions = !original.restorePermissions,
            restoreSsaid = !original.restoreSsaid,
        )
        try {
            for (invalid in listOf(imported.copy(themeType = "unknown"), imported.copy(killAppOption = "unknown"))) {
                val error = runCatching { context.saveConfigurationSettings(invalid) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertEquals(original, context.readConfigurationSettings())
            }
            context.saveConfigurationSettings(imported)
            assertEquals(imported, context.readConfigurationSettings())
        } finally {
            context.saveConfigurationSettings(original)
        }
    }
}
