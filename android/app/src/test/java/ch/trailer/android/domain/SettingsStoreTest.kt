package ch.trailer.android.domain

import ch.trailer.android.api.TourType
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun `parseBasemap returns enum for stored name`() {
        assertEquals(Basemap.TOPO, SettingsStore.parseBasemap("TOPO"))
        assertEquals(Basemap.SATELLITE, SettingsStore.parseBasemap("SATELLITE"))
    }

    @Test
    fun `parseBasemap falls back to default for missing or corrupt name`() {
        assertEquals(Basemap.SATELLITE, SettingsStore.parseBasemap(null))
        assertEquals(Basemap.SATELLITE, SettingsStore.parseBasemap(""))
        assertEquals(Basemap.SATELLITE, SettingsStore.parseBasemap("not-a-basemap"))
        assertEquals(Basemap.SATELLITE, SettingsStore.parseBasemap("topo"))
    }

    @Test
    fun `parseBasemap round-trips every stored enum name`() {
        Basemap.entries.forEach {
            assertEquals(it, SettingsStore.parseBasemap(it.name))
        }
    }

    @Test
    fun `parseTourType returns enum for stored name`() {
        assertEquals(TourType.BIKE, SettingsStore.parseTourType("BIKE"))
        assertEquals(TourType.SKI, SettingsStore.parseTourType("SKI"))
    }

    @Test
    fun `parseTourType falls back to default for missing or corrupt name`() {
        assertEquals(TourType.HIKING, SettingsStore.parseTourType(null))
        assertEquals(TourType.HIKING, SettingsStore.parseTourType(""))
        assertEquals(TourType.HIKING, SettingsStore.parseTourType("swimming"))
    }

    @Test
    fun `parseTourType round-trips every stored enum name`() {
        TourType.entries.forEach {
            assertEquals(it, SettingsStore.parseTourType(it.name))
        }
    }

    @Test
    fun `target keys are namespaced by sport`() {
        assertEquals("distance_HIKING", SettingsStore.distanceKey(TourType.HIKING))
        assertEquals("elevation_${TourType.MOUNTAIN_BIKE.name}",
            SettingsStore.elevationKey(TourType.MOUNTAIN_BIKE))
    }

    @Test
    fun `targets fall back to defaults when nothing is stored`() {
        val settings = SettingsStore(FakeSharedPreferences())
        assertEquals(SettingsStore.DEFAULT_DISTANCE, settings.targetDistance(TourType.BIKE), 0f)
        assertEquals(SettingsStore.DEFAULT_ELEVATION, settings.targetElevation(TourType.BIKE), 0f)
    }

    @Test
    fun `stored targets round-trip per sport`() {
        val settings = SettingsStore(FakeSharedPreferences())
        settings.setTargets(TourType.RUNNING, 7_500f, 320f)
        assertEquals(7_500f, settings.targetDistance(TourType.RUNNING), 0f)
        assertEquals(320f, settings.targetElevation(TourType.RUNNING), 0f)
    }

    @Test
    fun `targets are isolated between sports`() {
        val settings = SettingsStore(FakeSharedPreferences())
        settings.setTargets(TourType.HIKING, 12_000f, 900f)
        assertEquals(12_000f, settings.targetDistance(TourType.HIKING), 0f)
        assertEquals(SettingsStore.DEFAULT_DISTANCE, settings.targetDistance(TourType.BIKE), 0f)
        assertEquals(SettingsStore.DEFAULT_ELEVATION, settings.targetElevation(TourType.BIKE), 0f)
    }

    @Test
    fun `latest stored target wins for a sport`() {
        val settings = SettingsStore(FakeSharedPreferences())
        settings.setTargets(TourType.SKI, 4_000f, 100f)
        settings.setTargets(TourType.SKI, 6_000f, 250f)
        assertEquals(6_000f, settings.targetDistance(TourType.SKI), 0f)
        assertEquals(250f, settings.targetElevation(TourType.SKI), 0f)
    }

    @Test
    fun `every sport gets its own target slot`() {
        val settings = SettingsStore(FakeSharedPreferences())
        TourType.entries.forEachIndexed { index, sport ->
            settings.setTargets(sport, 1_000f + index, 100f + index)
        }
        TourType.entries.forEachIndexed { index, sport ->
            assertEquals(1_000f + index, settings.targetDistance(sport), 0f)
            assertEquals(100f + index, settings.targetElevation(sport), 0f)
        }
    }
}
