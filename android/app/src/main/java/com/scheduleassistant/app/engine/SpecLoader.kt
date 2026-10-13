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
    private val daypartsStreamProvider: () -> InputStream,
    private val fillersStreamProvider: (() -> InputStream)? = null
) {
    val scheduleTriggers: List<String> by lazy { loadTriggers() }
    val knownSubjects: List<String> by lazy { loadSubjects() }
    val intraTypeSynonyms: Map<String, List<String>> by lazy { loadSynonyms() }
    val dayparts: Map<String, DaypartConfig> by lazy { loadDayparts() }
    val titleFillers: List<String> by lazy { loadFillers() }

    fun normalizeTitleForMatching(title: String): String {
        var normalized = title.lowercase()
        val ascii = titleFillers.filter { it.all { c -> c.code < 128 } }
        val unicode = titleFillers.filter { it.any { c -> c.code >= 128 } }
        if (ascii.isNotEmpty()) {
            val asciiRegex = Regex("(?i)\\b(?:${ascii.joinToString("|") { Regex.escape(it) }})\\b")
            normalized = normalized.replace(asciiRegex, " ")
        }
        if (unicode.isNotEmpty()) {
            val unicodeRegex = Regex("(?:(?<=^|[\\s\\p{P}])(?:${unicode.joinToString("|") { Regex.escape(it) }})(?=$|[\\s\\p{P}]))")
            normalized = normalized.replace(unicodeRegex, " ")
        }
        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    private fun loadFillers(): List<String> {
        val defaultFillers = listOf(
            "ki", "ka", "ke",
            "की", "का", "के",
            "wali", "wala", "wale",
            "vali", "vala", "vale",
            "वाली", "वाला", "वाले"
        )
        try {
            if (fillersStreamProvider != null) {
                val json = JSONObject(fillersStreamProvider.invoke().bufferedReader().use { it.readText() })
                val arr = if (json.has("title_fillers")) json.getJSONArray("title_fillers")
                          else if (json.has("fillers")) json.getJSONArray("fillers")
                          else null
                if (arr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getString(i))
                    }
                    if (list.isNotEmpty()) return list
                }
            }
            val kwJson = JSONObject(keywordsStreamProvider().bufferedReader().use { it.readText() })
            if (kwJson.has("title_fillers")) {
                val arr = kwJson.getJSONArray("title_fillers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                if (list.isNotEmpty()) return list
            }
        } catch (_: Exception) {}
        return defaultFillers
    }

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
