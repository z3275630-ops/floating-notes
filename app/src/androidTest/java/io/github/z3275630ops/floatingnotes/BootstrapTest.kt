package io.github.z3275630ops.floatingnotes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class BootstrapTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launchAndLoadSyntheticData() {
        compose.onNodeWithText("浮记").assertExists()
        compose.onNodeWithText("载入合成示例").performClick()
        compose.onNodeWithText("【合成示例】读书时想到：先把自己的想法记下来，明天再看看。").assertExists()
    }
}
