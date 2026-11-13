package io.github.mangi.eta.agent.model

/**
 * 工具级关键词搜索（设计移植自 ZCode 的 ToolSearch 本地投影）：
 * 对未打开的 MCP 服务器工具按名字/描述评分排序，命中后下一轮开放所属服务器。
 *
 * 候选范围由调用方按策略和投影过滤，这里只做纯文本匹配。
 *
 * 只返回目录里的名字，不执行目标工具；关键词不按正则解释，
 * `|`、`.*` 之类字符一律按字面处理。
 */
internal object AgentToolSearch {
    const val DEFAULT_RESULTS = 5
    private val MCP_NAME = Regex("""^mcp_(.+?)_(.+?)_[0-9a-f]{4,8}$""")
    private fun parts(name: String): List<String> = name
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .lowercase().split(Regex("[\\s_.-]+")).filter(String::isNotEmpty)

    /** 一个可搜索的延迟工具：名字、描述与所属延迟组（用于命中后整组解锁）。 */
    data class Candidate(
        val name: String,
        val description: String?,
        /** 组索引摘要之外可参与匹配的补充关键词；null 表示没有。 */
        val searchHint: String?,
        val mcp: Boolean,
        val groupId: String,
    )

    data class Hit(
        val name: String,
        val groupId: String,
        val score: Int,
    )

    private class Tokenized(
        val coarse: List<String>,
        val parts: List<String>,
        val full: String,
        val mcp: Boolean,
    )

    private fun tokenize(candidate: Candidate): Tokenized {
        val name = candidate.name
        val mcpMatch = if (candidate.mcp) MCP_NAME.find(name) else null
        return if (mcpMatch != null) {
            val (server, tool) = mcpMatch.destructured
            val coarse = listOf(server.lowercase(), tool.lowercase())
            val parts = listOf(server, tool).flatMap(::parts)
            Tokenized(coarse, parts, parts.joinToString(" "), mcp = true)
        } else {
            // camelCase 拆词 + snake/kebab 分隔符拆词，保证「发送文本」能命中 sendText。
            val parts = parts(name)
            Tokenized(listOf(name.lowercase()), parts, parts.joinToString(" "), mcp = false)
        }
    }

    /**
     * 对 [candidates] 评分并返回最高分的若干命中，分数降序、同分按名字稳定排序。
     *
     * 查询语法（与 ZCode 对齐）：
     * - `select:name1,name2`：按模型工具名精确选择，不做模糊匹配；
     * - `+term`：term 必须在名字分词或描述里出现，其余词只参与排序；
     * - 空格分隔关键词：全部词都要命中（AND），按综合得分排序。
     */
    fun search(
        query: String,
        candidates: List<Candidate>,
        maxResults: Int = DEFAULT_RESULTS,
    ): List<Hit> {
        if (candidates.isEmpty()) return emptyList()
        val limit = maxResults.coerceIn(1, 20)
        val select = Regex("""^select:(.+)$""", RegexOption.IGNORE_CASE).find(query.trim())
        if (select != null) {
            val byName = candidates.associateBy { it.name.lowercase() }
            return select.groupValues[1].split(',')
                .mapNotNull { requested ->
                    val name = requested.trim().lowercase()
                    byName[name]?.let { Hit(it.name, it.groupId, Int.MAX_VALUE) }
                }
                .distinctBy { it.name }
                .take(limit)
        }
        val text = query.lowercase().trim()
        if (text.isEmpty()) return emptyList()
        // MCP 工具名前缀直达：模型看到组索引里的 mcp_ 名后常会直接抄前缀。
        if (text.startsWith("mcp_")) {
            val prefixHits = candidates
                .filter { it.name.lowercase().startsWith(text) }
                .take(limit)
                .map { Hit(it.name, it.groupId, Int.MAX_VALUE) }
            if (prefixHits.isNotEmpty()) return prefixHits
        }
        val terms = text.split(Regex("\\s+")).filter(String::isNotEmpty)
        val required = terms.filter { it.length > 1 && it.startsWith("+") }.map { it.drop(1) }
        val keywords = if (required.isEmpty()) terms else required + terms.filter { it.length <= 1 || !it.startsWith("+") }
        val patterns = keywords.associateWith { term -> Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{N}_])") }
        return candidates.asSequence()
            .map { candidate -> candidate to tokenize(candidate) }
            .mapNotNull { (candidate, tokenized) ->
                val description = candidate.description?.lowercase().orEmpty()
                val hint = candidate.searchHint?.lowercase().orEmpty()
                // 必含词不满足直接淘汰：这是模型表达「必须是这类工具」的手段。
                val mandatory = if (required.isEmpty()) keywords else required
                val matched = mandatory.all { term ->
                    tokenized.parts.any { part -> part.contains(term) } ||
                        tokenized.coarse.any { part -> part.contains(term) } ||
                        patterns[term]?.containsMatchIn(description) == true ||
                        patterns[term]?.containsMatchIn(hint) == true
                }
                if (!matched) return@mapNotNull null
                var score = 0
                for (term in keywords) {
                    val mcpBonus = if (tokenized.mcp) 2 else 0
                    when {
                        tokenized.parts.contains(term) -> score += 10 + mcpBonus
                        tokenized.parts.any { part -> part.contains(term) } -> score += 5 + mcpBonus
                    }
                    when {
                        tokenized.coarse.contains(term) -> score += 10 + mcpBonus
                        tokenized.coarse.any { part -> part.contains(term) } -> score += 3 + mcpBonus
                    }
                    if (tokenized.full.contains(term) && score == 0) score += 3
                    if (hint.isNotEmpty() && patterns[term]?.containsMatchIn(hint) == true) score += 4
                    if (patterns[term]?.containsMatchIn(description) == true) score += 2
                }
                if (score <= 0) null else Hit(candidate.name, candidate.groupId, score)
            }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy(Hit::name))
            .take(limit)
            .toList()
    }
}

