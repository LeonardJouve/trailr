package ch.trailer.android.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ch.trailer.android.api.TourType
import ch.trailer.android.domain.Basemap
import ch.trailer.android.domain.MapLayers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerPicker(
    sport: TourType,
    onSportChange: (TourType) -> Unit,
    basemap: Basemap,
    onBasemapChange: (Basemap) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .border(1.5.dp, Color.White.copy(alpha = 0.8f), shape)
            .clickable { showSheet = true },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(MapLayers.basemapPreview(basemap)),
            contentDescription = "Map layers (${basemap.label})",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text("Map layers", style = MaterialTheme.typography.headlineSmall)

                Spacer(Modifier.height(24.dp))

                Text("Basemap", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    Basemap.entries.forEachIndexed { index, bm ->
                        SegmentedButton(
                            selected = basemap == bm,
                            onClick = { onBasemapChange(bm) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = Basemap.entries.size
                            )
                        ) {
                            Text(bm.label)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text("Trails", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SportDropdown(sport = sport, onSportChange = onSportChange)

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
