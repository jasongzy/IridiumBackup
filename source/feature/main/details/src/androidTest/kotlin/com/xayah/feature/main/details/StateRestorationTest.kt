package com.xayah.feature.main.details

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import com.xayah.core.model.DataState
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.ui.component.DialogState
import com.xayah.core.ui.component.PackageDataStatesSaver
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.rememberDialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StateRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dismissedDialogHostCancelsPendingAndLateConfirmations() {
        val visible = mutableStateOf(true)
        lateinit var dialog: DialogState
        lateinit var job: Job
        compose.setContent {
            MaterialTheme { if (visible.value) dialog = rememberDialogState() }
        }
        compose.runOnIdle {
            job = CoroutineScope(Dispatchers.Main.immediate).launch { dialog.confirm("Confirm", "Proceed?") }
        }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertTrue(job.isCancelled) }
        compose.runOnIdle {
            job = CoroutineScope(Dispatchers.Main.immediate).launch { dialog.confirm("Confirm", "Proceed?") }
        }
        compose.runOnIdle { assertTrue(job.isCancelled) }
    }

    @Test
    fun replacingConfirmationCancelsOnlyThePreviousRequest() {
        lateinit var dialog: DialogState
        lateinit var previous: Job
        lateinit var current: Job
        compose.setContent { MaterialTheme { dialog = rememberDialogState() } }
        compose.runOnIdle {
            val scope = CoroutineScope(Dispatchers.Main.immediate)
            previous = scope.launch { dialog.confirm("First", "Proceed?") }
            current = scope.launch { dialog.confirm("Second", "Proceed?") }
        }
        compose.runOnIdle {
            assertTrue(previous.isCancelled)
            assertFalse(current.isCompleted)
        }
        compose.onNodeWithText("Second").assertExists()
        compose.runOnIdle { current.cancel() }
        compose.onNodeWithText("Second").assertDoesNotExist()
    }

    @Test
    fun confirmationAcceptsOnlyOneResponse() {
        lateinit var dialog: DialogState
        var confirmations = 0
        compose.setContent { MaterialTheme { dialog = rememberDialogState() } }
        compose.runOnIdle {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                if (dialog.open(false, "Confirm", confirmText = "Accept") {}.first.isConfirm) confirmations++
            }
        }
        val confirm = compose.onNodeWithText("Accept").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            confirm()
            confirm()
            assertEquals(1, confirmations)
        }
    }

    @Test
    fun confirmationCallbackCanOpenAnotherDialog() {
        lateinit var dialog: DialogState
        var confirmations = 0
        compose.setContent { MaterialTheme { dialog = rememberDialogState() } }
        compose.runOnIdle {
            dialog.open("First", confirmText = { "Accept" }, dismissText = { "Cancel" }, onConfirm = {
                confirmations++
                dialog.confirm("Second", "Proceed?") {}
            }) {}
        }
        val confirm = compose.onNodeWithText("Accept").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            confirm()
            confirm()
            assertEquals(1, confirmations)
        }
        compose.onNodeWithText("Second").assertExists()
    }

    @Test
    fun backupScopeSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        lateinit var states: MutableState<PackageDataStates>
        restoration.setContent {
            states = rememberSaveable(stateSaver = PackageDataStatesSaver) {
                mutableStateOf(PackageDataStates())
            }
        }
        val selection = PackageDataStates(
            apkState = DataState.Disabled,
            userState = DataState.NotSelected,
            userDeState = DataState.Disabled,
            dataState = DataState.NotSelected,
            permissionState = DataState.NotSelected,
            ssaidState = DataState.Disabled,
        )
        compose.runOnIdle { states.value = selection }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(selection, states.value) }
    }
}

class NoteEditorRecreationTest {
    @get:Rule
    val compose = createAndroidComposeRule<NoteEditorActivity>()

    @Test
    fun noteDraftSurvivesActivityRecreation() {
        val activity = compose.activity
        compose.onNode(hasSetTextAction()).performTextReplacement("unsaved draft")
        compose.activityRule.scenario.recreate()
        assertNotSame(activity, compose.activity)
        compose.onNode(hasSetTextAction()).assertTextContains("unsaved draft")
    }
}

class NoteEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { NoteEditorDialog(note = "saved", onDismiss = {}, onConfirm = {}) }
        }
    }
}
