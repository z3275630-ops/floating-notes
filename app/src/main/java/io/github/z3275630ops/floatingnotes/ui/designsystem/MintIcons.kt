package io.github.z3275630ops.floatingnotes.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom crisp linear vector icons aligned with Apple Notes & Mint Glass aesthetics.
 * Built without material-icons-extended so APK size stays minimal and strokes stay uniform.
 */
internal object MintIcons {
    val Menu: ImageVector by lazy {
        strokeIcon("MintMenu") {
            moveTo(4.5f, 7f); horizontalLineTo(19.5f)
            moveTo(4.5f, 12f); horizontalLineTo(19.5f)
            moveTo(4.5f, 17f); horizontalLineTo(14.5f)
        }
    }

    val Back: ImageVector by lazy {
        strokeIcon("MintBack") {
            moveTo(15f, 5.5f); lineTo(8.5f, 12f); lineTo(15f, 18.5f)
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("MintClose") {
            moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
            moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
        }
    }

    val More: ImageVector by lazy {
        fillIcon("MintMore") {
            circle(6f, 12f, 1.65f)
            circle(12f, 12f, 1.65f)
            circle(18f, 12f, 1.65f)
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("MintPlus") {
            moveTo(12f, 5f); verticalLineTo(19f)
            moveTo(5f, 12f); horizontalLineTo(19f)
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("MintCheck", strokeWidth = 2.2f) {
            moveTo(5.5f, 12.5f); lineTo(10f, 17f); lineTo(18.5f, 7.5f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("MintChevronRight") {
            moveTo(9.5f, 6.5f); lineTo(15f, 12f); lineTo(9.5f, 17.5f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("MintChevronDown") {
            moveTo(6.5f, 9.5f); lineTo(12f, 15f); lineTo(17.5f, 9.5f)
        }
    }

    val Records: ImageVector by lazy {
        strokeIcon("MintRecords") {
            roundedRect(4.5f, 3.5f, 19.5f, 20.5f, 3f)
            moveTo(8f, 8.5f); horizontalLineTo(16f)
            moveTo(8f, 12.5f); horizontalLineTo(16f)
            moveTo(8f, 16.5f); horizontalLineTo(13f)
        }
    }

    val Explore: ImageVector by lazy {
        strokeIcon("MintExplore") {
            moveTo(12f, 3.5f)
            lineTo(14.1f, 9.9f)
            lineTo(20.5f, 12f)
            lineTo(14.1f, 14.1f)
            lineTo(12f, 20.5f)
            lineTo(9.9f, 14.1f)
            lineTo(3.5f, 12f)
            lineTo(9.9f, 9.9f)
            close()
        }
    }

    val Agent: ImageVector by lazy {
        strokeIcon("MintAgent") {
            roundedRect(4f, 4.5f, 20f, 17.5f, 4.5f)
            moveTo(9.5f, 17.5f); lineTo(7.5f, 20.5f); lineTo(13f, 17.5f)
            moveTo(9f, 10.5f); horizontalLineTo(9.05f)
            moveTo(12f, 10.5f); horizontalLineTo(12.05f)
            moveTo(15f, 10.5f); horizontalLineTo(15.05f)
        }
    }

    val Library: ImageVector by lazy {
        strokeIcon("MintLibrary") {
            roundedRect(4f, 4.5f, 9f, 19.5f, 1.5f)
            roundedRect(10f, 6f, 15f, 19.5f, 1.5f)
            moveTo(16.5f, 5.5f); lineTo(20.5f, 19f)
        }
    }

    val Checklist: ImageVector by lazy {
        strokeIcon("MintChecklist") {
            roundedRect(4f, 4.5f, 10f, 10.5f, 2f)
            moveTo(5.6f, 7.6f); lineTo(7f, 9f); lineTo(8.8f, 6.6f)
            moveTo(13f, 7.5f); horizontalLineTo(20f)
            roundedRect(4f, 13.5f, 10f, 19.5f, 2f)
            moveTo(13f, 16.5f); horizontalLineTo(20f)
        }
    }

    val Pin: ImageVector by lazy {
        strokeIcon("MintPin") {
            moveTo(14.5f, 4f); lineTo(20f, 9.5f)
            moveTo(15.5f, 5f); lineTo(10.5f, 10f); lineTo(6.5f, 10.5f); lineTo(13.5f, 17.5f); lineTo(14f, 13.5f); lineTo(19f, 8.5f)
            moveTo(10f, 14f); lineTo(4.5f, 19.5f)
        }
    }

    val Archive: ImageVector by lazy {
        strokeIcon("MintArchive") {
            roundedRect(3.5f, 4.5f, 20.5f, 8.5f, 1.5f)
            moveTo(5f, 8.5f); verticalLineTo(18f); curveTo(5f, 19.1f, 5.9f, 20f, 7f, 20f)
            horizontalLineTo(17f); curveTo(18.1f, 20f, 19f, 19.1f, 19f, 18f); verticalLineTo(8.5f)
            moveTo(9.5f, 12.5f); horizontalLineTo(14.5f)
        }
    }

    val Folder: ImageVector by lazy {
        strokeIcon("MintFolder") {
            moveTo(3.5f, 7f)
            curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
            horizontalLineTo(9.5f)
            lineTo(11.5f, 7.5f)
            horizontalLineTo(18.5f)
            curveTo(19.6f, 7.5f, 20.5f, 8.4f, 20.5f, 9.5f)
            verticalLineTo(17.5f)
            curveTo(20.5f, 18.6f, 19.6f, 19.5f, 18.5f, 19.5f)
            horizontalLineTo(5.5f)
            curveTo(4.4f, 19.5f, 3.5f, 18.6f, 3.5f, 17.5f)
            close()
        }
    }

    val SmartFolder: ImageVector by lazy {
        strokeIcon("MintSmartFolder") {
            moveTo(3.5f, 7f)
            curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f)
            horizontalLineTo(9.5f)
            lineTo(11.5f, 7.5f)
            horizontalLineTo(18.5f)
            curveTo(19.6f, 7.5f, 20.5f, 8.4f, 20.5f, 9.5f)
            verticalLineTo(17.5f)
            curveTo(20.5f, 18.6f, 19.6f, 19.5f, 18.5f, 19.5f)
            horizontalLineTo(5.5f)
            curveTo(4.4f, 19.5f, 3.5f, 18.6f, 3.5f, 17.5f)
            close()
            moveTo(12f, 10.8f); verticalLineTo(16.2f)
            moveTo(9.3f, 13.5f); horizontalLineTo(14.7f)
        }
    }

    val Tag: ImageVector by lazy {
        strokeIcon("MintTag") {
            moveTo(9.5f, 4.5f); lineTo(7.5f, 19.5f)
            moveTo(16.5f, 4.5f); lineTo(14.5f, 19.5f)
            moveTo(4.5f, 9.5f); horizontalLineTo(19.5f)
            moveTo(3.8f, 14.5f); horizontalLineTo(18.8f)
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("MintSearch") {
            circle(10.5f, 10.5f, 6f)
            moveTo(15f, 15f); lineTo(20f, 20f)
        }
    }

    val Filter: ImageVector by lazy {
        strokeIcon("MintFilter") {
            moveTo(4.5f, 7f); horizontalLineTo(19.5f)
            moveTo(7f, 12f); horizontalLineTo(17f)
            moveTo(10f, 17f); horizontalLineTo(14f)
        }
    }

    val Calendar: ImageVector by lazy {
        strokeIcon("MintCalendar") {
            roundedRect(4f, 5.5f, 20f, 20f, 2.5f)
            moveTo(8f, 3.5f); verticalLineTo(6.5f)
            moveTo(16f, 3.5f); verticalLineTo(6.5f)
            moveTo(4f, 10f); horizontalLineTo(20f)
        }
    }

    val Bell: ImageVector by lazy {
        strokeIcon("MintBell") {
            moveTo(6.5f, 15.5f)
            verticalLineTo(10.5f)
            curveTo(6.5f, 7.2f, 8.9f, 4.5f, 12f, 4.5f)
            curveTo(15.1f, 4.5f, 17.5f, 7.2f, 17.5f, 10.5f)
            verticalLineTo(15.5f)
            lineTo(19.5f, 17.5f)
            horizontalLineTo(4.5f)
            close()
            moveTo(10f, 19.5f); curveTo(10.6f, 20.5f, 13.4f, 20.5f, 14f, 19.5f)
        }
    }

    val Image: ImageVector by lazy {
        strokeIcon("MintImage") {
            roundedRect(4f, 5f, 20f, 19f, 2.5f)
            circle(9f, 10f, 1.4f)
            moveTo(5.5f, 17.5f); lineTo(10.5f, 12.5f); lineTo(13.8f, 15.8f); lineTo(16f, 13.5f); lineTo(19f, 17.5f)
        }
    }

    val Audio: ImageVector by lazy {
        strokeIcon("MintAudio") {
            roundedRect(9f, 4f, 15f, 13f, 3f)
            moveTo(6.5f, 11.5f)
            curveTo(6.5f, 14.8f, 8.9f, 17f, 12f, 17f)
            curveTo(15.1f, 17f, 17.5f, 14.8f, 17.5f, 11.5f)
            moveTo(12f, 17f); verticalLineTo(20.5f)
            moveTo(9f, 20.5f); horizontalLineTo(15f)
        }
    }

    val Video: ImageVector by lazy {
        strokeIcon("MintVideo") {
            roundedRect(3.5f, 6.5f, 15.5f, 17.5f, 2.5f)
            moveTo(15.5f, 10.5f); lineTo(20.5f, 7.5f); verticalLineTo(16.5f); lineTo(15.5f, 13.5f); close()
        }
    }

    val Link: ImageVector by lazy {
        strokeIcon("MintLink") {
            moveTo(10f, 14f); lineTo(14f, 10f)
            moveTo(8.5f, 10f); lineTo(6.5f, 12f); curveTo(4.8f, 13.7f, 4.8f, 16.3f, 6.5f, 18f)
            curveTo(8.2f, 19.7f, 10.8f, 19.7f, 12.5f, 18f); lineTo(14.5f, 16f)
            moveTo(9.5f, 8f); lineTo(11.5f, 6f); curveTo(13.2f, 4.3f, 15.8f, 4.3f, 17.5f, 6f)
            curveTo(19.2f, 7.7f, 19.2f, 10.3f, 17.5f, 12f); lineTo(15.5f, 14f)
        }
    }

    val DevTools: ImageVector by lazy {
        strokeIcon("MintDevTools") {
            moveTo(9f, 4.5f); horizontalLineTo(15f)
            moveTo(10f, 4.5f); verticalLineTo(9.5f); lineTo(5.2f, 17.2f)
            curveTo(4.6f, 18.2f, 5.3f, 19.5f, 6.5f, 19.5f); horizontalLineTo(17.5f)
            curveTo(18.7f, 19.5f, 19.4f, 18.2f, 18.8f, 17.2f); lineTo(14f, 9.5f); verticalLineTo(4.5f)
            moveTo(7.5f, 14.5f); horizontalLineTo(16.5f)
        }
    }

    private fun strokeIcon(
        name: String,
        strokeWidth: Float = 1.85f,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

    private fun fillIcon(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
            pathBuilder = block,
        )
    }.build()

    private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
        moveTo(left + r, top)
        horizontalLineTo(right - r)
        curveTo(right - r * 0.45f, top, right, top + r * 0.45f, right, top + r)
        verticalLineTo(bottom - r)
        curveTo(right, bottom - r * 0.45f, right - r * 0.45f, bottom, right - r, bottom)
        horizontalLineTo(left + r)
        curveTo(left + r * 0.45f, bottom, left, bottom - r * 0.45f, left, bottom - r)
        verticalLineTo(top + r)
        curveTo(left, top + r * 0.45f, left + r * 0.45f, top, left + r, top)
        close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        val k = r * 0.5522847f
        moveTo(cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        close()
    }
}
