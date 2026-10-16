package com.galaxy.assettrail.driver.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Stroke icons drawn from the design canvas's inline SVGs (24×24 viewBox). */
object AppIcons {
    val Pin = icon("Pin", "M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z", circle(12f, 10f, 2.5f), width = 2f)
    val Check = icon("Check", "M5 12.5l4.5 4.5L19 7.5", width = 2.4f)
    val Truck = icon(
        "Truck",
        "M3 7h11v9H3z",
        "M14 10h4l3 3v3h-7",
        circle(7f, 17f, 2f),
        circle(17f, 17f, 2f),
    )
    val Wallet = icon("Wallet", roundRect(3f, 6f, 18f, 13f, 2f), "M3 10h18", "M16 15h2")
    val Person = icon("Person", circle(12f, 8f, 4f), "M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6")
    val Phone = icon("Phone", roundRect(7f, 2f, 10f, 20f, 2f), "M11 18h2")
    val Clock = icon("Clock", circle(12f, 12f, 9f), "M12 7v5l3 2", width = 2f)
    val Alert = icon("Alert", "M12 3.5l9 16H3z", "M12 10v4.5", "M12 17.4v.2", width = 2f)
    val Camera = icon("Camera", "M4 8h3.5l2-3h5l2 3H20v11H4z", circle(12f, 13f, 3.5f))
    val Tag = icon(
        "Tag",
        "M8.5 8.5a5 5 0 0 1 0 7",
        "M11.5 6a8.5 8.5 0 0 1 0 12",
        "M14.5 3.5a12 12 0 0 1 0 17",
        circle(5.5f, 12f, 1f),
        width = 2f,
    )
    val NoSignal = icon("NoSignal", "M4 20v-3", "M9 20v-6", "M14 20v-4", "M19 20v-9", "M3 4l18 16", width = 2f)
    val Copy = icon("Copy", roundRect(8f, 8f, 12f, 12f, 2f), "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3")
    val ChevronRight = icon("ChevronRight", "M9 6l6 6-6 6", width = 2f)
    val Shield = icon("Shield", "M12 3l8 3v6c0 4.5-3.4 8.2-8 9-4.6-.8-8-4.5-8-9V6z", "M8.5 12l2.5 2.5 4.5-5", width = 1.8f)
    val Flask = icon(
        "Flask",
        "M9 3h6",
        "M10 3v6.5L4.8 18.2A1.8 1.8 0 0 0 6.3 21h11.4a1.8 1.8 0 0 0 1.5-2.8L14 9.5V3",
        "M7.5 15h9",
    )
    val Route = icon("Route", circle(6f, 18f, 2.5f), circle(18f, 6f, 2.5f), "M8.5 18H15a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h6.5")

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
            "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private fun icon(name: String, vararg paths: String, width: Float = 1.8f): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { data ->
            builder.addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
