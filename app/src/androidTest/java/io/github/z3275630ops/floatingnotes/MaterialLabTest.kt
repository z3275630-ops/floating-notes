package io.github.z3275630ops.floatingnotes

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import java.io.File

class MaterialLabTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun changeMaterialAndRestoreThemeAfterRecreation() {
        compose.onNodeWithText("材质实验").performClick()
        for ((label, status) in listOf(
            "清晰" to "实色与渐变",
            "标准" to "背景模糊与边缘高光",
            "增强" to "背景模糊与边缘折射",
        )) {
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(status).assertIsDisplayed()
            screenshot("glass-$label")
        }
        compose.onNodeWithContentDescription("深色主题").performClick()
        compose.onNodeWithContentDescription("深色主题").assertIsOn()
        screenshot("glass-dark")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("背景模糊与边缘折射").assertIsDisplayed()
        compose.onNodeWithContentDescription("深色主题").assertIsOn()
        compose.onNodeWithText("返回工程样例").performClick()
        compose.onNodeWithText("载入合成示例").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(compose.activity.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
