package com.xayah.feature.main.cloud.add

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.model.CloudType
import com.xayah.core.model.SFTPAuthMode
import com.xayah.core.model.SmbAuthMode
import com.xayah.core.model.SmbVersion
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.model.database.SMBExtra
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.model.database.isValidCloudAccountName
import com.xayah.core.network.client.getCloud
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.GsonUtil
import com.xayah.feature.main.cloud.R
import com.xayah.feature.main.cloud.runCloudOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class IndexUiState(
    val currentName: String,
    val cloudEntity: CloudEntity?,
    val isProcessing: Boolean,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data class UpdateEntity(
        val name: String,
        val remote: String,
        val type: CloudType,
        val url: String,
        val username: String,
        val password: String,
        val extra: String,
    ) : IndexUiIntent()

    data class CreateAccount(val navController: NavHostController) : IndexUiIntent()

    data object TestConnection : IndexUiIntent()
    data class DeleteAccount(val navController: NavHostController) : IndexUiIntent()
    data class SetRemotePath(val context: Context) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    private val cloudRepo: CloudRepository,
    args: SavedStateHandle,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        currentName = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME) ?: "",
        cloudEntity = null,
        isProcessing = false,
    )
) {
    init {
        launchOnIO {
            val state = uiState.value
            if (state.currentName.isNotEmpty()) {
                emitState(state.copy(cloudEntity = cloudRepo.queryByName(state.currentName)))
            }
        }
    }

    suspend fun updateFTPEntity(name: String, remote: String, url: String, username: String, password: String, port: Int) {
        val extra = GsonUtil().toJson(FTPExtra(port = port))
        emitIntent(
            IndexUiIntent.UpdateEntity(
                name = name,
                type = CloudType.FTP,
                url = url,
                username = username,
                password = password,
                extra = extra,
                remote = remote,
            )
        )
    }

    suspend fun updateSFTPEntity(name: String, remote: String, url: String, username: String, password: String, port: Int, mode: SFTPAuthMode, privateKey: String) {
        val extra = GsonUtil().toJson(
            SFTPExtra(
                port = port,
                privateKey = if (mode == SFTPAuthMode.PUBLIC_KEY) privateKey else "",
                mode = mode,
            )
        )
        emitIntent(
            IndexUiIntent.UpdateEntity(
                name = name,
                type = CloudType.SFTP,
                url = url,
                username = username,
                password = password,
                extra = extra,
                remote = remote,
            )
        )
    }

    suspend fun updateWebDAVEntity(name: String, remote: String, url: String, username: String, password: String, insecure: Boolean) {
        val extra = GsonUtil().toJson(WebDAVExtra(insecure = insecure))
        emitIntent(
            IndexUiIntent.UpdateEntity(
                name = name,
                type = CloudType.WEBDAV,
                url = url,
                username = username,
                password = password,
                extra = extra,
                remote = remote,
            )
        )
    }

    suspend fun updateSMBEntity(name: String, remote: String, url: String, username: String, password: String, share: String, port: Int, domain: String, mode: SmbAuthMode) {
        val extra = GsonUtil().toJson(
            SMBExtra(
                share = share,
                port = port,
                domain = domain,
                version = listOf(SmbVersion.SMB_2_0_2, SmbVersion.SMB_2_1, SmbVersion.SMB_3_0, SmbVersion.SMB_3_0_2, SmbVersion.SMB_3_1_1),
                mode = mode,
            )
        )
        emitIntent(
            IndexUiIntent.UpdateEntity(
                name = name,
                type = CloudType.SMB,
                url = url,
                username = username,
                password = password,
                extra = extra,
                remote = remote,
            )
        )
    }

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.CreateAccount -> {
                val account = checkNotNull(state.cloudEntity)
                if (state.currentName.isEmpty()) {
                    val error = when {
                        !isValidCloudAccountName(account.name) -> R.string.cloud_account_name_invalid
                        !cloudRepo.add(account) -> R.string.cloud_account_name_taken
                        else -> null
                    }
                    if (error != null) {
                        emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = cloudRepo.getString(error)))
                        return
                    }
                } else {
                    cloudRepo.upsert(account)
                }
                withMainContext {
                    intent.navController.popBackStack()
                    if (state.currentName.isEmpty()) {
                        intent.navController.popBackStack()
                    }
                }
            }

            is IndexUiIntent.UpdateEntity -> {
                emitState(
                    state.copy(
                        cloudEntity = CloudEntity(
                            name = intent.name,
                            type = intent.type,
                            host = intent.url,
                            user = intent.username,
                            pass = intent.password,
                            remote = intent.remote,
                            extra = intent.extra,
                            activated = false,
                        )
                    )
                )
            }

            is IndexUiIntent.TestConnection -> {
                runCloudOperation(
                    processing = cloudRepo.getString(R.string.processing),
                    setProcessing = { busy -> updateState { it.copy(isProcessing = busy) } },
                    success = cloudRepo.getString(R.string.connection_established),
                ) {
                    checkNotNull(state.cloudEntity).getCloud().testConnection()
                }
            }

            is IndexUiIntent.DeleteAccount -> {
                cloudRepo.delete(state.currentName)
                withMainContext {
                    intent.navController.popBackStack()
                }
            }

            is IndexUiIntent.SetRemotePath -> {
                runCloudOperation(
                    processing = cloudRepo.getString(R.string.processing),
                    setProcessing = { busy -> updateState { it.copy(isProcessing = busy) } },
                ) {
                    val account = checkNotNull(state.cloudEntity)
                    account.getCloud().setRemote(intent.context) { remote, extra ->
                        updateState { it.copy(cloudEntity = account.copy(remote = remote, extra = extra)) }
                    }
                }
            }
        }
    }
}
