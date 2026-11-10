package io.github.mangi.eta.agent.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Serializable
internal data class SubagentStepSnapshot(
    val id: String,
    val kind: String,
    val title: String = "",
    val detail: String = "",
    val status: String = STATUS_RUNNING,
    val toolName: String = "",
) {
    companion object {
        const val KIND_THINKING = "thinking"
        const val KIND_TOOL = "tool"

        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * Subagent 实时与持久化的结构化活动快照。
 *
 * 保存在父级 `subagent` 工具消息的 `command` 字段中，既支持运行中点击实时检视，
 * 也支持会话归档与重启后直接点开回看完整内部过程。
 */
@Serializable
internal data class SubagentActivitySnapshot(
    val subagentId: String = "",
    val subagentType: String = SubagentProfile.DEFAULT_TYPE,
    val description: String = "",
    val prompt: String = "",
    val model: String = "",
    /** 实际使用的思考强度（wire 值）；空表示该模型未启用或用了默认。 */
    val effort: String = "",
    val resumed: Boolean = false,
    val status: String = STATUS_RUNNING,
    val currentAction: String = "",
    val steps: List<SubagentStepSnapshot> = emptyList(),
    val output: String = "",
    val error: String? = null,
) {
    val toolStepCount: Int
        get() = steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL }

    fun encode(): String {
        val full = json.encodeToString(this)
        if (full.length <= MAX_ENCODED_SNAPSHOT_CHARS) return full
        // 限制字段后重新序列化，不能直接截断 JSON；转义后的长度仍须复核。
        var compact = copy(
            subagentId = subagentId.clip(128),
            subagentType = subagentType.clip(80),
            description = description.clip(120),
            prompt = prompt.clip(2_000),
            model = model.clip(160),
            effort = effort.clip(32),
            status = status.clip(32),
            currentAction = currentAction.clip(160),
            steps = steps.takeLast(48).map { step ->
                step.copy(
                    id = step.id.clip(128),
                    kind = step.kind.clip(32),
                    title = step.title.clip(240),
                    detail = step.detail.clip(400),
                    status = step.status.clip(32),
                    toolName = step.toolName.clip(128),
                )
            },
            output = output.clip(12_000),
            error = error?.clip(1_000),
        )
        var encoded = json.encodeToString(compact)
        while (encoded.length > MAX_ENCODED_SNAPSHOT_CHARS) {
            compact = compact.copy(
                prompt = compact.prompt.clip(compact.prompt.length / 2),
                steps = compact.steps.takeLast((compact.steps.size / 2).coerceAtLeast(1)),
                output = compact.output.clip(compact.output.length / 2),
                error = compact.error?.let { it.clip(it.length / 2) },
            )
            encoded = json.encodeToString(compact)
        }
        return encoded
    }

    private fun String.clip(maxChars: Int): String {
        var end = length.coerceAtMost(maxChars)
        if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
        return substring(0, end)
    }

    companion object {
        private const val MAX_ENCODED_SNAPSHOT_CHARS = 48_000
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        private const val SNAPSHOT_PREFIX = "{\"subagentId\":"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String?): SubagentActivitySnapshot? {
            if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
            return runCatching { json.decodeFromString<SubagentActivitySnapshot>(raw) }
                .getOrNull()
                ?.takeIf { it.subagentType.isNotBlank() || it.description.isNotBlank() || it.prompt.isNotBlank() }
        }

        fun initialFromArguments(argumentsJson: String): SubagentActivitySnapshot? = runCatching {
            val args = JSONObject(argumentsJson)
            val resumeId = args.optString("resume").trim()
            val rawType = args.optString("subagent_type").trim().lowercase()
            val type = when {
                rawType.isNotEmpty() -> rawType
                resumeId.isNotEmpty() -> "subagent"
                else -> SubagentProfile.DEFAULT_TYPE
            }
            val description = args.optString("description").trim()
                .replace(Regex("\\s+"), " ")
                .take(80)
            val prompt = args.optString("prompt").trim().take(4_000)
            SubagentActivitySnapshot(
                subagentId = resumeId,
                subagentType = type,
                description = description,
                prompt = prompt,
                resumed = resumeId.isNotEmpty(),
                status = STATUS_RUNNING,
                currentAction = "正在启动…",
            )
        }.getOrNull()
    }
}
