package io.github.z3275630ops.floatingnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.z3275630ops.floatingnotes.feature.lab.MaterialLab
import io.github.z3275630ops.floatingnotes.feature.lab.InputLab
import io.github.z3275630ops.floatingnotes.feature.home.NotesShell
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var lab by rememberSaveable { mutableStateOf<Lab?>(null) }
            FloatingNotesTheme {
                BackHandler(enabled = lab != null) { lab = null }
                when (lab) {
                    Lab.Material -> MaterialLab { lab = null }
                    Lab.Input -> InputLab { lab = null }
                    null -> NotesShell {
                        if (BuildConfig.MATERIAL_LAB) {
                            TextButton(onClick = { lab = Lab.Material }) { Text("材质实验") }
                            TextButton(onClick = { lab = Lab.Input }) { Text("输入实验") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingNotesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Color(0xFFB9AEFF), background = Color(0xFF11131B),
            surface = Color(0xFF1A1E2A), onSurface = Color(0xFFEEF1F8)
        ) else lightColorScheme(
            primary = Color(0xFF6356D9), background = Color(0xFFF7F8FC),
            surface = Color.White, onSurface = Color(0xFF202431)
        ), content = content
    )
}

private enum class Lab { Material, Input }
