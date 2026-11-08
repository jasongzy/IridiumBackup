package com.xayah.core.util.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupServiceSettingsTest {
    @Test
    fun onlyTheStoppedAppsLostComponentsCanBeRestored() {
        val ime = "default_input_method"
        val services = "enabled_accessibility_services"
        assertEquals("app/.Ime", BackupServiceSettings.replacement(ime, "app", "app/.Ime", ""))
        assertNull(BackupServiceSettings.replacement(ime, "app", "app/.Ime", "other/.Ime"))
        assertNull(BackupServiceSettings.replacement(ime, "app", "other/.Ime", ""))
        assertEquals("new/.C:app/.B", BackupServiceSettings.replacement(services, "app", "old/.A:app/.B", "new/.C"))
        assertNull(BackupServiceSettings.replacement(services, "app", "old/.A", "new/.C"))
    }
}
