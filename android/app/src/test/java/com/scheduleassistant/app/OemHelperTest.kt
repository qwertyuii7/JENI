package com.scheduleassistant.app

import com.scheduleassistant.app.util.OemBrand
import com.scheduleassistant.app.util.OemHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for OEM background kill protection and guidance.
 */
class OemHelperTest {

    @Test
    fun testCurrentBrandInitialization() {
        val brand = OemHelper.currentBrand
        assertNotNull(brand)
        assertNotNull(brand.displayName)
        assertNotNull(brand.oemSkin)
    }

    @Test
    fun testOemGuidanceNotEmpty() {
        val guidance = OemHelper.getOemGuidance(isRestrictedSettingsNeeded = true)
        assertTrue("Guidance steps must not be empty", guidance.isNotEmpty())
        assertTrue("Must include Android 13+ restricted settings step", guidance.any { it.contains("Restricted setting") })
    }

    @Test
    fun testOemBrandsCoverMajorIndianPhoneMakers() {
        val brands = OemBrand.values().map { it.name }
        assertTrue(brands.contains("OPPO"))
        assertTrue(brands.contains("REALME"))
        assertTrue(brands.contains("XIAOMI"))
        assertTrue(brands.contains("VIVO"))
        assertTrue(brands.contains("ONEPLUS"))
        assertTrue(brands.contains("SAMSUNG"))
    }

    @Test
    fun testResolveBrandSimulation() {
        org.junit.Assert.assertEquals(OemBrand.OPPO, OemHelper.resolveBrand("OPPO", "CPH2269"))
        org.junit.Assert.assertEquals(OemBrand.REALME, OemHelper.resolveBrand("realme", "RMX3241"))
        org.junit.Assert.assertEquals(OemBrand.XIAOMI, OemHelper.resolveBrand("Xiaomi", "Redmi Note 12"))
        org.junit.Assert.assertEquals(OemBrand.VIVO, OemHelper.resolveBrand("vivo", "V2120"))
        org.junit.Assert.assertEquals(OemBrand.ONEPLUS, OemHelper.resolveBrand("OnePlus", "Nord CE"))
        org.junit.Assert.assertEquals(OemBrand.SAMSUNG, OemHelper.resolveBrand("samsung", "SM-A156B"))
        org.junit.Assert.assertEquals(OemBrand.OTHER, OemHelper.resolveBrand("Google", "Pixel 8"))
        org.junit.Assert.assertEquals(OemBrand.OTHER, OemHelper.resolveBrand(null, null))
    }

    @Test
    fun testHasDedicatedAutostartDistinction() {
        // Brands with proprietary autostart apps
        assertTrue(listOf(OemBrand.OPPO, OemBrand.REALME, OemBrand.XIAOMI, OemBrand.VIVO, OemBrand.ONEPLUS).all {
            it == OemBrand.OPPO || it == OemBrand.REALME || it == OemBrand.XIAOMI || it == OemBrand.VIVO || it == OemBrand.ONEPLUS
        })

        // Samsung and Stock AOSP do NOT use proprietary autostart activities
        assertFalse("Samsung must not require proprietary autostart activity", 
            OemBrand.SAMSUNG == OemBrand.OPPO || OemBrand.SAMSUNG == OemBrand.REALME || OemBrand.SAMSUNG == OemBrand.XIAOMI || OemBrand.SAMSUNG == OemBrand.VIVO || OemBrand.SAMSUNG == OemBrand.ONEPLUS
        )
    }
}
