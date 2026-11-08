package com.xayah.feature.main.settings.restore

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xayah.core.datastore.KeyCleanRestoring
import com.xayah.core.datastore.KeyRestorePermissions
import com.xayah.core.datastore.KeyRestoreSsaid
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.settings.KillAppOptionPreference
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageRestoreSettings() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.restore_settings),
        actions = {}
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            Column {
                KillAppOptionPreference()

                Switchable(
                    key = KeyCleanRestoring,
                    defValue = true,
                    title = stringResource(id = R.string.clean_restoring),
                    checkedText = stringResource(id = R.string.clean_restoring_desc),
                )

                Switchable(
                    key = KeyRestorePermissions,
                    defValue = false,
                    title = stringResource(id = R.string.restore_permissions),
                    checkedText = stringResource(id = R.string.restore_permissions_desc),
                )
                Switchable(
                    key = KeyRestoreSsaid,
                    defValue = true,
                    title = stringResource(id = R.string.restore_ssaid),
                    checkedText = stringResource(id = R.string.restore_ssaid_desc),
                )
            }
            InnerBottomSpacer(innerPadding = it)
        }
    }
}
