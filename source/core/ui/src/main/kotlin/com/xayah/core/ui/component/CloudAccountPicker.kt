package com.xayah.core.ui.component

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.ui.R
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.util.icon

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun CloudAccountPicker(
    accounts: List<CloudEntity>,
    selected: CloudEntity?,
    autoSelectFirst: Boolean,
    onManage: () -> Unit,
    enabled: Boolean = true,
    onSelect: (CloudEntity?) -> Unit,
) {
    val index = if (selected == null && autoSelectFirst) 0 else accounts.indexOfFirst { it.name == selected?.name }
    val current = accounts.getOrNull(index)
    val latestAccounts by rememberUpdatedState(accounts)
    LaunchedEffect(current, selected, enabled) {
        if (enabled && current != selected) onSelect(current)
    }
    val title = stringResource(R.string.account)
    if (accounts.isEmpty()) {
        Clickable(
            enabled = enabled,
            title = title,
            value = stringResource(R.string.no_available_account),
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_rounded_cancel_circle),
            trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            onClick = onManage,
        )
    } else {
        val dialog = LocalSlotScope.current!!.dialogSlot
        Selectable(
            enabled = enabled,
            title = title,
            leadingIcon = current?.type?.icon ?: ImageVector.vectorResource(R.drawable.ic_rounded_person),
            value = current?.user,
            current = current?.name ?: stringResource(R.string.choose_an_account),
        ) {
            val options = accounts.map { DialogRadioItem(enum = it.name, title = it.name, desc = it.user) }
            val (result, choice) = dialog.select(title, index.coerceAtLeast(0), options)
            if (result.isConfirm) onSelect(latestAccounts.firstOrNull { it.name == options[choice].title })
        }
    }
}
