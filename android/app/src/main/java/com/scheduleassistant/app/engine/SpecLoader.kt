package com.scheduleassistant.app.engine

import org.json.JSONObject
import java.io.InputStream

data class DaypartConfig(
    val tokens: List<String>,
    val startHour: Int? = null,
    val endHour: Int? = null,
    val pmHours: List<Int>? = null,
    val midnightHour: Int? = null,
    val earlyAmHours: List<Int>? = null,
    val borderHours: List<Int>? = null
)

class SpecLoader(
    private val keywordsStreamProvider: () -> InputStream,
    private val synonymsStreamProvider: () -> InputStream,
    private val daypartsStreamProvider: () -> InputStream
) {
    val scheduleTriggers: List<String> by lazy { loadTriggers() }
    val knownSubjects: List<String> by lazy { loadSubjects() }
    val intraTypeSynonyms: Map<String, List<String>> by lazy { loadSynonyms() }
    val dayparts: Map<String, DaypartConfig> by lazy { loadDayparts() }

    private fun loadTriggers(): List<String> {
        val json = JSONObject(keywordsStreamProvider().bufferedReader().use { it.readText() })
        val arr = json.getJSONArray("schedule_triggers")
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.getString(i))
        }
        return list
    }

    private fun loadSubjects(): List<String> {
        val json = JSONObject(keywordsStreamProvider().bufferedReader().use { it.readText() })
        val arr = json.getJSONArray("known_subjects")
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.getString(i))
        }
        return list.sortedByDescending { it.length }
    }

    private fun loadSynonyms(): Map<String, List<String>> {
        val json = JSONObject(synonymsStreamProvider().bufferedReader().use { it.readText() })
        val map = mutableMapOf<String, List<String>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val category = keys.next()
            val catObj = json.getJSONObject(category)
            val catKeys = catObj.keys()
            while (catKeys.hasNext()) {
                val word = catKeys.next()
                val synArr = catObj.getJSONArray(word)
                val synList = mutableListOf<String>()
                for (i in 0 until synArr.length()) {
                    synList.add(synArr.getString(i).lowercase())
                }
                map[word.lowercase()] = synList
            }
        }
        return map
    }

    private fun loadDayparts(): Map<String, DaypartConfig> {
        val json = JSONObject(daypartsStreamProvider().bufferedReader().use { it.readText() })
        val map = mutableMapOf<String, DaypartConfig>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = json.getJSONObject(key)
            val tokensArr = obj.getJSONArray("tokens")
            val tokens = mutableListOf<String>()
            for (i in 0 until tokensArr.length()) {
                tokens.add(tokensArr.getString(i))
            }
            map[key] = DaypartConfig(
                tokens = tokens,
                startHour = if (obj.has("start_hour")) obj.getInt("start_hour") else null,
                endHour = if (obj.has("end_hour")) obj.getInt("end_hour") else null
            )
        }
        return map
    }
}
