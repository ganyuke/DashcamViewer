package org.nextcolor.dashcamviewer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KeyboardUiTest {
    @get:Rule val activity = createAndroidComposeRule<MainActivity>()

    private fun imeVisible() =
        ViewCompat.getRootWindowInsets(activity.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test
    fun keyboardKeepsPasswordVisibleAndDoneReturnsToConnect() {
        activity
            .onNodeWithText("Camera Wi-Fi name")
            .performScrollTo()
            .performClick()
            .performTextReplacement("A13_KEYBOARD_TEST")
        activity.waitUntil(5_000) { imeVisible() }
        activity.onNodeWithText("Camera Wi-Fi name").performImeAction()
        activity.onNodeWithText("Wi-Fi password").assertIsFocused().assertIsDisplayed()
        activity.onNodeWithText("Wi-Fi password").performTextInput("12345678")
        activity.waitForIdle()
        Thread.sleep(350) // Wait for the real input-method window animation.
        val keyboardHeight =
            ViewCompat.getRootWindowInsets(activity.activity.window.decorView)!!.getInsets(
                    WindowInsetsCompat.Type.ime()
                )
                .bottom
        val keyboardTop =
            activity.activity.windowManager.currentWindowMetrics.bounds.bottom - keyboardHeight
        val password = activity.onNodeWithText("Wi-Fi password").fetchSemanticsNode().boundsInWindow
        assertTrue(
            "The password field must be above the keyboard",
            password.bottom <= keyboardTop + 1,
        )
        activity.onNode(hasText("Camera") and isSelectable()).assertDoesNotExist()
        val folder =
            File(activity.activity.getExternalFilesDir(null), "keyboard-review").apply { mkdirs() }
        val screenshot =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()
        File(folder, "password-with-keyboard.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        activity.onNodeWithText("Wi-Fi password").performImeAction()
        activity.waitUntil(5_000) { !imeVisible() }
        activity.onNodeWithText("Connect camera").assertIsDisplayed().assertIsEnabled()
        activity.onNode(hasText("Camera") and isSelectable()).assertExists()
    }
}
