package com.scheduleassistant.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpecParityTest {

    private fun findSpecRoot(): File {
        val candidates = listOf(
            File("../../spec"),
            File("../spec"),
            File("spec"),
            File("../../../spec")
        )
        for (cand in candidates) {
            if (cand.exists() && cand.isDirectory && File(cand, "keywords.json").exists()) {
                return cand.canonicalFile
            }
        }
        error("Could not locate root /spec directory from ${File(".").canonicalPath}")
    }

    private fun normalizeJson(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("[")) {
            JSONArray(trimmed).toString()
        } else {
            JSONObject(trimmed).toString()
        }
    }

    @Test
    fun testAllAndroidSpecCopiesMatchRootSpec() {
        val specRoot = findSpecRoot()
        val appRoot = File(specRoot.parentFile, "android/app")

        val assetsSpecDir = File(appRoot, "src/main/assets/spec")
        val testResourcesSpecDir = File(appRoot, "src/test/resources/spec")
        val testResourcesFixturesDir = File(testResourcesSpecDir, "fixtures")

        assertTrue("Assets spec dir must exist", assetsSpecDir.exists())
        assertTrue("Test resources spec dir must exist", testResourcesSpecDir.exists())

        val rootJsonFiles = listOf("dayparts.json", "fillers.json", "keywords.json", "synonyms.json")
        for (filename in rootJsonFiles) {
            val rootFile = File(specRoot, filename)
            assertTrue("Root spec file must exist: $filename", rootFile.exists())
            val rootContent = normalizeJson(rootFile.readText())

            val assetFile = File(assetsSpecDir, filename)
            assertTrue("Asset file must exist: $filename", assetFile.exists())
            assertEquals(
                "Asset copy of $filename differs from root /spec/$filename",
                rootContent,
                normalizeJson(assetFile.readText())
            )

            val testResourceFile = File(testResourcesSpecDir, filename)
            assertTrue("Test resource file must exist: $filename", testResourceFile.exists())
            assertEquals(
                "Test resource copy of $filename differs from root /spec/$filename",
                rootContent,
                normalizeJson(testResourceFile.readText())
            )
        }

        val fixtureFiles = listOf("phrases.json", "matching.json")
        for (filename in fixtureFiles) {
            val rootFixture = File(File(specRoot, "fixtures"), filename)
            assertTrue("Root fixture must exist: $filename", rootFixture.exists())
            val rootContent = normalizeJson(rootFixture.readText())

            val testFixture = File(testResourcesFixturesDir, filename)
            assertTrue("Test resource fixture must exist: $filename", testFixture.exists())
            assertEquals(
                "Test resource fixture copy of $filename differs from root /spec/fixtures/$filename",
                rootContent,
                normalizeJson(testFixture.readText())
            )
        }
    }
}
