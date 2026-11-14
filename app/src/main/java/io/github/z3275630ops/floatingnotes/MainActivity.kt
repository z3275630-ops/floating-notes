package io.github.z3275630ops.floatingnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.z3275630ops.floatingnotes.feature.lab.MaterialLab
import io.github.z3275630ops.floatingnotes.feature.lab.InputLab
import io.github.z3275630ops.floatingnotes.feature.home.NotesShell
import io.github.z3275630ops.floatingnotes.feature.capture.NotesViewModel
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme

class MainActivity : ComponentActivity() {
    private val notes by viewModels<NotesViewModel>()

    override fun onStart() {
        super.onStart()
        notes.onForeground()
    }

    override fun onStop() {
        notes.checkpointOnStop()
        super.onStop()
    }

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
                    null -> NotesShell(notes) {
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
    MaterialTheme(colorScheme = mintColorScheme(dark), content = content)
}

private enum class Lab { Material, Input }
