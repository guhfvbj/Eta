package io.github.mangi.eta.data.repository

import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.model.AgentTokenEstimator
import io.github.mangi.eta.agent.runtime.AgentConversationRuntimeState
import io.github.mangi.eta.agent.roleplay.RoleplayHistoryProjection
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.openTextStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 调用方将创建操作纳入会话保存队列，数据库事务保证前缀和上下文一起提交。 */
internal object ConversationBranchRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PAGE_SIZE = 128

    suspend fun create(
        database: EtaDatabase,
        sourceId: String,
        targetMessageId: String,
        branchId: String,
        title: String,
        now: Long,
    ) = database.withTransaction {
        val dao = database.conversationDao()
        val source = dao.conversation(sourceId) ?: throw ConversationBranchUnavailable()
        check(dao.conversationMetadataRow(branchId) == null) { "conversation_branch_id_exists" }
        val outline = dao.branchMessages(sourceId)
        val target = outline.firstOrNull { it.id == targetMessageId } ?: throw ConversationBranchUnavailable()
        val roleplay = source.revisionsJson.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString<RoleplayMessageState>(it) } ?: RoleplayMessageState()
        val checkpoint = dao.contextCheckpointRow(sourceId) ?: throw ConversationBranchUnavailable()
        val journal = ConversationBranchBoundary.resolve(outline,
            loadCheckpoint(dao, sourceId, "journal", checkpoint.journalJson), roleplay, targetMessageId)
        val compactedHistory = ConversationBranchBoundary.compactedPrefix(outline,
            loadCheckpoint(dao, sourceId, "history", checkpoint.historyJson), roleplay, targetMessageId)
        val retainedIds = outline.asSequence().filter { it.sortIndex <= target.sortIndex }.map { it.id }.toSet()
        val transcriptIds = journal.mapTo(mutableSetOf()) { it.messageId }
        val links = roleplay.links.filterKeys { it in retainedIds }
        if (links.values.any { it.transcriptMessageId !in transcriptIds }) throw ConversationBranchUnavailable()
        val revisions = roleplay.revisions.filterKeys { it in links }
        val retainedRoleplay = RoleplayMessageState(links = links, revisions = revisions)
        // Stored history is already the roleplay projection. Re-projecting it would add
        // duplicate notices (and rebuilding from journal would undo compaction).
        val history = compactedHistory ?: if (source.roleplayJson.isBlank()) journal else {
            val changedTranscripts = revisions.keys.mapNotNullTo(mutableSetOf()) { links[it]?.transcriptMessageId }
            val needed = links.filterValues { it.transcriptMessageId in changedTranscripts }.keys
            val contents = buildMap {
                needed.toList().chunked(PAGE_SIZE).forEach { ids ->
                    dao.messageContents(sourceId, ids).forEach { row ->
                        put(row.id, dao.restoreText("conversation_messages", row.id, "content", row.content))
                    }
                }
            }
            RoleplayHistoryProjection.project(journal, retainedRoleplay) { id -> revisions[id]?.content ?: contents[id] }
                ?: throw ConversationBranchUnavailable()
        }
        fun remap(id: String) = ConversationBranchBoundary.remapId(branchId, id)
        val remappedRoleplay = retainedRoleplay.copy(
            links = links.entries.associate { (id, link) -> remap(id) to link.copy(transcriptMessageId = remap(link.transcriptMessageId)) },
            revisions = revisions.entries.associate { (id, revision) -> remap(id) to revision },
        )
        dao.insertConversations(listOf(ConversationEntity(
            id = branchId, title = title, thinkingEnabled = source.thinkingEnabled,
            reasoningEffort = source.reasoningEffort, modelId = source.modelId,
            roleplayJson = source.roleplayJson,
            revisionsJson = if (source.roleplayJson.isBlank()) "" else json.encodeToString(remappedRoleplay),
            createdAt = now, updatedAt = now,
        )))
        val branchHistory = history.map { it.copy(messageId = remap(it.messageId)) }
        val runtime = AgentConversationRuntimeState.decode(dao.restoreText(
            "conversation_context_checkpoints", sourceId, "runtime", checkpoint.runtimeStateJson,
        ))
        // 指纹包含 messageId，必须按分支重建；保留父 usage 锚点，分支首轮响应后再观测校准。
        // 只继承 tokenBaseline：subagents 属于父会话，复制进分支会让分支误认领父会话的子智能体。
        // 分支可能建在会话中段，父锚点含被裁掉的后半段，按本地估算差值扣回，避免分支首轮就判定越线。
        val branchBaseline = runtime.tokenBaseline?.let { baseline ->
            val parentHistory = if (baseline.messageCount in 1..history.size) {
                history.take(baseline.messageCount)
            } else history
            val dropped = (AgentTokenEstimator.messages(parentHistory) -
                AgentTokenEstimator.messages(branchHistory)).coerceAtLeast(0)
            baseline.copy(
                lastContextTokens = (baseline.lastContextTokens - dropped).coerceAtLeast(0),
                messageCount = branchHistory.size,
                fingerprint = AgentTokenEstimator.fingerprint(branchHistory),
            )
        }
        val branchRuntime = AgentConversationRuntimeState(tokenBaseline = branchBaseline)
        dao.updateContextCheckpoint(
            branchId,
            AgentConversationCodec.encodeConversationCheckpoint(branchHistory),
            AgentConversationCodec.encodeTranscriptForStorage(journal.map { it.copy(messageId = remap(it.messageId)) }),
            branchRuntime.encode(),
        )
        var after = -1
        var index = 0
        while (true) {
            val page = dao.branchMessageRows(sourceId, after, target.sortIndex, PAGE_SIZE)
            if (page.isEmpty()) break
            val copies = page.map { stored ->
                val row = dao.restoreMessage(stored)
                row.copy(id = remap(row.id), conversationId = branchId, sortIndex = index++)
            }
            // restore/store 会为新 owner 重建长文本块，不能复制指向源消息的分块引用。
            dao.insertMessages(copies)
            after = page.last().sortIndex
        }
    }

    private fun loadCheckpoint(dao: ConversationDao, id: String, field: String, stored: String): List<ConversationMessage> {
        if (stored.isBlank()) return emptyList()
        return dao.openTextStream("conversation_context_checkpoints", id, field, stored).use {
            AgentConversationCodec.decodeTranscript(it)
        }
    }
}
