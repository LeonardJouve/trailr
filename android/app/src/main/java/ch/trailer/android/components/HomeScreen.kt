package ch.trailer.android.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import ch.trailer.android.SelectedPoint
import ch.trailer.android.viewmodel.TrailUiState

@Composable
fun HomeScreen(
    state: TrailUiState,
    onFindTrail: (point: SelectedPoint, length: UInt, elevation: UInt) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hasLocationPermission by remember {
        mutableStateOf(false)
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasLocationPermission =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        }

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val fine =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        hasLocationPermission = fine || coarse

        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    var selectedPoint by remember {
        mutableStateOf<SelectedPoint?>(null)
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (hasLocationPermission) {
            TrailMap(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                onPointSelected = { point ->
                    selectedPoint = point
                },
                selectedPoint = selectedPoint,
                trail = state.trail?.geoJSON,
            )

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
}