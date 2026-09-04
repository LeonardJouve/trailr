package ch.trailer.android.components

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import ch.trailer.android.SelectedPoint
import ch.trailer.android.api.TourType
import ch.trailer.android.database.TrailEntity
import ch.trailer.android.domain.parseElevationProfile
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
    isLoading: Boolean = false,
    onOpenList: () -> Unit,
    onClearTrail: () -> Unit,
    onDownloadTrail: () -> Unit,
    onFindTrail: (point: SelectedPoint, type: TourType, length: UInt, elevation: UInt) -> Unit,
) {
    val context = LocalContext.current

    var selectedPoint by remember {
        mutableStateOf<SelectedPoint?>(null)
    }

    var isStyleLoaded by remember {
        mutableStateOf(false)
    }

    var hoveredPoint by remember {
        mutableStateOf<SelectedPoint?>(null)
    }

    var mapViewRef by remember {
        mutableStateOf<MapView?>(null)
    }

    LaunchedEffect(selectedTrail?.id) {
        hoveredPoint = null
    }

    LaunchedEffect(hoveredPoint) {
        hoveredPoint?.let { point ->
            mapViewRef?.getMapAsync { map ->
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(point.latitude, point.longitude))
                            .zoom(map.cameraPosition.zoom.coerceAtLeast(15.0))
                            .build()
                    ),
                    300
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    MapView(context).apply {
                        mapViewRef = this
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

                                style.addSource(GeoJsonSource("trail-cursor"))
                                style.addLayer(
                                    CircleLayer(
                                        "trail-cursor-layer",
                                        "trail-cursor"
                                    ).withProperties(
                                        PropertyFactory.circleRadius(10f),
                                        PropertyFactory.circleColor("#2196F3"),
                                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                                        PropertyFactory.circleStrokeWidth(3f)
                                    )
                                )

                                isStyleLoaded = true
                            }
                        }
                    }
                },
                update = { mapView ->
                    if (!isStyleLoaded) {
                        return@AndroidView
                    }

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

                        val cursorSource = map.style?.getSourceAs<GeoJsonSource>("trail-cursor")
                            ?: return@getMapAsync

                        if (hoveredPoint != null) {
                            cursorSource.setGeoJson(
                                Feature.fromGeometry(
                                    Point.fromLngLat(
                                        hoveredPoint!!.longitude,
                                        hoveredPoint!!.latitude
                                    )
                                )
                            )
                        } else {
                            cursorSource.setGeoJson(
                                FeatureCollection.fromFeatures(emptyArray())
                            )
                        }
                    }
                }
            )

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

        when {
            isLoading -> TrailCardSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            selectedTrail != null -> {
                val trail = selectedTrail
                val profile = remember(trail.geoJSON) {
                    parseElevationProfile(trail.geoJSON)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = trail.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(end = 80.dp)
                            )

                            Row(
                                modifier = Modifier.align(Alignment.TopEnd),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download GPX",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable { onDownloadTrail() }
                                )

                                Spacer(modifier = Modifier.size(8.dp))

                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear trail",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable { onClearTrail() }
                                )
                            }
                        }

                        Text(
                            text = "Length: ${trail.length.toInt()} m",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        Text(
                            text = "Elevation: ${trail.elevation.toInt()} m",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        if (profile.size >= 2) {
                            ElevationGraph(
                                profile = profile,
                                onPointSelected = { point ->
                                    hoveredPoint = point?.let {
                                        SelectedPoint(it.latitude, it.longitude)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .padding(top = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        selectedPoint?.let {
            TrailMenu(
                onDismiss = {
                    selectedPoint = null
                },
                onFindTrail = { type, length, elevation ->
                    val point = selectedPoint!!
                    selectedPoint = null
                    onFindTrail(point, type, length, elevation)
                }
            )
        }
    }
}