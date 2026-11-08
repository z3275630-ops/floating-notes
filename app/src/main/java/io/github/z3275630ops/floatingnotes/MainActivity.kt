package io.github.z3275630ops.floatingnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FloatingNotesTheme { BootstrapScreen() } }
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

@Composable
private fun BootstrapScreen() {
    var samples by remember { mutableStateOf(emptyList<String>()) }
    val dark = isSystemInDarkTheme()
    val background = if (dark) listOf(Color(0xFF28233F), Color(0xFF11131B))
        else listOf(Color(0xFFE4DCFF), Color(0xFFD9ECFF), Color(0xFFF7F8FC))
    Surface {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(background))
            .safeDrawingPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(32.dp))
            Text("浮记", style = MaterialTheme.typography.headlineLarge)
            Text("留住一闪而过的想法", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.prototype_notice), style = MaterialTheme.typography.labelLarge)
            DebugSampleAction { samples = it }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 20.dp)) {
                items(samples) { text ->
                    Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                        Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
