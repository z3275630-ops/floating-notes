package io.github.z3275630ops.floatingnotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import org.junit.Rule
import org.junit.Test

class InputLabTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun replaceChineseSelectionAndUndoAcrossKeyboardDismissal() {
        compose.onNodeWithText("输入实验").performClick()
        val field = compose.onNodeWithTag("input-lab-editor")
        field.performClick()
        field.performTextInput("记录一段中文")
        field.performTextInputSelection(TextRange(2, 4))
        field.performTextInput("两段")
        field.assertTextEquals("记录两段中文")
        compose.onNodeWithText("收起键盘").performClick()
        compose.onNodeWithText("撤销").performClick()
        field.assertTextEquals("记录一段中文")
        compose.onNodeWithText("重做").performClick()
        field.assertTextEquals("记录两段中文")
        field.performClick()
        field.performTextInputSelection(TextRange(0, 2))
        compose.activityRule.scenario.recreate()
        field.assertTextEquals("记录两段中文")
        compose.onNodeWithText("6 字符 · 选区 0–2").assertIsDisplayed()
    }

    @Test fun restoreThirtyThousandCharacters() {
        compose.onNodeWithText("输入实验").performClick()
        compose.onNodeWithText("填入三万字").performClick()
        compose.onNodeWithText("30000 字符", substring = true).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("30000 字符", substring = true).assertIsDisplayed()
    }
}
