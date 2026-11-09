package com.xayah.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.map

private val KeyLabelColors = stringPreferencesKey("label_colors")
private val LabelColorsType = object : TypeToken<Map<String, Long>>() {}.type

private val labelColorGson = Gson()

private fun Preferences.labelColors(): Map<String, Long> =
    this[KeyLabelColors]?.let { labelColorGson.fromJson<Map<String, Long>>(it, LabelColorsType) }.orEmpty()

fun Context.readLabelColors() = dataStore.data.map { it.labelColors() }

private fun MutablePreferences.editLabelColors(edit: MutableMap<String, Long>.() -> Unit) {
    val colors = labelColors().toMutableMap().apply(edit)
    if (colors.isEmpty()) remove(KeyLabelColors)
    else this[KeyLabelColors] = labelColorGson.toJson(colors)
}

suspend fun Context.saveLabelColor(label: String, colorArgb: Long) = saveLabelColors(mapOf(label to colorArgb))

suspend fun Context.saveLabelColors(labelColors: Map<String, Long>) {
    dataStore.edit { it.editLabelColors { putAll(labelColors) } }
}

suspend fun Context.deleteLabelPreferences(label: String) {
    dataStore.edit { preferences ->
        preferences.editLabelColors { remove(label) }
        preferences.editLabelFilters { it - label }
    }
}

suspend fun Context.saveEditedLabel(oldLabel: String, newLabel: String, colorArgb: Long) {
    dataStore.edit { preferences ->
        preferences.editLabelColors { remove(oldLabel); put(newLabel, colorArgb) }
        if (oldLabel != newLabel) preferences.editLabelFilters { it.renameLabelFilter(oldLabel, newLabel) }
    }
}
