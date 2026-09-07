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
}
