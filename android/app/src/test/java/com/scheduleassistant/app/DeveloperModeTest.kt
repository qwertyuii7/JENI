package com.scheduleassistant.app

import android.content.Context
import android.content.SharedPreferences
import com.scheduleassistant.app.data.diagnostics.DeveloperModeManager
import com.scheduleassistant.app.data.diagnostics.DeveloperTapResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class DeveloperModeTest {

    private val memoryPrefs = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        memoryPrefs.clear()
        DeveloperModeManager.resetTapCounter()

        val editorHandler = InvocationHandler { _, method, args ->
            when (method.name) {
                "putBoolean" -> {
                    memoryPrefs[args[0] as String] = args[1] as Boolean
                    mockEditor()
                }
                "apply", "commit" -> null
                else -> null
            }
        }
        val mockEditor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            editorHandler
        ) as SharedPreferences.Editor

        val prefsHandler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getBoolean" -> {
                    val key = args[0] as String
                    val def = args[1] as Boolean
                    memoryPrefs[key] as? Boolean ?: def
                }
                "edit" -> mockEditor
                else -> null
            }
        }
        val mockPrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            prefsHandler
        ) as SharedPreferences

        DeveloperModeManager.prefsOverride = mockPrefs
    }

    @org.junit.After
    fun tearDown() {
        DeveloperModeManager.prefsOverride = null
    }

    private fun mockEditor(): SharedPreferences.Editor {
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putBoolean" -> {
                    memoryPrefs[args[0] as String] = args[1] as Boolean
                    mockEditor()
                }
                "apply", "commit" -> null
                else -> null
            }
        } as SharedPreferences.Editor
    }

    @Test
    fun testFiveTapActivationSequence() {
        assertFalse("Initially developer mode must be disabled", DeveloperModeManager.isDeveloperModeEnabled())

        // Tap 1
        val r1 = DeveloperModeManager.onVersionTap()
        assertEquals(DeveloperTapResult.Silent, r1)
        assertFalse(DeveloperModeManager.isDeveloperModeEnabled())

        // Tap 2
        val r2 = DeveloperModeManager.onVersionTap()
        assertEquals(DeveloperTapResult.Silent, r2)
        assertFalse(DeveloperModeManager.isDeveloperModeEnabled())

        // Tap 3 -> Hint: 2 steps away
        val r3 = DeveloperModeManager.onVersionTap()
        assertTrue("Tap 3 should give Hint with 2 steps remaining", r3 is DeveloperTapResult.Hint && r3.stepsRemaining == 2)
        assertFalse(DeveloperModeManager.isDeveloperModeEnabled())

        // Tap 4 -> Hint: 1 step away
        val r4 = DeveloperModeManager.onVersionTap()
        assertTrue("Tap 4 should give Hint with 1 step remaining", r4 is DeveloperTapResult.Hint && r4.stepsRemaining == 1)
        assertFalse(DeveloperModeManager.isDeveloperModeEnabled())

        // Tap 5 -> Activated!
        val r5 = DeveloperModeManager.onVersionTap()
        assertEquals(DeveloperTapResult.Activated, r5)
        assertTrue("After 5 taps, developer mode must be enabled", DeveloperModeManager.isDeveloperModeEnabled())

        // Subsequent tap returns AlreadyActive
        val r6 = DeveloperModeManager.onVersionTap()
        assertEquals(DeveloperTapResult.AlreadyActive, r6)
    }

    @Test
    fun testDeveloperModeManualDeactivation() {
        DeveloperModeManager.setDeveloperModeEnabled(enabled = true)
        assertTrue(DeveloperModeManager.isDeveloperModeEnabled())

        DeveloperModeManager.setDeveloperModeEnabled(enabled = false)
        assertFalse(DeveloperModeManager.isDeveloperModeEnabled())
    }
}
