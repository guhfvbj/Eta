package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** Removes optional reasoning and extracts a balanced JSON object without confusing quoted braces. */
internal object ModelFeatureJson {
    fun withoutThinking(raw: String): String = raw.replace(Regex("(?is)<think(?:ing)?>.*?</think(?:ing)?>"), "" ).replace(Regex("(?is)<think(?:ing)?>.*$"), "").trim()
    fun objectFrom(raw: String): JSONObject {
        val text = withoutThinking(raw)
        runCatching { JSONObject(text) }.getOrNull()?.let { return it }
        val start = text.indexOf('{')
        require(start >= 0) { "模型未返回 JSON 对象" }
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escaped) { escaped = false; continue }
            if (quoted && ch == '\\') { escaped = true; continue }
            if (ch == '"') { quoted = !quoted; continue }
            if (quoted) continue
            if (ch == '{') depth++
            if (ch == '}' && --depth == 0) return JSONObject(text.substring(start, i + 1))
        }
        error("模型返回的 JSON 不完整")
    }
}
