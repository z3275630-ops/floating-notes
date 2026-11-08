package io.github.z3275630ops.floatingnotes

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun DebugSampleAction(onLoad: (List<String>) -> Unit) {
    TextButton(onClick = { onLoad(listOf(
        "【合成示例】读书时想到：先把自己的想法记下来，明天再看看。",
        "【合成示例】周末清单\n□ 整理课堂笔记\n□ 给阳台的植物浇水",
        "【合成示例】一个问题：哪些旧记录值得重新打开？"
    )) }) { Text(stringResource(R.string.load_samples)) }
}
