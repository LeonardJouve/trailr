package ch.trailer.android.util

import android.content.Context
import android.os.Environment
import ch.trailer.android.api.GeoJsonFeature
import ch.trailer.android.database.TrailEntity
import kotlinx.serialization.json.Json
import java.io.File

object GpxExporter {

    fun export(context: Context, trail: TrailEntity): Result<File> {
        return try {
            val gpx = buildGpx(trail)
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val safeName = trail.name.replace(Regex("[^A-Za-z0-9]"), "_").take(64)
            val file = File(downloadsDir, "$safeName.gpx")
            file.writeText(gpx)
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun buildGpx(trail: TrailEntity): String {
        val feature = Json.decodeFromString<GeoJsonFeature>(trail.geoJSON)
        val points = feature.geometry.coordinates.flatten()

        val trackPoints = buildString {
            for (point in points) {
                val lon = point[0]
                val lat = point[1]
                val ele = point.getOrNull(2)
                append("    <trkpt lat=\"$lat\" lon=\"$lon\">\n")
                if (ele != null) {
                    append("      <ele>$ele</ele>\n")
                }
                append("    </trkpt>\n")
            }
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="trailr" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${escapeXml(trail.name)}</name>
    <trkseg>
$trackPoints    </trkseg>
  </trk>
</gpx>
""".trimIndent()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
