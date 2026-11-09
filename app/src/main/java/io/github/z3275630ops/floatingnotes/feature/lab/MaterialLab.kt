package io.github.z3275630ops.floatingnotes.feature.lab

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.ui.designsystem.*

@Composable
fun MaterialLab(onBack: () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(systemDark) }
    var tier by rememberSaveable { mutableStateOf(GlassTier.Standard) }
    val backdrop = rememberGlassBackdrop()
    val effective = tier.supportedOn(Build.VERSION.SDK_INT)
    val scheme = mintColorScheme(dark)
    var panelHeight by remember { mutableIntStateOf(0) }
    val bottomInset = with(LocalDensity.current) { panelHeight.toDp() }

    MaterialTheme(colorScheme = scheme) {
        Surface(color = scheme.background) {
            Column(Modifier.fillMaxSize().background(mintAmbient(dark)).safeDrawingPadding()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    TextButton(onClick = onBack) { Text("返回工程样例") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("玻璃材质实验", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        Switch(
                            checked = dark, onCheckedChange = { dark = it },
                            modifier = Modifier.semantics { contentDescription = "深色主题" },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in GlassTier.entries) {
                            FilterChip(
                                selected = tier == option, onClick = { tier = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.fillMaxSize().then(
                        if (effective == GlassTier.Reduced) Modifier else Modifier.glassSource(backdrop)
                    ).background(mintAmbient(dark))) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomInset + 28.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items((1..40).toList(), key = { it }) { index ->
                                Card(
                                    Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                ) {
                                    Column(Modifier.padding(20.dp)) {
                                        Text("合成记录 · $index", color = scheme.primary,
                                            style = MaterialTheme.typography.labelMedium)
                                        Spacer(Modifier.height(8.dp))
                                        Text("读书时想到，写下一句话，给明天的自己留个线索。",
                                            style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }

                    GlassSurface(backdrop, tier, dark,
                        Modifier.align(Alignment.BottomCenter).padding(20.dp)
                            .fillMaxWidth().onSizeChanged { panelHeight = it.height }) {
                        Column(Modifier.padding(16.dp)) {
                            Text("背景随笔记滚动", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    tier != effective -> "当前系统使用${effective.label}档"
                                    effective == GlassTier.Enhanced -> "背景模糊与边缘折射"
                                    effective == GlassTier.Standard -> "背景模糊与边缘高光"
                                    else -> "实色与渐变"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
