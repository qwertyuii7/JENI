package com.scheduleassistant.app

import com.scheduleassistant.app.data.db.DiagnosticLogEntity
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsAnonymizerTest {

    @Test
    fun testAnonymizeLogEntryMasksPhoneNumbersAndSenderNames() {
        val entry = DiagnosticLogEntity(
            id = "diag-1",
            createdAt = "2026-10-12T10:00:00Z",
            packageName = "com.whatsapp",
            chatTitle = "Rahul Sharma",
            sender = "Rahul Sharma",
            isGroup = false,
            rawText = "Rahul Sharma: Call me at +91 9876543210. kal 4 baje DBMS class hai",
            prefilterResult = "passed",
            parserOutput = JSONObject().apply {
                put("type", "new")
                put("title", "DBMS class")
                put("start", "2026-10-13T16:00:00+05:30")
                put("confidence", 0.9)
            }.toString(),
            mode = "LOG_ONLY"
        )

        val nameMap = mutableMapOf<String, String>()
        val resultJson = DiagnosticsManager.anonymizeLogEntry(entry, nameMap)

        val anonymizedText = resultJson.getString("anonymized_text")

        // Assert phone number is masked
        assertFalse("Phone number +91 9876543210 must not be present in clear text", anonymizedText.contains("9876543210"))
        assertTrue("Phone number must be replaced with [PHONE_MASKED]", anonymizedText.contains("[PHONE_MASKED]"))

        // Assert sender name is pseudonymized
        assertFalse("Personal name 'Rahul Sharma' must not appear in clear text", anonymizedText.contains("Rahul Sharma"))
        assertTrue("Sender name must be replaced by pseudonym [PERSON_1]", anonymizedText.contains("[PERSON_1]"))

        // Assert schedule keywords and times are preserved
        assertTrue("Schedule trigger 'kal' must remain intact", anonymizedText.contains("kal"))
        assertTrue("Schedule time '4 baje' must remain intact", anonymizedText.contains("4 baje"))
        assertTrue("Subject 'DBMS class' must remain intact", anonymizedText.contains("DBMS class"))

        // Assert parser output structure is preserved
        val parserOut = resultJson.getJSONObject("parser_output")
        assertEquals("new", parserOut.getString("type"))
        assertEquals("DBMS class", parserOut.getString("title"))
        assertEquals("2026-10-13T16:00:00+05:30", parserOut.getString("start"))
    }

    @Test
    fun testPseudonymConsistencyAcrossMultipleEntries() {
        val nameMap = mutableMapOf<String, String>()

        val entry1 = DiagnosticLogEntity(
            id = "diag-1",
            createdAt = "2026-10-12T10:00:00Z",
            packageName = "com.whatsapp",
            chatTitle = "Study Group",
            sender = "Pooja Verma",
            isGroup = true,
            rawText = "Pooja Verma: kal 2 baje test hai",
            prefilterResult = "passed",
            parserOutput = null,
            mode = "LOG_ONLY"
        )

        val entry2 = DiagnosticLogEntity(
            id = "diag-2",
            createdAt = "2026-10-12T10:05:00Z",
            packageName = "com.whatsapp",
            chatTitle = "Study Group",
            sender = "Pooja Verma",
            isGroup = true,
            rawText = "Pooja Verma: test shift ho gaya",
            prefilterResult = "passed",
            parserOutput = null,
            mode = "LOG_ONLY"
        )

        val res1 = DiagnosticsManager.anonymizeLogEntry(entry1, nameMap)
        val res2 = DiagnosticsManager.anonymizeLogEntry(entry2, nameMap)

        val shape1 = res1.getJSONObject("notification_shape")
        val shape2 = res2.getJSONObject("notification_shape")

        // Both must have the identical pseudonym [PERSON_1]
        assertEquals("[PERSON_1]", shape1.getString("sender_pseudonym"))
        assertEquals("[PERSON_1]", shape2.getString("sender_pseudonym"))
    }
}
