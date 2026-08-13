package ch.trailer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.trailer.android.api.NetworkModule
import ch.trailer.android.api.TrailRepository
import ch.trailer.android.components.HomeScreen
import ch.trailer.android.ui.theme.TrailrTheme
import ch.trailer.android.viewmodel.TrailViewModel
import ch.trailer.android.viewmodel.TrailViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrailrTheme {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val application = LocalContext.current.applicationContext as TrailrApplication

    val repository = remember {
        TrailRepository(NetworkModule.trailApi, application.database.trailDao())
    }

    val factory = remember {
        TrailViewModelFactory(repository)
    }

    val viewModel: TrailViewModel = viewModel(
        factory = factory
    )

    LaunchedEffect(Unit) {
        viewModel.healthCheck()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        HomeScreen(
            state = viewModel.state,
            onFindTrail = { point, length, elevation ->
                viewModel.findTrail(
                    point = point,
                    length = length,
                    elevation = elevation
                )
            },
            modifier = Modifier.padding(innerPadding),
            onDeleteTrail = { trail -> viewModel.deleteTrail(trail) },
        )
    }
}
