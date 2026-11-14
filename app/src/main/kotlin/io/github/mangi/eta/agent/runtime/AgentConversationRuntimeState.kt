package io.github.mangi.eta.agent.runtime

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentContextTokenBaseline
import io.github.mangi.eta.agent.subagent.SubagentSessionStore
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Independently updated metadata; UI history saves preserve this checkpoint field. */
@Serializable
internal data class AgentConversationRuntimeState(
    val version: Int = 1,
    val tokenBaseline: AgentContextTokenBaseline? = null,
    val subagents: List<SubagentSessionStore.StoredSession> = emptyList(),
) {
    fun encode(): String = codec.encodeToString(this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }
        fun decode(raw: String?): AgentConversationRuntimeState = if (raw.isNullOrBlank()) {
            AgentConversationRuntimeState()
        } else codec.decodeFromString<AgentConversationRuntimeState>(raw).also { require(it.version == 1) }
    }
}

internal object AgentConversationRuntimeStore {
    fun load(context: Context, conversationId: String): AgentConversationRuntimeState = runBlocking(Dispatchers.IO) {
        val database = EtaDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationDao()
            val row = dao.contextCheckpointRow(conversationId)
            AgentConversationRuntimeState.decode(row?.let {
                dao.restoreText("conversation_context_checkpoints", conversationId, "runtime", it.runtimeStateJson)
            })
        }
    }

    fun update(context: Context, conversationId: String,
        transform: (AgentConversationRuntimeState) -> AgentConversationRuntimeState,
    ) = runBlocking(Dispatchers.IO) {
        val database = EtaDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationDao()
            val row = dao.contextCheckpointRow(conversationId)
            val previous = AgentConversationRuntimeState.decode(row?.let {
                dao.restoreText("conversation_context_checkpoints", conversationId, "runtime", it.runtimeStateJson)
            })
            dao.updateRuntimeState(conversationId, transform(previous).encode())
        }
    }
}
