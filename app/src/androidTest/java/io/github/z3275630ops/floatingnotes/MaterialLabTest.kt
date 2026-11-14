package io.github.z3275630ops.floatingnotes

import android.graphics.Bitmap
import android.os.Build
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
        val standardStatus = if (Build.VERSION.SDK_INT >= 31) "背景模糊与边缘高光" else "当前系统使用清晰档"
        val enhancedStatus = when {
            Build.VERSION.SDK_INT >= 33 -> "背景模糊与边缘折射"
            Build.VERSION.SDK_INT >= 31 -> "当前系统使用标准档"
            else -> "当前系统使用清晰档"
        }
        for ((label, status) in listOf(
            "清晰" to "实色与渐变",
            "标准" to standardStatus,
            "增强" to enhancedStatus,
        )) {
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(status).assertIsDisplayed()
            screenshot("glass-$label")
        }
        compose.onNodeWithContentDescription("深色主题").performClick()
        compose.onNodeWithContentDescription("深色主题").assertIsOn()
        screenshot("glass-dark")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(enhancedStatus).assertIsDisplayed()
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
