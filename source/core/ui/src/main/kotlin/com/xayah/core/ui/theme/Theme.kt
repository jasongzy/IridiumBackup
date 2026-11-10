package com.xayah.core.ui.theme

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.xayah.core.datastore.readMonet
import com.xayah.core.datastore.readThemeType
import com.xayah.core.model.ThemeType
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.rememberSlotScope
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

val LocalThemeType = staticCompositionLocalOf { ThemeType.AUTO }

@Composable
fun DataBackupTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val appearance by remember(context) {
        combine(context.readThemeType(), context.readMonet(), ::Pair).catch { error ->
            if (error !is IOException) throw error
            emit(ThemeType.AUTO to true)
        }
    }.collectAsStateWithLifecycle(initialValue = null)
    val (themeType, dynamicColor) = appearance ?: return
    val darkTheme = when (themeType) {
        ThemeType.AUTO -> isSystemInDarkTheme()
        ThemeType.LIGHT_THEME -> false
        ThemeType.DARK_THEME -> true
    }
    LaunchedEffect(themeType) {
        AppCompatDelegate.setDefaultNightMode(when (themeType) {
            ThemeType.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeType.LIGHT_THEME -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeType.DARK_THEME -> AppCompatDelegate.MODE_NIGHT_YES
        })
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    val themedColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkThemedColorScheme(context) else dynamicLightThemedColorScheme(context)
        }

        darkTheme -> darkThemedColorScheme()
        else -> lightThemedColorScheme()
    }
    // Transparent system bars
    val systemUiController = rememberSystemUiController()
    SideEffect {
        systemUiController.setStatusBarColor(
            color = Color.Transparent,
            darkIcons = !darkTheme
        )
        systemUiController.setNavigationBarColor(
            color = Color.Transparent,
            darkIcons = !darkTheme,
            navigationBarContrastEnforced = false
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = {
            CompositionLocalProvider(
                LocalThemeType provides themeType,
                LocalThemedColorScheme provides themedColorScheme,
            ) {
                // LocalThemedColorScheme should be applied first.
                val slotScope = rememberSlotScope()
                CompositionLocalProvider(
                    LocalSlotScope provides slotScope,
                    content = content
                )
            }
        }
    )
}
