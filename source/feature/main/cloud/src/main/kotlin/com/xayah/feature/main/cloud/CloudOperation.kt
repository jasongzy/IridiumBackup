package com.xayah.feature.main.cloud

import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal suspend fun BaseViewModel<*, *, IndexUiEffect>.runCloudOperation(
    processing: String,
    setProcessing: (Boolean) -> Unit,
    success: String? = null,
    operation: suspend () -> Unit,
) {
    setProcessing(true)
    try {
        emitEffect(IndexUiEffect.DismissSnackbar)
        coroutineScope {
            val loading = launch {
                emitEffect(IndexUiEffect.ShowSnackbar(processing, SnackbarType.Loading, duration = SnackbarDuration.Indefinite))
            }
            try {
                operation()
            } finally {
                loading.cancel()
            }
        }
        if (success != null) emitEffectOnIO(IndexUiEffect.ShowSnackbar(success, SnackbarType.Success))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        emitEffectOnIO(IndexUiEffect.ShowSnackbar(message, SnackbarType.Error, duration = SnackbarDuration.Long))
    } finally {
        setProcessing(false)
    }
}
