package com.xayah.feature.main.details

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.xayah.core.ui.component.FullscreenModalBottomSheet
import com.xayah.core.ui.component.ModalBottomSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class BottomSheetGestureTest(private val fullscreen: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "fullscreen={0}")
        fun variants() = listOf(false, true)
    }

    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(false)
    private val session = mutableIntStateOf(0)
    private lateinit var sheetState: SheetState
    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun setContent() {
        compose.setContent {
            MaterialTheme {
                key(session.intValue) { sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fullscreen) }
                if (visible.value) {
                    val content: @Composable ColumnScope.() -> Unit = {
                        backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                        Text("Filters", Modifier.fillMaxWidth().padding(24.dp).testTag("title"))
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("content")) {
                            repeat(30) { Text("Option $it", Modifier.fillMaxWidth().padding(16.dp)) }
                        }
                    }
                    if (fullscreen) {
                        FullscreenModalBottomSheet(
                            title = "Private key",
                            onDismissRequest = { visible.value = false },
                            sheetState = sheetState,
                            content = content,
                        )
                    } else {
                        ModalBottomSheet(onDismissRequest = { visible.value = false }, sheetState = sheetState, content = content)
                    }
                }
            }
        }
    }

    @Test
    fun upwardSwipesKeepTheSheetOpen() {
        setContent()
        for (region in if (fullscreen) listOf("title", "content") else listOf("handle", "title", "content")) {
            for (duration in listOf(100L, 600L)) {
                compose.runOnIdle { visible.value = true }
                val target = if (region == "handle") {
                    compose.onNodeWithContentDescription("Drag handle")
                } else {
                    compose.onNodeWithTag(region)
                }
                target.performTouchInput {
                    val start = if (region == "content") Offset(center.x, 16f) else center
                    swipe(start, start - Offset(0f, 500f), durationMillis = duration)
                }
                compose.runOnIdle {
                    assertTrue("Swiping $region for $duration ms dismissed the sheet", visible.value)
                    assertTrue(sheetState.isVisible)
                    visible.value = false
                }
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun upwardSwipesDuringOpeningKeepTheSheetOpen() {
        setContent()
        for (delay in listOf(96L, 160L)) {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                session.intValue++
                visible.value = true
            }
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            compose.mainClock.advanceTimeBy(delay)
            compose.runOnIdle {
                assertEquals(SheetValue.Hidden, sheetState.currentValue)
                assertEquals(if (fullscreen) SheetValue.Expanded else SheetValue.PartiallyExpanded, sheetState.targetValue)
            }
            compose.onNodeWithTag("content").performTouchInput {
                swipe(Offset(center.x, 16f), Offset(center.x, -484f), durationMillis = 150)
            }
            compose.mainClock.autoAdvance = true
            compose.runOnIdle {
                assertTrue("Upward swipe $delay ms into opening dismissed the sheet", visible.value)
                assertTrue(sheetState.isVisible)
                visible.value = false
            }
            compose.waitForIdle()
        }
    }

    @Test
    fun downwardSwipeOutsideTapAndBackDismissTheSheet() {
        setContent()
        for (action in if (fullscreen) listOf("swipe", "back") else listOf("swipe", "outside", "back")) {
            compose.runOnIdle { visible.value = true }
            compose.runOnIdle { assertEquals(if (fullscreen) SheetValue.Expanded else SheetValue.PartiallyExpanded, sheetState.currentValue) }
            when (action) {
                "swipe" -> compose.onNodeWithTag("title").performTouchInput {
                    swipe(center, Offset(center.x, center.y + 1000), durationMillis = 200)
                }
                "outside" -> compose.onNodeWithContentDescription("Drag handle").performTouchInput {
                    click(Offset(center.x, -150f))
                }
                "back" -> compose.runOnIdle { backDispatcher.onBackPressed() }
            }
            compose.waitUntil(timeoutMillis = 3_000) { !visible.value }
        }
    }
}
