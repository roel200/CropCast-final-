package com.cropcast.app.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationTest {
    @Test
    fun `hiligaynon translates core navigation and settings labels`() {
        assertEquals("Balay", localize("Home", "Hiligaynon"))
        assertEquals("Kahandaan sang binulan nga datos", localize("Monthly data readiness", "Hiligaynon"))
        assertEquals("Mga Setting", localize("Settings", "Hiligaynon"))
        assertEquals("Rekomendasyon sang Tanom", localize("Crop Recommendation", "Hiligaynon"))
    }

    @Test
    fun `english and unknown text remain unchanged`() {
        assertEquals("Settings", localize("Settings", "English"))
        assertEquals("CropCast", localize("CropCast", "Hiligaynon"))
    }
}
