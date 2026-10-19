package com.scheduleassistant.app

import com.scheduleassistant.app.domain.DefaultLaneClassifier
import com.scheduleassistant.app.domain.DefaultSignalScorer
import com.scheduleassistant.app.domain.SignalConfig
import com.scheduleassistant.app.domain.TriageLane
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Triage Evaluation Suite & Metrics Benchmark for Day 2.
 *
 * Verifies the three-lane classification against labeled fixtures from real messages,
 * minimal pairs, and Hindi edge cases.
 *
 * Evaluates:
 * 1. Schedule Recall (Target: >= 85%)
 * 2. Card Precision (Target: >= 90%)
 * 3. Needs Attention Recall (Target: >= 80%)
 * 4. Ignore False Negative Rate (Target: < 2%)
 */
class TriageEvaluationTest {

    private lateinit var scorer: DefaultSignalScorer
    private lateinit var classifier: DefaultLaneClassifier
    private lateinit var specRoot: File

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
        error("Could not locate root /spec directory")
    }

    @Before
    fun setUp() {
        specRoot = findSpecRoot()
        val signalsFile = File(specRoot, "signals.json")
        assertTrue("signals.json must exist at ${signalsFile.absolutePath}", signalsFile.exists())

        val config = SignalConfig.fromJson(signalsFile.readText())
        scorer = DefaultSignalScorer(config)
        classifier = DefaultLaneClassifier()
    }

    @Test
    fun testTriageFixturesEvaluation() {
        val fixturesFile = File(File(specRoot, "fixtures"), "triage.json")
        assertTrue("triage.json must exist at ${fixturesFile.absolutePath}", fixturesFile.exists())

        val arr = JSONArray(fixturesFile.readText())
        assertTrue("Fixtures must not be empty", arr.length() > 0)

        var tpSchedule = 0
        var fpSchedule = 0
        var fnSchedule = 0

        var tpAttention = 0
        var fpAttention = 0
        var fnAttention = 0

        var tpIgnore = 0
        var fpIgnore = 0
        var fnIgnore = 0

        var criticalIgnoreFalseNegatives = 0 // Real schedule/attention wrongly classified as IGNORE

        val mismatches = mutableListOf<String>()

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.getString("id")
            val text = item.getString("text")
            val sender = item.optString("sender", null)
            val chat = item.optString("chat", null)
            val expectedLaneStr = item.getString("expected_lane")
            val expectedLane = TriageLane.valueOf(expectedLaneStr)

            val features = scorer.score(text, sender, chat)
            val classification = classifier.classify(features)
            val actualLane = classification.lane

            if (actualLane == expectedLane) {
                when (expectedLane) {
                    TriageLane.SCHEDULE -> tpSchedule++
                    TriageLane.NEEDS_ATTENTION -> tpAttention++
                    TriageLane.IGNORE -> tpIgnore++
                }
            } else {
                mismatches.add(
                    "[$id] '$text'\n  Expected: $expectedLane, Actual: $actualLane\n  Reasons: ${classification.reasons}\n  Tokens: ${features.matchedTokens}\n"
                )
                when (expectedLane) {
                    TriageLane.SCHEDULE -> {
                        fnSchedule++
                        if (actualLane == TriageLane.IGNORE) criticalIgnoreFalseNegatives++
                        if (actualLane == TriageLane.NEEDS_ATTENTION) fpAttention++
                    }
                    TriageLane.NEEDS_ATTENTION -> {
                        fnAttention++
                        if (actualLane == TriageLane.IGNORE) criticalIgnoreFalseNegatives++
                        if (actualLane == TriageLane.SCHEDULE) fpSchedule++
                    }
                    TriageLane.IGNORE -> {
                        fnIgnore++
                        if (actualLane == TriageLane.SCHEDULE) fpSchedule++
                        if (actualLane == TriageLane.NEEDS_ATTENTION) fpAttention++
                    }
                }
            }
        }

        val totalFixtures = arr.length()
        val totalCorrect = tpSchedule + tpAttention + tpIgnore
        val overallAccuracy = (totalCorrect.toDouble() / totalFixtures) * 100.0

        val schedulePrecision = if (tpSchedule + fpSchedule > 0) {
            (tpSchedule.toDouble() / (tpSchedule + fpSchedule)) * 100.0
        } else 0.0

        val scheduleRecall = if (tpSchedule + fnSchedule > 0) {
            (tpSchedule.toDouble() / (tpSchedule + fnSchedule)) * 100.0
        } else 0.0

        val attentionRecall = if (tpAttention + fnAttention > 0) {
            (tpAttention.toDouble() / (tpAttention + fnAttention)) * 100.0
        } else 0.0

        val ignoreFnRate = if (totalFixtures > 0) {
            (criticalIgnoreFalseNegatives.toDouble() / totalFixtures) * 100.0
        } else 0.0

        println("============================================================")
        println("TRIAGE EVALUATION BENCHMARK RESULTS (Day 2)")
        println("Total Fixtures: $totalFixtures")
        println("Overall Accuracy: %.2f%% (%d/%d)".format(overallAccuracy, totalCorrect, totalFixtures))
        println("Schedule Recall: %.2f%% (Target >= 85%%)".format(scheduleRecall))
        println("Card Precision: %.2f%% (Target >= 90%%)".format(schedulePrecision))
        println("Needs Attention Recall: %.2f%% (Target >= 80%%)".format(attentionRecall))
        println("Critical Ignore False Negative Rate: %.2f%% (Target < 2%%)".format(ignoreFnRate))
        println("============================================================")

        if (mismatches.isNotEmpty()) {
            println("MISMATCHES (${mismatches.size}):")
            mismatches.forEach { println(it) }
        }

        assertTrue(
            "Schedule Recall (%.2f%%) must be >= 85%%".format(scheduleRecall),
            scheduleRecall >= 85.0
        )
        assertTrue(
            "Card Precision (%.2f%%) must be >= 90%%".format(schedulePrecision),
            schedulePrecision >= 90.0
        )
        assertTrue(
            "Needs Attention Recall (%.2f%%) must be >= 80%%".format(attentionRecall),
            attentionRecall >= 80.0
        )
        assertTrue(
            "Critical Ignore False Negative Rate (%.2f%%) must be < 2%%".format(ignoreFnRate),
            ignoreFnRate < 2.0
        )

        assertEquals("Zero classification mismatches expected on benchmark suite", 0, mismatches.size)
    }
}
