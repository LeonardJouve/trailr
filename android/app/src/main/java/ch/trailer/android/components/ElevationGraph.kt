package ch.trailer.android.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ch.trailer.android.domain.ElevationPoint
import kotlin.math.abs

@Composable
fun ElevationGraph(
    profile: List<ElevationPoint>,
    onPointSelected: (ElevationPoint?) -> Unit,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.15f)
    val axisColor = Color.Gray.copy(alpha = 0.5f)
    val cursorColor = MaterialTheme.colorScheme.error
    val textColor = MaterialTheme.colorScheme.onSurface
    val paddingPx = with(LocalDensity.current) { 16.dp.toPx() }
    val labelOffsetPx = with(LocalDensity.current) { 4.dp.toPx() }

    var selectedPoint by remember { mutableStateOf<ElevationPoint?>(null) }

    Canvas(
        modifier = modifier
            .pointerInput(profile, paddingPx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        selectedPoint = findNearestPoint(
                            profile,
                            offset.x,
                            size.width,
                            paddingPx
                        )
                        onPointSelected(selectedPoint)
                    },
                    onDrag = { change, _ ->
                        selectedPoint = findNearestPoint(
                            profile,
                            change.position.x,
                            size.width,
                            paddingPx
                        )
                        onPointSelected(selectedPoint)
                    },
                    onDragEnd = {
                        selectedPoint = null
                        onPointSelected(null)
                    },
                    onDragCancel = {
                        selectedPoint = null
                        onPointSelected(null)
                    }
                )
            }
    ) {
        val textPaint = Paint().apply {
            color = textColor.toArgb()
            textSize = 28f
            isAntiAlias = true
        }

        if (profile.size < 2) {
            drawContext.canvas.nativeCanvas.drawText(
                "No elevation data",
                size.width / 2 - 80f,
                size.height / 2,
                textPaint
            )
            return@Canvas
        }

        val graphLeft = paddingPx + 40f
        val graphTop = paddingPx
        val graphRight = size.width - paddingPx
        val graphBottom = size.height - paddingPx - 30f
        val graphWidth = graphRight - graphLeft
        val graphHeight = graphBottom - graphTop

        val minElevation = profile.minOf { it.elevationMeters }
        val maxElevation = profile.maxOf { it.elevationMeters }
        val rawRange = maxElevation - minElevation
        val (drawMinElevation, drawMaxElevation) = if (rawRange < 1.0) {
            val center = (minElevation + maxElevation) / 2.0
            Pair(center - 0.5, center + 0.5)
        } else {
            Pair(minElevation, maxElevation)
        }
        val elevationRange = (drawMaxElevation - drawMinElevation).coerceAtLeast(1.0)
        val maxDistance = profile.last().distanceMeters.coerceAtLeast(1.0)

        fun x(distance: Double) = graphLeft + (distance / maxDistance * graphWidth).toFloat()
        fun y(elevation: Double) =
            graphBottom - ((elevation - drawMinElevation) / elevationRange * graphHeight).toFloat()

        // Grid lines
        val horizontalGridCount = 4
        for (i in 0..horizontalGridCount) {
            val yLine = graphTop + (graphHeight / horizontalGridCount) * i
            drawLine(
                color = axisColor,
                start = Offset(graphLeft, yLine),
                end = Offset(graphRight, yLine),
                strokeWidth = 1f
            )
        }

        val verticalGridCount = 4
        for (i in 0..verticalGridCount) {
            val xLine = graphLeft + (graphWidth / verticalGridCount) * i
            drawLine(
                color = axisColor,
                start = Offset(xLine, graphTop),
                end = Offset(xLine, graphBottom),
                strokeWidth = 1f
            )
        }

        // Axes
        drawLine(
            color = axisColor,
            start = Offset(graphLeft, graphTop),
            end = Offset(graphLeft, graphBottom),
            strokeWidth = 2f
        )
        drawLine(
            color = axisColor,
            start = Offset(graphLeft, graphBottom),
            end = Offset(graphRight, graphBottom),
            strokeWidth = 2f
        )

        // Profile line
        val path = Path().apply {
            moveTo(x(profile[0].distanceMeters), y(profile[0].elevationMeters))
            for (i in 1 until profile.size) {
                lineTo(x(profile[i].distanceMeters), y(profile[i].elevationMeters))
            }
        }

        // Fill area
        val fillPath = Path().apply {
            addPath(path)
            lineTo(x(profile.last().distanceMeters), graphBottom)
            lineTo(x(profile[0].distanceMeters), graphBottom)
            close()
        }
        drawPath(fillPath, color = fillColor)
        drawPath(path, color = lineColor, style = Stroke(width = 4f))

        // Cursor line
        selectedPoint?.let { point ->
            val cx = x(point.distanceMeters)
            drawLine(
                color = cursorColor,
                start = Offset(cx, graphTop),
                end = Offset(cx, graphBottom),
                strokeWidth = 2f
            )
        }

        // Axis labels
        val nativeCanvas = drawContext.canvas.nativeCanvas

        // Y-axis labels
        nativeCanvas.drawText(
            "${drawMaxElevation.toInt()} m",
            labelOffsetPx,
            graphTop + 20f,
            textPaint
        )
        nativeCanvas.drawText(
            "${drawMinElevation.toInt()} m",
            labelOffsetPx,
            graphBottom,
            textPaint
        )

        // X-axis labels
        nativeCanvas.drawText(
            "0 km",
            graphLeft,
            size.height - labelOffsetPx,
            textPaint
        )
        nativeCanvas.drawText(
            "${(maxDistance / 1000).toInt()} km",
            graphRight - 50f,
            size.height - labelOffsetPx,
            textPaint
        )
    }
}

private fun findNearestPoint(
    profile: List<ElevationPoint>,
    touchX: Float,
    canvasWidth: Int,
    paddingPx: Float
): ElevationPoint? {
    if (profile.isEmpty()) {
        return null
    }

    val graphLeft = paddingPx + 40f
    val graphRight = canvasWidth - paddingPx
    val graphWidth = graphRight - graphLeft
    if (graphWidth <= 0) {
        return profile.first()
    }

    val maxDistance = profile.last().distanceMeters.coerceAtLeast(1.0)
    val ratio = ((touchX - graphLeft) / graphWidth).toDouble().coerceIn(0.0, 1.0)
    val targetDistance = ratio * maxDistance

    return profile.minByOrNull { abs(it.distanceMeters - targetDistance) }
}
