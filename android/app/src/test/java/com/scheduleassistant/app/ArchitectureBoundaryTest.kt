package com.scheduleassistant.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Architecture boundary test enforcing Section 3 of WEEK_PLAN.md and PROJECT_SCOPE.md:
 *
 * 1. domain/ is pure Kotlin: zero android.* imports.
 * 2. domain/ does not import upward (no ui, no service, no alerts, no ingestion, no data).
 * 3. engine/ (ingestion/triage/spec parsing) does not depend on ui.
 */
class ArchitectureBoundaryTest {

    private val sourceRoot = File("src/main/java/com/scheduleassistant/app")

    @Test
    fun testDomainContainsZeroAndroidImports() {
        val domainDir = File(sourceRoot, "domain")
        assertTrue("Domain directory must exist at ${domainDir.absolutePath}", domainDir.exists() && domainDir.isDirectory)

        val ktFiles = domainDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("Domain directory must contain at least one Kotlin source file", ktFiles.isNotEmpty())

        val violations = mutableListOf<String>()

        for (file in ktFiles) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import android.")) {
                    violations.add("${file.name}:${index + 1} -> $trimmed")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Architecture boundary violation! Found android.* imports in domain:\n" +
                    violations.joinToString("\n"))
        }
    }

    @Test
    fun testDomainDoesNotImportUpward() {
        val domainDir = File(sourceRoot, "domain")
        val ktFiles = domainDir.walkTopDown().filter { it.extension == "kt" }.toList()

        val forbiddenPrefixes = listOf(
            "import com.scheduleassistant.app.ui",
            "import com.scheduleassistant.app.service",
            "import com.scheduleassistant.app.alerts",
            "import com.scheduleassistant.app.ingestion",
            "import com.scheduleassistant.app.data"
        )

        val violations = mutableListOf<String>()

        for (file in ktFiles) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                for (prefix in forbiddenPrefixes) {
                    if (trimmed.startsWith(prefix)) {
                        violations.add("${file.name}:${index + 1} -> $trimmed")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Architecture boundary violation! Domain must not import upward:\n" +
                    violations.joinToString("\n"))
        }
    }

    @Test
    fun testEngineDoesNotImportUi() {
        val engineDir = File(sourceRoot, "engine")
        assertTrue("Engine directory must exist", engineDir.exists() && engineDir.isDirectory)

        val ktFiles = engineDir.walkTopDown().filter { it.extension == "kt" }.toList()
        val violations = mutableListOf<String>()

        for (file in ktFiles) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import com.scheduleassistant.app.ui")) {
                    violations.add("${file.name}:${index + 1} -> $trimmed")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Architecture boundary violation! Engine must not depend on UI:\n" +
                    violations.joinToString("\n"))
        }
    }
}
