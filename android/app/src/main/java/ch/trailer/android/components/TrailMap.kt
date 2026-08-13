package ch.trailer.android.components

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ch.trailer.android.SelectedPoint
import ch.trailer.android.database.TrailEntity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
@SuppressLint("MissingPermission")
@Composable
fun TrailMap(
    modifier: Modifier = Modifier,
    selectedTrail: TrailEntity? = null,
    onOpenList: () -> Unit,
    onClearTrail: () -> Unit,
    onFindTrail: (point: SelectedPoint, length: UInt, elevation: UInt) -> Unit,
) {
    val context = LocalContext.current

    var selectedPoint by remember {
        mutableStateOf<SelectedPoint?>(null)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    MapView(context).apply {
                        getMapAsync { map ->
                            map.setStyle("asset://swisstopo.json") { style ->
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
                                    selectedPoint = SelectedPoint(point.latitude, point.longitude)
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

                                style.addSource(
                                    GeoJsonSource(
                                        "trail-source",
                                        FeatureCollection.fromFeatures(emptyArray())
                                    )
                                )
                                style.addLayer(
                                    LineLayer("trail-layer", "trail-source").withProperties(
                                        PropertyFactory.lineWidth(5f),
                                        PropertyFactory.lineColor("#FF0000"),
                                        PropertyFactory.lineOpacity(1f)
                                    )
                                )
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
                                Feature.fromGeometry(
                                    Point.fromLngLat(
                                        selectedPoint!!.longitude,
                                        selectedPoint!!.latitude
                                    )
                                )
                            )
                        } else {
                            selectedPointSource.setGeoJson(
                                FeatureCollection.fromFeatures(emptyArray())
                            )
                        }

                        val trailSource = map.style?.getSourceAs<GeoJsonSource>("trail-source")
                            ?: return@getMapAsync

                        if (selectedTrail != null) {
                            trailSource.setGeoJson(selectedTrail.geoJSON)

                            // Center camera on the selected trail
                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(LatLng(selectedTrail.latitude, selectedTrail.longitude))
                                        .zoom(15.0)
                                        .build()
                                ),
                                1000
                            )
                        } else {
                            trailSource.setGeoJson(
                                FeatureCollection.fromFeatures(emptyArray())
                            )
                        }
                    }
                }
            )

            selectedTrail?.let { trail ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = trail.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Length: ${(trail.length).toInt()} m",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        Text(
                            text = "Elevation: ${trail.elevation.toInt()} m",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear trail",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .clickable { onClearTrail() }
                    )
                }
            }

            androidx.compose.material3.FloatingActionButton(
                onClick = onOpenList,
                containerColor = Color(0xFF2196F3),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 60.dp),
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = "My Trails",
                )
            }

        }

        selectedPoint?.let {
            TrailMenu(
                onDismiss = {
                    selectedPoint = null
                },
                onFindTrail = { length, elevation ->
                    onFindTrail(selectedPoint!!, length, elevation)
                }
            )
        }
    }
}