package io.github.mangi.eta.agent.model

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Advisory only: never suppress a read or replace its code with a cached result. */
internal class AgentReadRepeatGuard {
    private val counts = linkedMapOf<String, Int>()

    fun reset() = counts.clear()

    fun observe(name: String, result: AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (name != "read_file") return result
        val body = runCatching { JSONObject(result.content) }.getOrNull() ?: return result
        if (!body.optBoolean("ok") || body.optBoolean("older_tool_output_folded") ||
            !body.has("path") || !body.has("revision") || !body.has("content")) return result
        // Compare the actual observation, not call IDs or argument key ordering.
        // Different files, revisions and continuation windows have independent counts.
        val identity = JSONArray()
        for (key in listOf("environment", "identity", "path", "revision", "offset_bytes",
            "next_offset_bytes", "stop_reason", "content")) identity.put(body.opt(key))
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val count = (counts.remove(key) ?: 0) + 1
        counts[key] = count
        if (counts.size > 128) counts.remove(counts.keys.first())
        if (count !in setOf(3, 5, 8)) return result
        body.put("repeat_read_reminder", "同一文件版本与范围已读取 $count 次，返回内容未变化。请复用此前结果推进编辑或汇报；" +
            "若缺少其他范围，按 continuation 定点续读；只有文件变化或关键证据确实缺失时才重新读取。")
        return result.copy(content = body.toString())
    }
}
