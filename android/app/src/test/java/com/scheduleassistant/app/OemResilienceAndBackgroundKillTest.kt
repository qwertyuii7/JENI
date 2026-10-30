package com.scheduleassistant.app

import com.scheduleassistant.app.util.OemBrand
import com.scheduleassistant.app.util.OemHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OEM Background Protection & Device Compatibility Test Suite:
 *
 * Verifies that the app handles background task killers, Autostart managers,
 * and restricted settings gracefully across all major smartphone makers in India.
 */
class OemResilienceAndBackgroundKillTest {

    @Test
    fun testOemBrandClassificationAcrossIndianSmartphoneFleet() {
        val testFleet = listOf(
            // OPPO devices
            Triple("OPPO", "CPH2269", OemBrand.OPPO),       // OPPO A16
            Triple("oppo", "CPH2505", OemBrand.OPPO),       // OPPO Reno 11
            Triple("OPPO", "Find X6 Pro", OemBrand.OPPO),

            // Realme devices
            Triple("realme", "RMX3241", OemBrand.REALME),   // Realme 8 5G
            Triple("Realme", "RMX3706", OemBrand.REALME),   // Realme 11 Pro
            Triple("realme", "Narzo 60", OemBrand.REALME),

            // Xiaomi, Redmi, POCO
            Triple("Xiaomi", "23129RAA4G", OemBrand.XIAOMI), // Redmi Note 13
            Triple("Redmi", "Redmi Note 12", OemBrand.XIAOMI),
            Triple("POCO", "Poco X6 Pro", OemBrand.XIAOMI),

            // Vivo, iQOO
            Triple("vivo", "V2314", OemBrand.VIVO),          // Vivo V30
            Triple("vivo", "V2120", OemBrand.VIVO),          // Vivo Y21
            Triple("iQOO", "I2208", OemBrand.VIVO),          // iQOO Neo 7

            // Samsung
            Triple("samsung", "SM-A156B", OemBrand.SAMSUNG), // Galaxy A15 5G (Our test device)
            Triple("Samsung", "SM-S928B", OemBrand.SAMSUNG), // Galaxy S24 Ultra
            Triple("samsung", "SM-M346B", OemBrand.SAMSUNG), // Galaxy M34

            // OnePlus
            Triple("OnePlus", "CPH2493", OemBrand.ONEPLUS),  // OnePlus Nord CE 3
            Triple("oneplus", "CPH2609", OemBrand.ONEPLUS),  // OnePlus 12R

            // Stock / Other
            Triple("Google", "Pixel 8", OemBrand.OTHER),
            Triple("Motorola", "moto g84 5G", OemBrand.OTHER),
            Triple("Nothing", "A065", OemBrand.OTHER)
        )

        for ((mfr, model, expectedBrand) in testFleet) {
            val resolved = OemHelper.resolveBrand(mfr, model)
            assertEquals("Device $mfr ($model) should resolve to $expectedBrand", expectedBrand, resolved)
        }
    }

    @Test
    fun testOemGuidanceIncludesManufacturerSpecificInstructions() {
        val guidance = OemHelper.getOemGuidance(isRestrictedSettingsNeeded = true)
        assertNotNull(guidance)
        assertTrue(guidance.isNotEmpty())

        // Android 13+ restricted settings step
        assertTrue(guidance.any { it.contains("Restricted setting") && it.contains("3 dots") })
    }

    @Test
    fun testAggressiveOemDetection() {
        val aggressiveMfrs = listOf("oppo", "realme", "xiaomi", "redmi", "poco", "vivo", "iqoo", "oneplus", "samsung")
        for (mfr in aggressiveMfrs) {
            val brand = OemHelper.resolveBrand(mfr, "test-model")
            assertTrue("Manufacturer '$mfr' should be classified as aggressive OEM", brand != OemBrand.OTHER)
        }

        val nonAggressive = listOf("google", "motorola", "hmd", "essential")
        for (mfr in nonAggressive) {
            val brand = OemHelper.resolveBrand(mfr, "test-model")
            assertEquals("Manufacturer '$mfr' should be OTHER", OemBrand.OTHER, brand)
        }
    }
}
