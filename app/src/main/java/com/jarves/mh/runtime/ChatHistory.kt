package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage

/** Max prior turns injected as `<conversation_history>`. */
private const val MAX_HISTORY_MESSAGES = 20
/** Hard byte ceiling for the injected block (proot `Argument list too long`). */
private const val MAX_HISTORY_CHARS = 100_000

/**
 * Shared history filter: drop the just-sent prompt, the greeting, and error
 * noise. Previously duplicated verbatim in the DSH and Claude bridges.
 */
internal fun filterPriorChatMessages(history: List<ChatMessage>): List<ChatMessage> =
    history.filter { msg ->
        (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
            !msg.text.startsWith("Failed to") &&
            !msg.text.startsWith("Error:") &&
            !msg.text.contains("API Error")
    }.dropLast(1).takeLast(MAX_HISTORY_MESSAGES)

/**
 * `<conversation_history>` block, oldest-trimmed past the byte ceiling.
 * Blank when there is nothing worth injecting (first turn).
 */
internal fun conversationHistoryBlock(prior: List<ChatMessage>, guestWorkspacePath: String): String {
    var kept = prior
    var out = renderHistory(kept, guestWorkspacePath)
    // ponytail: trim oldest turns past the ceiling instead of failing spawn.
    while (out.length > MAX_HISTORY_CHARS && kept.size > 1) {
        kept = kept.drop(1)
        out = renderHistory(kept, guestWorkspacePath)
    }
    return out
}

private fun renderHistory(prior: List<ChatMessage>, guestWorkspacePath: String): String {
    if (prior.isEmpty()) return ""
    return buildString {
        appendLine("<conversation_history>")
        appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        appendLine()
        for (msg in prior) {
            val role = if (msg.fromUser) "User" else "Assistant"
            appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            appendLine()
        }
        appendLine("</conversation_history>")
    }
}
