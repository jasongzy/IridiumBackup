package com.xayah.core.datastore

import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.ThemeType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationSettingsTest {
    @Test
    fun applyingOtherFiltersDoesNotRestoreAnOldLabelSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = context.readDashboardFilterPreference().first()
        val labels = context.readDashboardLabelFilters().first()
        val colors = context.readLabelColors().first()
        try {
            context.editDashboardLabelFilters { mapOf("work:old" to LabelFilterMode.EXCLUDE) }
            context.saveEditedLabel("work:old", "work:new", 0xFF0000FF)
            context.saveDashboardFilterPreference(saved.copy(frozenApps = !saved.frozenApps))
            assertEquals(mapOf("work:new" to LabelFilterMode.EXCLUDE), context.readDashboardLabelFilters().first())
            context.deleteLabelPreferences("work:new")
            assertTrue(context.readDashboardLabelFilters().first().isEmpty())
        } finally {
            context.saveDashboardFilterPreference(saved)
            context.deleteLabelPreferences("work:new")
            context.saveLabelColors(colors)
            context.editDashboardLabelFilters { labels }
        }
    }

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
            killAppOption = if (original.killAppOption == KillAppOption.OPTION_II.name) {
                KillAppOption.OPTION_III.name
            } else {
                KillAppOption.OPTION_II.name
            },
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
            for (invalid in listOf(imported.copy(themeType = "unknown"), imported.copy(killAppOption = "unknown"), imported.copy(compressionLevel = 23), imported.copy(language = "unknown"))) {
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
