package com.igloo.blindpenguincoder.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-authored glyphs on a 24x24 grid — the app deliberately carries no icon dependency.
 * Every icon is a white fill; call sites size them with [com.igloo.blindpenguincoder.core.design.IglooTheme.icons]
 * and tint them with a ColorFilter, so the vectors stay theme-agnostic.
 */
object IglooIcons {

    val Search: ImageVector by lazy {
        icon("Search") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                circle(10.5f, 10.5f, 6.5f)
                circle(10.5f, 10.5f, 4.3f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(15.2f, 16.8f)
                lineTo(16.8f, 15.2f)
                lineTo(21.5f, 19.9f)
                lineTo(19.9f, 21.5f)
                close()
            }
        }
    }

    val Home: ImageVector by lazy {
        icon("Home") {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 3f)
                lineTo(21f, 10.4f)
                lineTo(21f, 21f)
                lineTo(14.4f, 21f)
                lineTo(14.4f, 14.4f)
                lineTo(9.6f, 14.4f)
                lineTo(9.6f, 21f)
                lineTo(3f, 21f)
                lineTo(3f, 10.4f)
                close()
            }
        }
    }

    val Movies: ImageVector by lazy {
        icon("Movies") {
            // Film strip: outer frame with the screen window and sprocket holes cut out.
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 4f)
                lineTo(21f, 4f)
                lineTo(21f, 20f)
                lineTo(3f, 20f)
                close()
                moveTo(8.2f, 6.2f)
                lineTo(15.8f, 6.2f)
                lineTo(15.8f, 17.8f)
                lineTo(8.2f, 17.8f)
                close()
                sprocket(4.6f, 5.6f)
                sprocket(4.6f, 10.9f)
                sprocket(4.6f, 16.2f)
                sprocket(17.6f, 5.6f)
                sprocket(17.6f, 10.9f)
                sprocket(17.6f, 16.2f)
            }
        }
    }

    val Star: ImageVector by lazy {
        icon("Star") {
            // Five-point star: outer radius 9.5, inner 4, centered on the 24-grid.
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 2.5f)
                lineTo(14.4f, 8.8f)
                lineTo(21f, 9.1f)
                lineTo(15.8f, 13.2f)
                lineTo(17.6f, 19.7f)
                lineTo(12f, 16f)
                lineTo(6.4f, 19.7f)
                lineTo(8.2f, 13.2f)
                lineTo(3f, 9.1f)
                lineTo(9.6f, 8.8f)
                close()
            }
        }
    }

    val TvShows: ImageVector by lazy {
        icon("TvShows") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                moveTo(2f, 4.5f)
                lineTo(22f, 4.5f)
                lineTo(22f, 17f)
                lineTo(2f, 17f)
                close()
                moveTo(4.2f, 6.7f)
                lineTo(19.8f, 6.7f)
                lineTo(19.8f, 14.8f)
                lineTo(4.2f, 14.8f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(10.8f, 17f)
                lineTo(13.2f, 17f)
                lineTo(13.2f, 19f)
                lineTo(17f, 19f)
                lineTo(17f, 21f)
                lineTo(7f, 21f)
                lineTo(7f, 19f)
                lineTo(10.8f, 19f)
                close()
            }
        }
    }

    val Music: ImageVector by lazy {
        icon("Music") {
            path(fill = SolidColor(Color.White)) {
                // Two beamed eighth notes.
                circle(7.1f, 17.3f, 2.6f)
                circle(16.9f, 15.3f, 2.6f)
                moveTo(8.2f, 17.3f)
                lineTo(8.2f, 4.4f)
                lineTo(19.5f, 2.4f)
                lineTo(19.5f, 15.3f)
                lineTo(18.1f, 15.3f)
                lineTo(18.1f, 6.4f)
                lineTo(9.6f, 7.9f)
                lineTo(9.6f, 17.3f)
                close()
            }
        }
    }

    val Photos: ImageVector by lazy {
        icon("Photos") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 4f)
                lineTo(21f, 4f)
                lineTo(21f, 20f)
                lineTo(3f, 20f)
                close()
                moveTo(5f, 6f)
                lineTo(19f, 6f)
                lineTo(19f, 18f)
                lineTo(5f, 18f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(6.4f, 16.6f)
                lineTo(10.2f, 10.8f)
                lineTo(12.8f, 14.2f)
                lineTo(14.6f, 12f)
                lineTo(17.6f, 16.6f)
                close()
                circle(9.2f, 8.9f, 1.5f)
            }
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                gear(cx = 12f, cy = 12f, teeth = 8, outer = 10.2f, inner = 7.7f)
                circle(12f, 12f, 3.4f)
            }
        }
    }

    val SwitchProfile: ImageVector by lazy {
        icon("SwitchProfile") {
            path(fill = SolidColor(Color.White)) {
                // Companion profile behind, primary profile in front.
                circle(16.4f, 8.3f, 2.7f)
                moveTo(15.2f, 12.6f)
                curveTo(18.9f, 12.9f, 21.4f, 15f, 21.4f, 18.6f)
                lineTo(21.4f, 19.5f)
                lineTo(16.9f, 19.5f)
                lineTo(16.9f, 18.2f)
                curveTo(16.9f, 15.8f, 16.3f, 13.9f, 15.2f, 12.6f)
                close()
                circle(9f, 7.6f, 3.3f)
                moveTo(9f, 13.1f)
                curveTo(13.1f, 13.1f, 15.4f, 15.3f, 15.4f, 18.9f)
                lineTo(15.4f, 19.9f)
                lineTo(2.6f, 19.9f)
                lineTo(2.6f, 18.9f)
                curveTo(2.6f, 15.3f, 4.9f, 13.1f, 9f, 13.1f)
                close()
            }
        }
    }

    val SignOut: ImageVector by lazy {
        icon("SignOut") {
            path(fill = SolidColor(Color.White)) {
                // Door frame open toward the exit arrow.
                moveTo(4f, 3f)
                lineTo(13f, 3f)
                lineTo(13f, 5.2f)
                lineTo(6.2f, 5.2f)
                lineTo(6.2f, 18.8f)
                lineTo(13f, 18.8f)
                lineTo(13f, 21f)
                lineTo(4f, 21f)
                close()
                moveTo(10.5f, 10.9f)
                lineTo(17.5f, 10.9f)
                lineTo(17.5f, 7.8f)
                lineTo(21.7f, 12f)
                lineTo(17.5f, 16.2f)
                lineTo(17.5f, 13.1f)
                lineTo(10.5f, 13.1f)
                close()
            }
        }
    }

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "Igloo.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.sprocket(x: Float, y: Float) {
        moveTo(x, y)
        lineTo(x + 1.8f, y)
        lineTo(x + 1.8f, y + 2.2f)
        lineTo(x, y + 2.2f)
        close()
    }

    private fun PathBuilder.gear(cx: Float, cy: Float, teeth: Int, outer: Float, inner: Float) {
        val step = 360f / teeth
        repeat(teeth) { tooth ->
            val base = tooth * step
            val corners = listOf(base - 14f to inner, base - 8f to outer, base + 8f to outer, base + 14f to inner)
            corners.forEachIndexed { index, (angle, radius) ->
                val radians = Math.toRadians(angle.toDouble())
                val x = cx + radius * cos(radians).toFloat()
                val y = cy + radius * sin(radians).toFloat()
                if (tooth == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        close()
    }
}
