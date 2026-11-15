package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 仅折叠已结束用户轮次的旧结果；保留最近三次交换和 24K 尾部，不改 transcript 或签名链。 */
internal object AgentToolOutputFolder {
    const val LARGE_OUTPUT_CHARS = 3_000
    const val MAX_FOLDED_CHARS = 1_500
    const val MAX_READ_FILE_FOLDED_CHARS = 4_000
    private const val PROTECTED_TAIL_CHARS = 24_000
    private const val PROTECTED_ASSISTANTS = 3
    private val envelopeKeys = listOf("ok", "tool", "path", "code", "next_offset_bytes", "revision",
        "document_id", "subagent_id", "environment", "identity", "offset_bytes", "next_offset_chars",
        "next_cursor", "complete", "partial", "truncated", "stop_reason", "next_offset", "has_more", "session_id",
        "offset", "limit", "start_line", "end_line", "line_count", "total_lines", "offset_chars", "limit_chars",
        "next_line", "start_line_reached", "line_truncated", "size_bytes", "bytes_read")

    data class FoldResult(val changedMessages: Int = 0, val savedTokens: Int = 0)

    fun fold(messages: JSONArray, systemCount: Int): FoldResult {
        if (SignedToolContext.hasPendingResponse(messages)) return FoldResult()
        val open = mutableSetOf<String>()
        val toolNames = mutableMapOf<String, String>()
        val splits = mutableListOf<Int>()
        val assistants = mutableListOf<Int>()
        for (index in systemCount until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role == "assistant") {
                if (open.isNotEmpty()) return FoldResult()
                assistants += index
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id; toolNames[it.id] = it.name }
            } else if (role == "tool") {
                if (!open.remove(message.optString("tool_call_id"))) return FoldResult()
            } else if (open.isNotEmpty()) return FoldResult()
            if (open.isEmpty() && messages.optJSONObject(index + 1)?.optString("role") != "tool") splits += index + 1
        }
        if (open.isNotEmpty() || assistants.size <= PROTECTED_ASSISTANTS) return FoldResult()
        var tailStart = messages.length()
        var tailChars = 0L
        while (tailStart > systemCount && tailChars < PROTECTED_TAIL_CHARS) {
            tailStart--
            tailChars += messages.getJSONObject(tailStart).toString().length
        }
        val latestUser = (systemCount until messages.length()).lastOrNull {
            messages.getJSONObject(it).optString("role") == "user" && !messages.getJSONObject(it).optBoolean("_eta_observation")
        } ?: systemCount
        val bound = minOf(tailStart, assistants[assistants.size - PROTECTED_ASSISTANTS], latestUser)
        val end = splits.lastOrNull { it <= bound } ?: return FoldResult()
        var changed = 0
        var savedTokens = 0L
        for (index in systemCount until end) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "tool") continue
            val content = message.opt("content") as? String ?: continue // Never rewrite image/structured provider blocks.
            if (content.length <= LARGE_OUTPUT_CHARS) continue
            val source = if (content.trimStart().startsWith('{')) runCatching { JSONObject(content) }.getOrNull() else null
            if (source?.optBoolean("older_tool_output_folded") == true) continue
            // appendMessage shares objects with the append-only transcript. Replace the
            // array slot with a copy; mutating message.content would also mutate journal.
            val copy = JSONObject()
            message.keys().forEach { key -> copy.put(key, message.opt(key)) }
            val readFile = toolNames[message.optString("tool_call_id")] == "read_file" || source?.optString("tool") == "read_file"
            copy.put("content", foldedContent(content, readFile, source))
            val saving = AgentTokenEstimator.json(message) - AgentTokenEstimator.json(copy)
            if (saving <= 0 || copy.toString().length >= message.toString().length) continue
            messages.put(index, copy)
            changed++
            savedTokens += saving
        }
        return FoldResult(changed, AgentTokenEstimator.bounded(savedTokens))
    }

    private fun foldedContent(content: String, readFile: Boolean, source: JSONObject?): String {
        val envelope = JSONObject()
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
        var high = if (readFile) 3_000 else 1_200
        while (low < high) {
            val size = (low + high + 1) / 2
            envelope.put("preview", preview(content, size, readFile))
            if (envelope.toString().length <= (if (readFile) MAX_READ_FILE_FOLDED_CHARS else MAX_FOLDED_CHARS)) low = size else high = size - 1
        }
        return envelope.put("preview", preview(content, low, readFile)).toString()
    }

    private fun preview(text: String, size: Int, readFile: Boolean): String {
        var head = size / 2
        var tail = size - head
        if (head > 0 && text[head - 1].isHighSurrogate()) head--
        if (tail > 0 && text[text.length - tail].isLowSurrogate()) tail--
        val hint = if (readFile) {
            "已折叠旧文件输出，需要细节时用 offset/limit 分段重读该文件（read_file 使用 offset_bytes/max_lines/max_bytes，续读带 expected_revision）"
        } else "已折叠旧轮次输出，需要细节时按范围查询"
        return text.take(head) + "\n[... $hint；省略 ${text.length - head - tail} 字符 ...]\n" + text.takeLast(tail)
    }
}
