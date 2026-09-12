package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {
    private fun msg(user: Boolean, text: String) = ChatMessage(fromUser = user, text = text)

    @Test
    fun filterDropsGreetingErrorsAndCurrentPrompt() {
        val history = listOf(
            msg(false, "Hi! Tell me what to build"),
            msg(true, "first question"),
            msg(false, "first answer"),
            msg(false, "Failed to reach provider"),
            msg(true, "current prompt"),
        )
        val kept = filterPriorChatMessages(history)
        assertEquals(listOf("first question", "first answer"), kept.map { it.text })
    }

    @Test
    fun filterCapsAtTwentyMessages() {
        val history = (1..25).map { msg(true, "q$it") } + msg(true, "current")
        val kept = filterPriorChatMessages(history)
        assertEquals(20, kept.size)
        assertEquals("q6", kept.first().text)
    }

    @Test
    fun blockBlankWhenNothingWorthInjecting() {
        assertEquals("", conversationHistoryBlock(emptyList(), "/workspace/x"))
    }

    @Test
    fun blockLabelsRolesAndTrimsPastByteCeiling() {
        val history = (1..15).map { msg(true, "filler $it " + "x".repeat(10_000)) } +
            listOf(msg(true, "remember the pineapple"), msg(false, "noted")) +
            msg(true, "current")
        val block = conversationHistoryBlock(filterPriorChatMessages(history), "/workspace/x")
        assertTrue(block.contains("User: remember the pineapple"))
        assertTrue(block.length <= 100_000)
        assertFalse(block.contains("filler 1 "))
    }
}
