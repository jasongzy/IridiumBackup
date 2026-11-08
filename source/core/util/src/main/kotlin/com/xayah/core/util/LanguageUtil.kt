package com.xayah.core.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.xayah.core.datastore.ConstantUtil

object LanguageUtil {
    fun applyLanguage(language: String) {
        AppCompatDelegate.setApplicationLocales(
            if (language == ConstantUtil.LANGUAGE_SYSTEM) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(language)
        )
    }
}
