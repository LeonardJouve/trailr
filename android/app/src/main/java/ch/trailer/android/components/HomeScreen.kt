package ch.trailer.android.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ch.trailer.android.Screen
import ch.trailer.android.SelectedPoint
import ch.trailer.android.database.TrailEntity
import ch.trailer.android.util.GpxExporter
import ch.trailer.android.viewmodel.TrailUiState
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    state: TrailUiState,
    onFindTrail: (point: SelectedPoint, length: UInt, elevation: UInt) -> Unit,
    onDeleteTrail: (trail: TrailEntity) -> Unit,
    onSelectTrail: (trail: TrailEntity) -> Unit,
    onClearTrail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val downloadTrail: (TrailEntity) -> Unit = { trail ->
        scope.launch {
            GpxExporter.export(context, trail)
                .onSuccess { file ->
                    Toast.makeText(
                        context,
                        "GPX saved to ${file.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { error ->
                    Toast.makeText(
                        context,
                        "Failed to export GPX: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    val openStorage: () -> Unit = {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            Toast.makeText(
                context,
                "External storage is not available",
                Toast.LENGTH_LONG
            ).show()
        } else {
            try {
                downloadsDir.mkdirs()

                val docId = "primary:Android/data/${context.packageName}/files/Download"
                val docUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    docId
                )
                val docIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, "vnd.android.document/directory")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (docIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(docIntent)
                } else {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        downloadsDir
                    )
                    val folderIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "resource/folder")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    if (folderIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(folderIntent)
                    } else {
                        Toast.makeText(
                            context,
                            "No file manager found. Files are in ${downloadsDir.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Could not open storage: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

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
                onTrailDownload = { trail ->
                    downloadTrail(trail)
                },
                onTrailDelete = { trail ->
                    onDeleteTrail(trail)
                },
                onOpenStorage = {
                    openStorage()
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
                    onDownloadTrail = {
                        state.selectedTrail?.let { downloadTrail(it) }
                    },
                    selectedTrail = state.selectedTrail,
                    isLoading = state.isLoading,
                )
            }
        }
    }
}