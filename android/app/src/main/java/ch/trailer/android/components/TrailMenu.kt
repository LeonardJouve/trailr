package ch.trailer.android.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailMenu(
    onFindTrail: (length: UInt, elevation: UInt) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        var targetLength by remember {
            mutableFloatStateOf(10_000f)
        }

        var targetElevation by remember {
            mutableFloatStateOf(500f)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = "Find a trail",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(24.dp))

            Text(
                "Target distance: ${targetLength.toInt()} m"
            )

            Slider(
                value = targetLength,
                onValueChange = {
                    targetLength = it
                },
                valueRange = 500f..25_000f,
                steps = 100
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Target elevation: ${targetElevation.toInt()} m"
            )

            Slider(
                value = targetElevation,
                onValueChange = {
                    targetElevation = it
                },
                valueRange = 0f..2_000f,
                steps = 50
            )

            Spacer(Modifier.height(24.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onFindTrail(targetLength.toUInt(), targetElevation.toUInt())
                }
            ) {
                Text("Find trail")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}