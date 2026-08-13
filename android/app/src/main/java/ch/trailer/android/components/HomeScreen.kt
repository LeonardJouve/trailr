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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ch.trailer.android.Screen
import ch.trailer.android.SelectedPoint
import ch.trailer.android.database.TrailEntity
import ch.trailer.android.viewmodel.TrailUiState

@Composable
fun HomeScreen(
    state: TrailUiState,
    onFindTrail: (point: SelectedPoint, length: UInt, elevation: UInt) -> Unit,
    onDeleteTrail: (trail: TrailEntity) -> Unit,
    onSelectTrail: (trail: TrailEntity) -> Unit,
    onClearTrail: () -> Unit,
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

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Map.route
    ) {
        composable(Screen.Trails.route) {
            TrailList(
                trails = state.savedTrails,
                onTrailClick = { trail ->
                    onSelectTrail(trail)
                    navController.navigate(Screen.Map.route)
                },
                onTrailDelete = { trail ->
                    onDeleteTrail(trail)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Map.route) {
            if (hasLocationPermission) {
                TrailMap(
                    onOpenList = {
                        onClearTrail()
                        navController.navigate(Screen.Trails.route)
                    },
                    onClearTrail = {
                        onClearTrail()
                    },
                    onFindTrail = { point, length, elevation ->
                        onFindTrail(
                            point,
                            length,
                            elevation
                        )
                    },
                    selectedTrail = state.selectedTrail,
                )
            }
        }
    }
}