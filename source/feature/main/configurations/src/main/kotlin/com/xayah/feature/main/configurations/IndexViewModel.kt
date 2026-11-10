package com.xayah.feature.main.configurations

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.room.withTransaction
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.requireBackupStorage
import com.xayah.core.data.repository.importBlacklist
import com.xayah.core.database.AppDatabase
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.validatePreferences
import com.xayah.core.datastore.saveConfigurationSettings
import com.xayah.core.model.Configurations
import com.xayah.core.model.database.isValidCloudAccountName
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.ui.component.DialogState
import com.xayah.core.ui.component.select
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.model.DialogCheckBoxItem
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.ConfigsConfigurationsName
import com.xayah.core.util.PathUtil
import com.xayah.core.util.LanguageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class IndexUiState(
    val blacklistSelected: Boolean,
    val cloudSelected: Boolean,
    val labelSelected: Boolean,
    val appNotesSelected: Boolean,
    val settingsSelected: Boolean,
) : UiState {
    val selectedCount get() = listOf(blacklistSelected, cloudSelected, labelSelected, appNotesSelected, settingsSelected).count { it }
}

sealed class IndexUiIntent : UiIntent {
    data object Export : IndexUiIntent()
    data class Import(val dialogState: DialogState) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val database: AppDatabase,
    private val cloudRepo: CloudRepository,
    private val labelsRepo: LabelsRepo,
    private val appBackupRepository: AppBackupRepository,
    private val commonBackupUtil: CommonBackupUtil,
    private val pathUtil: PathUtil,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        blacklistSelected = true,
        cloudSelected = true,
        labelSelected = true,
        appNotesSelected = true,
        settingsSelected = true,
    )
) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        try {
            when (intent) {
                is IndexUiIntent.Export -> {
                    val backupDir = context.localBackupSaveDir()
                    rootService.requireBackupStorage(context, backupDir, allowNewDirectory = true)
                    val result = commonBackupUtil.backupConfigs(
                        dstDir = "$backupDir/${PathUtil.getConfigsRelativeDir()}",
                        includeBlacklist = state.blacklistSelected,
                        includeCloud = state.cloudSelected,
                        includeLabels = state.labelSelected,
                        includeAppNotes = state.appNotesSelected,
                        includeSettings = state.settingsSelected,
                    )
                    emitEffect(IndexUiEffect.DismissSnackbar)
                    emitEffect(IndexUiEffect.ShowSnackbar(type = if (result.isSuccess) SnackbarType.Success else SnackbarType.Error, message = result.outString, duration = SnackbarDuration.Short))
                }

                is IndexUiIntent.Import -> {
                    val src = "${pathUtil.getLocalBackupConfigsDir()}/$ConfigsConfigurationsName"
                    val json = rootService.readTextIfPresent(src)
                    if (json == null) {
                        emitEffect(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = "${context.getString(R.string.not_exist)}: $src", duration = SnackbarDuration.Short))
                        return
                    }
                    val config = requireNotNull(rootService.gsonUtil.fromJson<Configurations?>(json, Configurations::class.java)) {
                        context.getString(R.string.config_file_may_be_broken)
                    }
                    val items = buildList {
                        fun addGroup(key: String, title: Int, count: Int) {
                            if (count > 0) add(DialogCheckBoxItem(enum = key, title = "${context.getString(title)} ($count)"))
                        }
                        addGroup(ConstantUtil.CONFIGURATIONS_KEY_BLACKLIST, R.string.blacklist, config.blacklist.apps.size + config.blacklist.files.size)
                        addGroup(ConstantUtil.CONFIGURATIONS_KEY_CLOUD, R.string.cloud_accounts, config.cloud.size)
                        addGroup(ConstantUtil.CONFIGURATIONS_KEY_LABEL, R.string.labels, config.labels.size + config.labelAppRefs.size + config.labelFileRefs.size)
                        addGroup(ConstantUtil.CONFIGURATIONS_KEY_APP_NOTES, R.string.app_notes, config.appNotes.orEmpty().size)
                        if (config.settings != null) add(DialogCheckBoxItem(enum = ConstantUtil.CONFIGURATIONS_KEY_SETTINGS, title = context.getString(R.string.other_settings)))
                    }
                    check(items.isNotEmpty()) { context.getString(R.string.config_file_may_be_broken) }
                    val (dialog, selection) = intent.dialogState.select(title = context.getString(R.string._import), def = items.map { true }, items = items)
                    if (!dialog.isConfirm || selection.none { it }) return
                    val selected = items.filterIndexed { index, _ -> selection[index] }.map { it.enum }
                    val settings = config.settings?.takeIf { ConstantUtil.CONFIGURATIONS_KEY_SETTINGS in selected }
                    settings?.validatePreferences()
                    if (ConstantUtil.CONFIGURATIONS_KEY_CLOUD in selected) {
                        require(config.cloud.all { isValidCloudAccountName(it.name) }) { context.getString(R.string.config_file_may_be_broken) }
                    }
                    val labelColors = if (ConstantUtil.CONFIGURATIONS_KEY_LABEL in selected) {
                        val labelNames = config.labels.mapTo(mutableSetOf(), LabelEntity::label)
                        require(labelNames.none(String::isBlank) && config.labelAppRefs.all { it.label in labelNames } && config.labelFileRefs.all { it.label in labelNames }) {
                            context.getString(R.string.config_file_may_be_broken)
                        }
                        requireNotNull(config.labelColors) { context.getString(R.string.config_file_may_be_broken) }.filterKeys(labelNames::contains)
                    } else null
                    database.withTransaction {
                        if (ConstantUtil.CONFIGURATIONS_KEY_BLACKLIST in selected) database.importBlacklist(config.blacklist)
                        if (ConstantUtil.CONFIGURATIONS_KEY_CLOUD in selected) cloudRepo.upsert(config.cloud)
                        if (labelColors != null) {
                            labelsRepo.addLabels(config.labels)
                            labelsRepo.addLabelAppCrossRefs(config.labelAppRefs)
                            labelsRepo.addLabelFileCrossRefs(config.labelFileRefs)
                        }
                        if (ConstantUtil.CONFIGURATIONS_KEY_APP_NOTES in selected) appBackupRepository.saveAppNotes(config.appNotes.orEmpty())
                    }
                    if (labelColors != null) labelsRepo.setLabelColors(labelColors)
                    if (settings != null) {
                        context.saveConfigurationSettings(settings)
                        withMainContext { LanguageUtil.applyLanguage(settings.language.trim()) }
                    }

                    emitEffect(IndexUiEffect.DismissSnackbar)
                    emitEffect(IndexUiEffect.ShowSnackbar(type = SnackbarType.Success, message = context.getString(R.string.imported), duration = SnackbarDuration.Short))
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emitEffect(IndexUiEffect.DismissSnackbar)
            emitEffect(
                IndexUiEffect.ShowSnackbar(
                    type = SnackbarType.Error,
                    message = error.localizedMessage ?: error.javaClass.simpleName,
                    duration = SnackbarDuration.Long,
                )
            )
        }
    }

    val counts = database.appBackupDao().observeConfigurationCounts().stateInScope(null)
}
