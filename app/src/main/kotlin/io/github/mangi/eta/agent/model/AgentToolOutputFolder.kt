package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Local, deterministic history maintenance, never a journal/transcript rewrite.
 * Keep at least three recent assistant exchanges AND 24K characters of tail, rounded
 * outwards to a closed tool batch. A single long user task can therefore shed old
 * reads too. No changes at all while a signed response or incomplete batch is live.
 */
internal object AgentToolOutputFolder {
    const val LARGE_OUTPUT_CHARS = 3_000
    const val MAX_FOLDED_CHARS = 1_500
    private const val PROTECTED_TAIL_CHARS = 24_000
    private const val PROTECTED_ASSISTANTS = 3
    private val envelopeKeys = listOf("ok", "tool", "path", "code", "next_offset_bytes", "revision",
        "document_id", "subagent_id", "environment", "identity", "offset_bytes", "next_offset_chars",
        "next_cursor", "complete", "partial", "truncated", "stop_reason", "next_offset", "has_more", "session_id")

    fun fold(messages: JSONArray, systemCount: Int): Int {
        if (SignedToolContext.hasPendingResponse(messages)) return 0
        val open = mutableSetOf<String>()
        val splits = mutableListOf<Int>()
        val assistants = mutableListOf<Int>()
        for (index in systemCount until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role == "assistant") {
                if (open.isNotEmpty()) return 0
                assistants += index
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
            } else if (role == "tool") {
                if (!open.remove(message.optString("tool_call_id"))) return 0
            } else if (open.isNotEmpty()) return 0
            if (open.isEmpty() && messages.optJSONObject(index + 1)?.optString("role") != "tool") splits += index + 1
        }
        if (open.isNotEmpty() || assistants.size <= PROTECTED_ASSISTANTS) return 0
        var tailStart = messages.length()
        var tailChars = 0L
        while (tailStart > systemCount && tailChars < PROTECTED_TAIL_CHARS) {
            tailStart--
            tailChars += messages.getJSONObject(tailStart).toString().length
        }
        val bound = minOf(tailStart, assistants[assistants.size - PROTECTED_ASSISTANTS])
        val end = splits.lastOrNull { it <= bound } ?: return 0
        var changed = 0
        for (index in systemCount until end) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "tool") continue
            val content = message.opt("content") as? String ?: continue // Never rewrite image/structured provider blocks.
            if (content.length <= LARGE_OUTPUT_CHARS) continue
            // appendMessage shares objects with the append-only transcript. Replace the
            // array slot with a copy; mutating message.content would also mutate journal.
            val copy = JSONObject()
            message.keys().forEach { key -> copy.put(key, message.opt(key)) }
            copy.put("content", foldedContent(content))
            messages.put(index, copy)
            changed++
        }
        return changed
    }

    private fun foldedContent(content: String): String {
        val envelope = JSONObject()
        val source = if (content.trimStart().startsWith('{')) runCatching { JSONObject(content) }.getOrNull() else null
        envelopeKeys.forEach { key ->
            val value = source?.opt(key) ?: return@forEach
            if (value is JSONObject || value is JSONArray) return@forEach
            // Keep exact scalar metadata when bounded, not invented/truncated revisions.
            envelope.put(key, value)
            if (envelope.toString().length > 800) envelope.remove(key)
        }
        envelope.put("older_tool_output_folded", true)
        // JSON escaping can expand quotes/control characters. Fit the serialized result,
        // not just the preview; the binary search is deterministic for identical input.
        var low = 0
        var high = 1_200
        while (low < high) {
            val size = (low + high + 1) / 2
            envelope.put("preview", preview(content, size))
            if (envelope.toString().length <= MAX_FOLDED_CHARS) low = size else high = size - 1
        }
        return envelope.put("preview", preview(content, low)).toString()
    }

    private fun preview(text: String, size: Int): String {
        var head = size / 2
        var tail = size - head
        if (head > 0 && text[head - 1].isHighSurrogate()) head--
        if (tail > 0 && text[text.length - tail].isLowSurrogate()) tail--
        return text.take(head) + "\n[... truncated ${text.length - head - tail} chars from older turn; re-read if needed ...]\n" + text.takeLast(tail)
    }
}
