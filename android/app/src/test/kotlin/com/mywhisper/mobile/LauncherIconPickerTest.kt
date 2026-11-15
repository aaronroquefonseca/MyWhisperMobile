package com.mywhisper.mobile

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "en")
class LauncherIconPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun previewChoicesAreRadioSelectionsAndDefaultCanBeRestored() {
        var selected by mutableStateOf(LauncherIconStyle.DEFAULT)
        compose.setContent {
            MaterialTheme { LauncherIconPicker(selected, nightly = false) { selected = it } }
        }
        compose.onNodeWithText("Default").assertIsSelected()
        for ((label, style) in listOf("Cobalt / white" to LauncherIconStyle.COBALT,
                "Cream / ink" to LauncherIconStyle.CREAM, "Charcoal / white" to LauncherIconStyle.CHARCOAL,
                "Navy / cyan" to LauncherIconStyle.NAVY)) {
            compose.onNodeWithText(label).performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(style, selected) }
        }
        compose.onNodeWithText("Default").performClick().assertIsSelected()
        compose.onNodeWithText("Stable default: Navy / cyan").assertExists()
    }

    @Test
    fun nightlyDefaultExplainsCobaltAndChoicesDisableWhileApplying() {
        compose.setContent {
            MaterialTheme { LauncherIconPicker(LauncherIconStyle.DEFAULT, nightly = true, enabled = false) {} }
        }
        compose.onNodeWithText("Nightly default: Cobalt / white").assertExists()
        compose.onNodeWithText("Default").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("Cream / ink").assertIsNotEnabled()
    }
}
