package com.xayah.feature.main.processing

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.saveScreenOffCountDown
import com.xayah.core.model.OperationState
import com.xayah.core.ui.component.AnimatedTextContainer
import com.xayah.core.ui.component.BodyLargeText
import com.xayah.core.ui.component.HeadlineMediumText
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.ProcessingCard
import com.xayah.core.ui.component.SegmentCircularProgressIndicator
import com.xayah.core.ui.component.Surface
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.paddingBottom
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.AnimationTokens
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.util.withMainContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay

@OptIn(ExperimentalAnimationGraphicsApi::class)
@SuppressLint("StringFormatInvalid")
@ExperimentalCoroutinesApi
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageProcessing(
    topBarTitleId: (state: OperationState) -> Int,
    finishedTitleId: Int,
    finishedSubtitleId: Int,
    finishedWithErrorsSubtitleId: Int,
    viewModel: AbstractProcessingViewModel,
    autoStart: Boolean = false,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val task by viewModel.task.collectAsStateWithLifecycle()
    val preItemsProgress by viewModel.preItemsProgress.collectAsStateWithLifecycle()
    val preItems by viewModel.preItems.collectAsStateWithLifecycle()
    val postItemsProgress by viewModel.postItemsProgress.collectAsStateWithLifecycle()
    val postItems by viewModel.postItems.collectAsStateWithLifecycle()
    val dataItems by viewModel.dataItems.collectAsStateWithLifecycle()
    val navController = LocalNavController.current!!
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val progress: Float by remember(task?.processingIndex, dataItems.size) {
        mutableFloatStateOf(
            if (task != null)
                task!!.processingIndex.toFloat() / (dataItems.size + 1)
            else
                -1f
        )
    }
    val screenOffCountDown by viewModel.screenOffCountDown.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, autoStart) {
        viewModel.initialize(autoStart)
    }

    LaunchedEffect(screenOffCountDown, uiState.state) {
        if (screenOffCountDown != 0) {
            if (uiState.state != OperationState.PROCESSING) {
                context.saveScreenOffCountDown(0)
            } else {
                viewModel.launchOnIO {
                    viewModel.emitEffect(IndexUiEffect.DismissSnackbar)
                    viewModel.emitEffect(
                        IndexUiEffect.ShowSnackbar(
                            type = SnackbarType.Success,
                            message = context.getString(R.string.args_screen_off_in_seconds, screenOffCountDown),
                            duration = SnackbarDuration.Indefinite
                        )
                    )
                }
                delay(screenOffCountDown * 1000L)
                viewModel.emitEffectOnIO(IndexUiEffect.DismissSnackbar)
                context.saveScreenOffCountDown(0)
                viewModel.emitIntent(ProcessingUiIntent.TurnOffScreen)
            }
        }
    }

    val onBack: () -> Unit = remember {
        {
            if (uiState.state == OperationState.PROCESSING) {
                viewModel.launchOnIO {
                    if (dialogState.confirm(title = context.getString(R.string.prompt), text = context.getString(R.string.processing_exit_confirmation))) {
                        viewModel.emitIntent(ProcessingUiIntent.Cancel)
                        viewModel.emitIntent(ProcessingUiIntent.ClearTask)
                        withMainContext {
                            navController.popBackStack()
                        }
                    }
                }
            } else {
                viewModel.launchOnIO {
                    viewModel.emitIntent(ProcessingUiIntent.DestroyService)
                    viewModel.emitIntent(ProcessingUiIntent.ClearTask)
                    withMainContext {
                        navController.popBackStack()
                    }
                }
            }
        }
    }

    BackHandler {
        onBack()
    }

    ProcessingSetupScaffold(
        scrollBehavior = null,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = if (uiState.state == OperationState.ERROR) R.string.failed else topBarTitleId.invoke(uiState.state)),
        progress = if (uiState.state == OperationState.ERROR) -1f else progress,
        actions = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level24)
                    .paddingVertical(SizeTokens.Level8),
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = when (uiState.state) {
                        OperationState.IDLE -> !autoStart
                        OperationState.PROCESSING, OperationState.DONE, OperationState.ERROR -> true
                        else -> false
                    },
                    onClick = {
                        if (uiState.state == OperationState.IDLE) viewModel.emitIntentOnIO(ProcessingUiIntent.Process)
                        else onBack()
                    },
                ) {
                    val actionText = when (uiState.state) {
                        OperationState.PROCESSING -> R.string.cancel
                        OperationState.DONE, OperationState.ERROR -> R.string.finish
                        else -> R.string._continue
                    }
                    AnimatedTextContainer(targetState = stringResource(actionText)) { text ->
                        Text(text = text)
                    }
                }
            }

        },
        onBackClick = {
            onBack()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ThemedColorSchemeKeyTokens.Surface.value)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val index = task?.processingIndex
                val currentItem = dataItems.getOrNull((index ?: 0) - 1)
                val title = when (index) {
                    null -> ""
                    0 -> stringResource(R.string.preprocessing)
                    dataItems.size + 1 -> stringResource(R.string.post_processing)
                    dataItems.size + 2 -> stringResource(finishedTitleId)
                    else -> currentItem?.title.orEmpty()
                }
                val subtitle = when (index) {
                    null -> ""
                    0 -> preItems.getOrNull(task?.preprocessingIndex ?: -1)?.title.orEmpty()
                    dataItems.size + 1 -> postItems.getOrNull(task?.postProcessingIndex ?: -1)?.title.orEmpty()
                    dataItems.size + 2 -> if ((task?.failureCount ?: 0) == 0) {
                        stringResource(finishedSubtitleId, task?.successCount ?: dataItems.size)
                    } else {
                        stringResource(finishedWithErrorsSubtitleId, task!!.successCount, task!!.failureCount)
                    }
                    else -> currentItem?.let { item ->
                        item.items.getOrNull(item.processingIndex)?.let { it.title + if (it.content.isEmpty()) "" else " (${it.content})" }
                    } ?: stringResource(R.string.necessary_remaining_data_processing)
                }
                val segmentProgress = when (index) {
                    0 -> preItemsProgress
                    dataItems.size + 1 -> postItemsProgress
                    dataItems.size + 2 -> 1f
                    else -> currentItem?.progress ?: 0f
                }
                val segments = when (index) {
                    0 -> preItems.size
                    dataItems.size + 1 -> postItems.size
                    else -> currentItem?.items?.size ?: 0
                }
                val animatedImageVector = AnimatedImageVector.animatedVectorResource(com.xayah.core.ui.R.drawable.ic_animated_tick)
                var atEnd by remember { mutableStateOf(false) }

                if (LocalConfiguration.current.screenHeightDp >= 480) {
                    AnimatedContent(modifier = Modifier.fillMaxWidth(), targetState = task?.processingIndex, label = AnimationTokens.AnimatedContentLabel) {
                        Box(
                            modifier = Modifier
                                .paddingTop(SizeTokens.Level24)
                                .paddingBottom(SizeTokens.Level16),
                            contentAlignment = Alignment.Center
                        ) {
                            if (it != null) {
                                when (it) {
                                    dataItems.size + 2 -> {
                                        // Finished
                                        LaunchedEffect(animatedImageVector) {
                                            atEnd = true
                                        }
                                        Image(
                                            painter = rememberAnimatedVectorPainter(animatedImageVector, atEnd),
                                            contentDescription = null,
                                            modifier = Modifier.size(SizeTokens.Level152),
                                        )
                                    }

                                    else -> {
                                        // Processing, Pre-processing, Post-processing
                                        PackageIconImage(
                                            icon = if (it > 0 && it < dataItems.size + 1) null else ImageVector.vectorResource(id = com.xayah.core.ui.R.drawable.ic_rounded_hourglass_empty),
                                            packageName = dataItems.getOrNull(it - 1)?.key ?: "",
                                            inCircleShape = true,
                                            size = SizeTokens.Level128
                                        )

                                        SegmentCircularProgressIndicator(
                                            size = SizeTokens.Level152,
                                            segments = segments,
                                            progress = segmentProgress,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                HeadlineMediumText(text = title)
                BodyLargeText(
                    modifier = Modifier
                        .paddingTop(SizeTokens.Level4)
                        .paddingBottom(SizeTokens.Level24), text = subtitle, color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value
                )
            }

            Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(SizeTokens.Level12), color = ThemedColorSchemeKeyTokens.SurfaceContainerLowest.value, shadowElevation = SizeTokens.Level1) {
                val lazyListState = rememberLazyListState()

                LaunchedEffect(task?.processingIndex, dataItems.size) {
                    val currentIndex = (task?.processingIndex ?: 0) - 1
                    if (currentIndex in dataItems.indices) {
                        lazyListState.animateScrollToItem(currentIndex + 1)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(SizeTokens.Level12)
                ) {
                    item {
                        Spacer(modifier = Modifier.size(SizeTokens.Level12))
                    }
                    items(count = dataItems.size) {
                        var expandedOverride by rememberSaveable(task?.id, it) { mutableStateOf<Boolean?>(null) }
                        val automaticallyExpanded = task?.processingIndex == it + 1
                        val expanded = expandedOverride ?: automaticallyExpanded
                        val item = dataItems.getOrNull(it)
                        if (item != null) {
                            ProcessingCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .paddingHorizontal(SizeTokens.Level24),
                                title = item.title,
                                state = item.state,
                                packageName = item.key,
                                expanded = expanded,
                                items = item.items,
                                processingIndex = item.processingIndex,
                                onActionBarClick = {
                                    expandedOverride = (!expanded).takeUnless { it == automaticallyExpanded }
                                }
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.size(SizeTokens.Level12 + it))
                    }
                }
            }
        }
    }
    uiState.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.failed)) },
            text = {
                SelectionContainer {
                    Text(failure, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.finish)) }
            },
        )
    }
}
