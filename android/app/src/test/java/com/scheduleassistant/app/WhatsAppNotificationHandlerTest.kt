package com.scheduleassistant.app

import com.scheduleassistant.app.engine.WhatsAppNotificationHandler
import org.junit.Assert.*
import org.junit.Test

class WhatsAppNotificationHandlerTest {

    private val handler = WhatsAppNotificationHandler()

    @Test
    fun testSplitSenderPrefixFromGroupMessage() {
        val (sender1, text1) = handler.splitSenderPrefix(null, "Rahul: kal 4 baje DBMS class hai")
        assertEquals("Rahul", sender1)
        assertEquals("kal 4 baje DBMS class hai", text1)

        val (sender2, text2) = handler.splitSenderPrefix(null, "Dr. Mehta: Friday 11am project review meeting")
        assertEquals("Dr. Mehta", sender2)
        assertEquals("Friday 11am project review meeting", text2)

        val (sender3, text3) = handler.splitSenderPrefix("+919876543210", "kal subah 10 baje test hai")
        assertEquals("+919876543210", sender3)
        assertEquals("kal subah 10 baje test hai", text3)
    }

    @Test
    fun testDeduplicationByChatTextAndPostTime() {
        val chat = "College Group"
        val text = "kal 4 baje DBMS class hai"
        val postTime = 1770000000000L

        // First ingestion: not duplicate
        val isFirstDup = handler.isDeduplicated(chat, text, postTime)
        assertFalse("First notification occurrence should not be deduplicated", isFirstDup)

        // Second ingestion with identical chat, text, postTime: should be detected as duplicate
        val isSecondDup = handler.isDeduplicated(chat, text, postTime)
        assertTrue("Subsequent notification with identical (chat, text, postTime) MUST be deduplicated", isSecondDup)

        // Same message with new post time within 24h: should be deduplicated ignoring post time
        val isSameMessageNewTime = handler.isDeduplicated(chat, text, postTime + 5000L)
        assertTrue("Notification within 24h should be deduplicated ignoring post time", isSameMessageNewTime)

        // Different text: new message
        val isDifferentText = handler.isDeduplicated(chat, "parso 10 baje quiz", postTime)
        assertFalse("Notification with new text should not be deduplicated", isDifferentText)

        // After 24h: should no longer be deduplicated
        val isAfter24h = handler.isDeduplicated(chat, text, postTime + 25 * 60 * 60 * 1000L)
        assertFalse("Notification after 24h should not be deduplicated", isAfter24h)
    }

    @Test
    fun testTrimAndWhitespaceHandlingInSenderPrefix() {
        val (sender, text) = handler.splitSenderPrefix(null, "  Prof Verma :   meeting postponed to Monday  ")
        assertEquals("Prof Verma", sender)
        assertEquals("meeting postponed to Monday", text)
    }
}
