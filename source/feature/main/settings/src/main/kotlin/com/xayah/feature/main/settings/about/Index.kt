package com.xayah.feature.main.settings.about

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.ui.component.AppIcon
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.HeadlineSmallText
import com.xayah.core.ui.component.OutlinedButtonIconTextButton
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageAboutSettings() {
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.about),
        actions = {}
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(modifier = Modifier.paddingVertical(SizeTokens.Level16))
            HeadlineSmallText(
                text = stringResource(id = R.string.app_name),
                color = ThemedColorSchemeKeyTokens.OnSurface.value,
            )
            BodyMediumText(
                modifier = Modifier.paddingTop(SizeTokens.Level4),
                text = "${stringResource(id = R.string.version)} ${BuildConfigUtil.VERSION_NAME} (${BuildConfigUtil.VERSION_CODE})",
                color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
            )
            OutlinedButtonIconTextButton(
                modifier = Modifier
                    .paddingVertical(SizeTokens.Level16)
                    .width(SizeTokens.Level128),
                icon = Icons.Outlined.Code,
                text = stringResource(id = R.string.github),
            ) {
                uriHandler.openUri(ConstantUtil.GITHUB_LINK)
            }
        }
    }
}
