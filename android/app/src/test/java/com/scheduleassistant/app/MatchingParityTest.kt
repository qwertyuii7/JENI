package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.engine.SpecLoader
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MatchingParityTest {

    private lateinit var specLoader: SpecLoader

    @Before
    fun setUp() {
        specLoader = SpecLoader(
            keywordsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/keywords.json")!! },
            synonymsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/synonyms.json")!! },
            daypartsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/dayparts.json")!! },
            fillersStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/fillers.json")!! }
        )
    }

    private fun areTitlesSynonyms(t1: String, t2: String): Boolean {
        if (t1 == t2) return true
        val w1 = t1.split(Regex("\\s+"))
        val w2 = t2.split(Regex("\\s+"))
        if (w1.size != w2.size) return false
        for (i in w1.indices) {
            if (w1[i] == w2[i]) continue
            val syns = specLoader.intraTypeSynonyms[w1[i]] ?: emptyList()
            if (!syns.contains(w2[i])) return false
        }
        return true
    }

    private fun matchTarget(
        existingSchedules: List<ScheduleEntity>,
        incomingTitle: String
    ): ScheduleEntity? {
        val targetTitle = incomingTitle.lowercase().trim()
        val cleanTarget = specLoader.normalizeTitleForMatching(targetTitle)

        val candidates = existingSchedules.filter { sched ->
            val sTitle = sched.title.lowercase().trim()
            val cleanSched = specLoader.normalizeTitleForMatching(sTitle)
            val isSynonym = specLoader.intraTypeSynonyms[targetTitle]?.contains(sTitle) == true ||
                            specLoader.intraTypeSynonyms[cleanTarget]?.contains(cleanSched) == true ||
                            areTitlesSynonyms(cleanSched, cleanTarget) ||
                            areTitlesSynonyms(sTitle, targetTitle)
            sTitle == targetTitle || cleanSched == cleanTarget ||
            sTitle.contains(targetTitle) || targetTitle.contains(sTitle) ||
            cleanSched.contains(cleanTarget) || cleanTarget.contains(cleanSched) ||
            isSynonym
        }
        return if (candidates.size == 1) candidates[0] else null
    }

    @Test
    fun testMatchingFixturesParity() {
        val stream = javaClass.classLoader!!.getResourceAsStream("spec/fixtures/matching.json")
            ?: error("Could not find spec/fixtures/matching.json in test resources")
        val jsonText = stream.bufferedReader().use { it.readText() }
        val array = JSONArray(jsonText)

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.getString("id")
            val desc = item.getString("description")
            val existingArr = item.getJSONArray("existing_events")
            val incoming = item.getJSONObject("incoming")
            val incomingTitle = incoming.getString("title")
            val expectedId = if (item.isNull("expected_matched_id")) null else item.getString("expected_matched_id")

            val existingList = mutableListOf<ScheduleEntity>()
            for (j in 0 until existingArr.length()) {
                val ev = existingArr.getJSONObject(j)
                existingList.add(
                    ScheduleEntity(
                        id = ev.getString("id"),
                        title = ev.getString("title"),
                        start = ev.optString("start", "2026-10-13T10:00:00+05:30"),
                        status = ScheduleStatus.CONFIRMED,
                        source_quote = ev.getString("title"),
                        confidence = 0.9,
                        created_at = "2026-10-12T10:00:00+05:30",
                        updated_at = "2026-10-12T10:00:00+05:30"
                    )
                )
            }

            val matched = matchTarget(existingList, incomingTitle)
            if (expectedId == null) {
                assertNull("[$id] $desc: Expected null match for '$incomingTitle', got '${matched?.title}'", matched)
            } else {
                assertEquals("[$id] $desc: Expected match for '$incomingTitle'", expectedId, matched?.id)
            }
        }
    }
}
