package com.xayah.feature.main.settings.language

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.util.LocalNavController
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold
import java.util.Locale

@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageLanguageSelector() {
    val navController = LocalNavController.current!!
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val viewModel = hiltViewModel<IndexViewModel>()
    val selectedLanguage = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag() ?: ConstantUtil.LANGUAGE_SYSTEM

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.language),
    ) {
        val sortedLocales = ConstantUtil.SupportedLanguages
        LazyColumn {
            items(count = sortedLocales.size + 1) {
                if (it == 0) {
                    Clickable(
                        enabled = selectedLanguage != ConstantUtil.LANGUAGE_SYSTEM,
                        title = stringResource(id = R.string.system),
                        onClick = { viewModel.emitIntentOnIO(IndexUiIntent.UpdateLanguage(navController, ConstantUtil.LANGUAGE_SYSTEM)) },
                    )
                } else {
                    val item = sortedLocales[it - 1]
                    val locale = Locale.forLanguageTag(item)
                    Clickable(
                        enabled = selectedLanguage != item,
                        title = locale.getDisplayName(locale),
                        onClick = { viewModel.emitIntentOnIO(IndexUiIntent.UpdateLanguage(navController, item)) },
                    )
                }
            }
        }
    }
}
