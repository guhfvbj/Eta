package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentContinuationBaseStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun base(id: String = "conversation", savedAt: Long = System.currentTimeMillis()): AgentContinuationBaseStore.Base {
        val request = AgentRuntimeWire.RunRequest("run", "原任务", AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "不能落盘的密钥", model = "model", systemPrompt = "敏感配置"),
            emptyList(), history = listOf(AgentModelClient.ConversationMessage("user", "历史")),
            handoff = AgentRuntimeWire.EntryHandoff("run", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                AgentUiHandoffPayload(id).toJson()), modelSessionId = "session")
        return AgentContinuationBaseStore.Base(id, "run", request,
            AgentContextSnapshot(operationId = "run", messages = request.history),
            listOf(AgentModelClient.ConversationMessage("assistant", "原结果")), savedAt)
    }

    @Test fun saveAndLoadPreserveContinuationContentWithoutConfig() {
        val store = AgentContinuationBaseStore(temporary.root)
        val original = base()
        store.save(original)
        val loaded = AgentContinuationBaseStore(temporary.root).load(original.conversationId)!!
        assertEquals(original.conversationId, loaded.conversationId)
        assertEquals(original.runId, loaded.runId)
        assertEquals(original.snapshot, loaded.snapshot)
        assertEquals(original.transcript, loaded.transcript)
        assertEquals(original.savedAt, loaded.savedAt)
        assertEquals(original.request.history, loaded.request.history)
        assertEquals(original.request.prompt, loaded.request.prompt)
        assertEquals(original.request.handoff, loaded.request.handoff)
        assertEquals("session", loaded.request.effectiveModelSessionId)
        assertEquals("", loaded.request.config.apiKey)
        store.remove(original.conversationId)
        assertNull(store.load(original.conversationId))
    }

    @Test fun newerBaseReplacesTheSameConversationAtomically() {
        val store = AgentContinuationBaseStore(temporary.root)
        val first = base()
        store.save(first)
        val newer = first.copy(request = first.request.copy(prompt = "新任务"), savedAt = first.savedAt + 1)
        store.save(newer)
        assertEquals("新任务", store.load("conversation")!!.request.prompt)
        assertEquals(newer.savedAt, store.load("conversation")!!.savedAt)
        assertEquals(1, temporary.root.listFiles().orEmpty().size)
    }

    @Test fun oversizedBaseIsRejectedAndDoesNotKeepStaleBase() {
        val store = AgentContinuationBaseStore(temporary.root)
        val base = base()
        store.save(base)
        store.save(base.copy(request = base.request.copy(prompt = "大".repeat(512 * 1024))))
        assertNull(store.load(base.conversationId))
        assertTrue(temporary.root.listFiles().orEmpty().none { it.extension == "json" })
    }

    @Test fun ninthConversationEvictsOldestSavedAt() {
        val store = AgentContinuationBaseStore(temporary.root)
        val now = System.currentTimeMillis()
        listOf(8, 2, 6, 1, 7, 3, 5, 4, 9).forEach { store.save(base("c$it", now - 100 + it)) }
        assertNull(store.load("c1"))
        (2..9).forEach { assertEquals("c$it", store.load("c$it")?.conversationId) }
        assertEquals(8, temporary.root.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test fun expiredBaseReturnsNullAndDeletesFile() {
        val store = AgentContinuationBaseStore(temporary.root)
        store.save(base(savedAt = System.currentTimeMillis() - 24 * 60 * 60 * 1000L - 1))
        assertNull(store.load("conversation"))
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun corruptJsonReturnsNullAndDeletesFile() {
        val store = AgentContinuationBaseStore(temporary.root)
        store.save(base())
        val file = temporary.root.listFiles()!!.single()
        file.writeText("{坏文件")
        assertNull(store.load("conversation"))
        assertFalse(file.exists())
    }

    @Test fun serializedBaseContainsNoCredentialFieldsOrConfigValues() {
        val store = AgentContinuationBaseStore(temporary.root)
        store.save(base())
        val stored = temporary.root.listFiles()!!.single().readText()
        listOf("apiKey", "token", "authorization", "config", "不能落盘的密钥", "敏感配置", "example.invalid")
            .forEach { assertFalse("不应序列化 $it", stored.contains(it, ignoreCase = true)) }
    }

    @Test fun conversationIdsCannotEscapeThePrivateDirectory() {
        val root = File(temporary.root, "bases")
        val store = AgentContinuationBaseStore(root)
        store.save(base("../../outside"))
        assertEquals("../../outside", store.load("../../outside")?.conversationId)
        assertEquals(root.canonicalFile, root.listFiles()!!.single().canonicalFile.parentFile)
    }
}
