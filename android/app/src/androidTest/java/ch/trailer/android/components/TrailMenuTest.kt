package ch.trailer.android.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.trailer.android.api.TourType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrailMenuTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun menuOpensOnTheSportPassedByTheCaller() {
        compose.setContent {
            TrailMenu(
                sport = TourType.SKI,
                onSportChange = {},
                onFindTrail = { _, _, _ -> },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("Ski").assertExists()
        compose.onNodeWithText("Find ski").assertExists()
    }

    @Test
    fun changingTheSportIsReportedToTheCaller() {
        var reported: TourType? = null

        compose.setContent {
            var sport by remember { mutableStateOf(TourType.HIKING) }
            TrailMenu(
                sport = sport,
                onSportChange = {
                    reported = it
                    sport = it
                },
                onFindTrail = { _, _, _ -> },
                onDismiss = {},
            )
        }

        // Open the dropdown (anchor currently shows the caller's sport) and pick Bike.
        compose.onNodeWithText("Hike").performClick()
        compose.onNodeWithText("Bike").performClick()

        assertEquals(TourType.BIKE, reported)
        compose.onNodeWithText("Find bike").assertExists()
    }
}
