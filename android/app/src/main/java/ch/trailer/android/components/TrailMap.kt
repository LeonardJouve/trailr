package ch.trailer.android.components

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ch.trailer.android.SelectedPoint
import ch.trailer.android.api.GeoJsonFeature
import kotlinx.serialization.json.Json
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

val style = Style.Builder().fromJson("""
{
  "version": 8,
  "sources": {
    "swisstopo": {
      "type": "raster",
      "tiles": [
        "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/{z}/{x}/{y}.jpeg"
      ],
      "tileSize": 256
    }
  },
  "layers": [
    {
      "id": "swisstopo",
      "type": "raster",
      "source": "swisstopo"
    }
  ]
}
""".trimIndent())

@SuppressLint("MissingPermission")
@Composable
fun TrailMap(
    modifier: Modifier = Modifier,
    onPointSelected: (SelectedPoint) -> Unit,
    selectedPoint: SelectedPoint?,
    trail: GeoJsonFeature?,
) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = {
            MapView(context).apply {
                getMapAsync { map ->
                    map.setStyle(style) { style ->
                        println("SwissTopo style loaded")

                        val locationComponent = map.locationComponent

                        locationComponent.activateLocationComponent(
                            LocationComponentActivationOptions
                                .builder(context, style)
                                .build()
                        )

                        locationComponent.isLocationComponentEnabled = true
                        locationComponent.renderMode = RenderMode.COMPASS

                        map.setMinZoomPreference(8.0)
                        map.setMaxZoomPreference(20.0)
                        map.setLatLngBoundsForCameraTarget(
                            LatLngBounds.Builder()
                                .include(LatLng(45.50, 5.00))
                                .include(LatLng(48.00, 11.00))
                                .build()
                        )

                        map.cameraPosition = CameraPosition.Builder()
                            .target(locationComponent.lastKnownLocation?.let {
                                LatLng(it.latitude, it.longitude)
                            })
                            .zoom(15.0)
                            .build()

                        locationComponent.cameraMode = CameraMode.TRACKING

                        map.addOnMapClickListener { point ->
                            onPointSelected(SelectedPoint(point.latitude, point.longitude))
                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(LatLng(point.latitude, point.longitude))
                                        .zoom(15.0)
                                        .build()
                                ),
                                1000
                            )
                            true
                        }

                        val source = GeoJsonSource("selected-point")
                        style.addSource(source)

                        style.addLayer(
                            CircleLayer(
                                "selected-point-layer",
                                "selected-point"
                            ).withProperties(
                                PropertyFactory.circleRadius(8f),
                                PropertyFactory.circleColor("#FF0000"),
                                PropertyFactory.circleStrokeColor("#FFFFFF"),
                                PropertyFactory.circleStrokeWidth(2f)
                            )
                        )

                        style.addSource(GeoJsonSource("trail-source", FeatureCollection.fromFeatures(emptyArray())))
                        style.addLayer(LineLayer("trail-layer", "trail-source").withProperties(
                            PropertyFactory.lineWidth(5f),
                            PropertyFactory.lineColor("#FF0000"),
                            PropertyFactory.lineOpacity(1f)
                        ))
                    }
                }
            }
        },
        update = { mapView ->
            mapView.getMapAsync { map ->
                val selectedPointSource = map.style
                    ?.getSourceAs<GeoJsonSource>("selected-point")
                    ?: return@getMapAsync

                if (selectedPoint != null) {
                    selectedPointSource.setGeoJson(
                        Feature.fromGeometry(Point.fromLngLat(selectedPoint.longitude,selectedPoint.latitude))
                    )
                } else {
                    selectedPointSource.setGeoJson(
                        FeatureCollection.fromFeatures(emptyArray())
                    )
                }

                val trailSource = map.style?.getSourceAs<GeoJsonSource>("trail-source")
                    ?: return@getMapAsync

                if (trail != null) {
                    trailSource.setGeoJson(Json.encodeToString(trail))
                } else {
                    trailSource.setGeoJson(
                        FeatureCollection.fromFeatures(emptyArray())
                    )
                }
            }
        }
    )
}