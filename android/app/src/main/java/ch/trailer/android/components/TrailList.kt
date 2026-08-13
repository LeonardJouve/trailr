package ch.trailer.android.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.trailer.android.database.TrailEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailList(
    trails: List<TrailEntity>,
    onTrailClick: (TrailEntity) -> Unit,
    onTrailDelete: (TrailEntity) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("My Trails")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(
                items = trails,
                key = { it.id }
            ) { trail ->
                TrailItem(
                    trail = trail,
                    onClick = { onTrailClick(trail) },
                    onDelete = { onTrailDelete(trail) }
                )
            }
        }
    }
}