package io.github.mangi.eta.agent.openviking

/** Only text with explicit provenance enters the queue. Never accepts a transcript/tool message. */
internal object OpenVikingCaptureSanitizer {
    const val VERSION = 1
    const val MAX_CHARS = 8_000
    private val blocks = Regex("<(remote_memory|memory_core|memory_headings|openviking-context|relevant-memories|screen_context|assistant_screen_context|tool_result|tool_output)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val token = Regex("\\b(?:sk-[A-Za-z0-9_-]{8,}|AKIA[A-Z0-9]{16}|gh[pousr]_[A-Za-z0-9]{16,}|glpat-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{30,}|xox[baprs]-[A-Za-z0-9-]{16,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b")
    private val credentialLine = Regex("(?im)((?:cookie|set-cookie|refresh[_-]?token|session[_-]?token|client[_-]?secret|(?:aws_)?secret[_-]?access[_-]?key|access[_-]?key(?:[_-]?(?:id|secret))?)[\"']?\\s*[:=]\\s*[\"']?)[^\\r\\n;\"'<>]+")
    private val cookies = Regex("(?i)((?:set-cookie|cookie)[\"']?\\s*[:=]\\s*[\"']?)[^\\r\\n\"'<>]+")
    private val uriCredentials = Regex("(?i)(\\b[a-z][a-z0-9+.-]*://)[^\\s/@]+:[^\\s/@]+@")
    private val noise = Regex("(?is)^(?:ok|okay|thanks|thank you|你好|好的|好|谢谢|嗯|继续|已停止|已取消|\\[.*(?:omitted|redacted|隐藏|省略).*])[,!.。！\\s]*$")
    private val errorStub = Regex("(?i)^(?:(?:error|exception|model_empty_response|model_output_limit)[:：\\s]|工具执行已结束|模型未返回|操作失败|已停止|已取消)")
    private val transient = Regex("(?im)(?:<hierarchy\\b|<node\\b[^>]*(?:bounds|resource-id)|\"(?:ui_nodes|accessibility_tree|observation_id)\"\\s*:|^\\s*(?:\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}|Traceback \\(most recent call last\\)|at [\\w.$]+\\([^)]*:[0-9]+\\)|\\$ (?:adb|cat|dumpsys)\\b))")

    fun clean(text: String, credential: String = "", assistant: Boolean = false, tainted: Boolean = false): String? {
        if (assistant && tainted || text.isBlank() || text.length > 32_000) return null
        var value = text
        repeat(8) { value = value.replace(blocks, "") }
        value = value.substringBefore("以下是系统在本次助理唤醒时提供的屏幕与应用数据")
        // An unfinished injection delimiter is never accepted as ordinary text.
        if (Regex("(?i)<(?:remote_memory|memory_core|memory_headings|openviking-context|relevant-memories|screen_context|assistant_screen_context|tool_result|tool_output)\\b").containsMatchIn(value)) return null
        value = OpenVikingTextSafety.clean(value, credential).replace(bearer, "Bearer [redacted]").replace(token, "[redacted]")
            .replace(credentialLine) { it.groupValues[1] + "[redacted]" }
            .replace(cookies) { it.groupValues[1] + "[redacted]" }
            .replace(uriCredentials) { it.groupValues[1] + "[redacted]@" }
        if (Regex("-----BEGIN [^-]*PRIVATE KEY-----").containsMatchIn(value)) return null
        if (transient.containsMatchIn(value) || value.length > MAX_CHARS || value.toByteArray(Charsets.UTF_8).size > 12_000) return null
        if (value.length < 3 || noise.matches(value) || value.count(Char::isLetterOrDigit) < 2) return null
        if (errorStub.containsMatchIn(value)) return null
        return value
    }
}
