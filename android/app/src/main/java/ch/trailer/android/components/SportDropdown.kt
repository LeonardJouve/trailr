package ch.trailer.android.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ch.trailer.android.api.TourType
import ch.trailer.android.domain.MapLayers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SportDropdown(
    sport: TourType,
    onSportChange: (TourType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = sport.label,
            onValueChange = {},
            readOnly = true,
            leadingIcon = { Icon(MapLayers.sportIcon(sport), contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TourType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.label) },
                    leadingIcon = { Icon(MapLayers.sportIcon(type), contentDescription = null) },
                    onClick = {
                        onSportChange(type)
                        expanded = false
                    }
                )
            }
        }
    }
}
