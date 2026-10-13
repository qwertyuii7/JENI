package com.scheduleassistant.app

import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ParserParityTest {

    private lateinit var specLoader: SpecLoader
    private lateinit var prefilter: Prefilter
    private lateinit var parser: ScheduleParser

    @Before
    fun setUp() {
        specLoader = SpecLoader(
            keywordsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/keywords.json")!! },
            synonymsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/synonyms.json")!! },
            daypartsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/dayparts.json")!! },
            fillersStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/fillers.json")!! }
        )
        prefilter = Prefilter(specLoader)
        parser = ScheduleParser(specLoader, prefilter)
    }

    @Test
    fun testParserParityAgainstSpecFixtures() {
        val stream = javaClass.classLoader!!.getResourceAsStream("spec/fixtures/phrases.json")
            ?: error("Could not find spec/fixtures/phrases.json in test resources")
        val jsonText = stream.bufferedReader().use { it.readText() }
        val array = JSONArray(jsonText)

        var passed = 0
        val failures = mutableListOf<String>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.getString("id")
            val input = item.getString("input")
            val refNow = item.optString("reference_now", "2026-10-12T10:00:00+05:30")
            val expected = item.getJSONObject("expected")
            val isSchedule = expected.getBoolean("is_schedule")

            val actual = parser.parse(input, refNow)

            if (!isSchedule) {
                if (actual != null) {
                    failures.add("[$id] Expected null (not schedule) for \"$input\", got title: \"${actual.title}\"")
                } else {
                    passed++
                }
            } else {
                val exp = expected.getJSONObject("extraction")
                val expType = exp.getString("type")
                val expTitle = exp.getString("title")

                if (actual == null) {
                    failures.add("[$id] Expected extraction for \"$input\", got null")
                    continue
                }

                try {
                    assertEquals("[$id] Type mismatch", expType, actual.type)
                    assertEquals("[$id] Title mismatch", expTitle.lowercase(), actual.title.lowercase())

                    if (exp.has("start") && !exp.isNull("start")) {
                        val expStart = exp.getString("start")
                        assertEquals("[$id] Start ISO mismatch", expStart, actual.start)
                    }

                    if (exp.has("start_date") && !exp.isNull("start_date")) {
                        val expStartDate = exp.getString("start_date")
                        assertEquals("[$id] Start date mismatch", expStartDate, actual.start_date)
                    }

                    if (exp.optBoolean("time_unspecified", false)) {
                        assertTrue("[$id] Expected time_unspecified=true", actual.time_unspecified)
                        assertNull("[$id] Expected null start timestamp when time_unspecified", actual.start)
                    }

                    if (exp.optBoolean("ambiguous", false)) {
                        assertTrue("[$id] Expected ambiguous=true", actual.ambiguous)
                        assertTrue("[$id] Expected confidence <= 0.65", actual.confidence <= 0.65)
                    } else {
                        assertTrue("[$id] Expected confidence >= 0.75", actual.confidence >= 0.75)
                    }

                    passed++
                } catch (e: AssertionError) {
                    failures.add("[$id] Assertion failed for \"$input\": ${e.message}")
                }
            }
        }

        if (failures.isNotEmpty()) {
            System.err.println("Parity Failures (${failures.size}/${array.length()}):")
            failures.take(10).forEach { System.err.println("  $it") }
        }

        assertEquals("All fixtures must pass without failure: ${failures.take(5)}", 0, failures.size)
        assertEquals(array.length(), passed)
    }
}
