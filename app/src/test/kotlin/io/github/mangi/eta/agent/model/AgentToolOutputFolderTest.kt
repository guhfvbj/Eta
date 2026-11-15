package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentToolOutputFolderTest {
    @Test fun foldsOnlyOldLargeResultsAndKeepsJournalAndPairingIntact() {
        val messages = history()
        val journal = JSONArray((0 until messages.length()).map(messages::getJSONObject))
        val original = journal.toString()
        val pairing = AgentConversationCodec.transcript(messages, 1).map { it.role to it.toolCallId }
        assertTrue(AgentToolOutputFolder.fold(messages, 1).changedMessages > 0)
        assertEquals(original, journal.toString())
        assertEquals(pairing, AgentConversationCodec.transcript(messages, 1).map { it.role to it.toolCallId })
        assertTrue(messages.getJSONObject(3).getString("content").length <= AgentToolOutputFolder.MAX_READ_FILE_FOLDED_CHARS)
        for (i in 6..8) assertEquals(journal.getJSONObject(i * 2 + 1).toString(), messages.getJSONObject(i * 2 + 1).toString())
        for (i in 0 until messages.length()) if (messages.getJSONObject(i).getString("role") != "tool") {
            assertSame(journal.getJSONObject(i), messages.getJSONObject(i))
        }
        assertEquals(0, AgentToolOutputFolder.fold(messages, 1).changedMessages)
    }

    @Test fun currentUserTurnIsNeverFoldedEvenAfterHundredsOfTools() {
        val messages = history(rounds = 200, endTurn = false)
        val before = messages.toString()
        assertEquals(0, AgentToolOutputFolder.fold(messages, 1).changedMessages)
        assertEquals(before, messages.toString())
    }

    @Test fun readFileKeepsThreeThousandCharactersAndRangeMetadata() {
        val content = JSONObject().put("tool", "read_file").put("path", "src/File.kt")
            .put("start_line", 10).put("end_line", 900).put("offset", 9).put("limit", 891)
            .put("content", "CODE".repeat(3_000)).toString()
        val messages = history(output = content)
        val before = AgentTokenEstimator.messages(messages)
        val folded = AgentToolOutputFolder.fold(messages, 1)
        assertTrue(folded.changedMessages > 0)
        assertEquals(before - AgentTokenEstimator.messages(messages), folded.savedTokens)
        val result = JSONObject(messages.getJSONObject(3).getString("content"))
        assertEquals("src/File.kt", result.getString("path"))
        assertEquals(10, result.getInt("start_line"))
        assertEquals(900, result.getInt("end_line"))
        assertEquals(9, result.getInt("offset"))
        assertEquals(891, result.getInt("limit"))
        assertTrue(result.getString("preview").length >= 3_000)
        assertTrue(result.getString("preview").contains("offset/limit 分段重读"))
    }

    @Test fun protectsAtLeast24KOfTailAndDoesNotFoldSmallOrStructuredContent() {
        val short = history(output = "x".repeat(3_001))
        assertEquals(0, AgentToolOutputFolder.fold(short, 1).changedMessages)
        val messages = history(rounds = 12)
        val structured = JSONArray().put(JSONObject().put("type", "text").put("text", "image/text".repeat(2_000)))
        messages.getJSONObject(3).put("content", structured)
        messages.getJSONObject(5).put("content", "s".repeat(3_000))
        assertTrue(AgentToolOutputFolder.fold(messages, 1).changedMessages > 0)
        assertSame(structured, messages.getJSONObject(3).getJSONArray("content"))
        assertEquals("s".repeat(3_000), messages.getJSONObject(5).getString("content"))
    }

    @Test fun serialisedEnvelopeIsBoundedAndPreservesExactMetadataAndUnicodeHeadTail() {
        val content = JSONObject().put("ok", false).put("tool", "read_file").put("path", "/a'b\nfile")
            .put("revision", "opaque-exact").put("next_offset_bytes", 16_000).put("code", "PARTIAL")
            .put("partial", true).put("next_cursor", "opaque-cursor")
            .put("content", "HEAD😀" + "\"\\\n\t😀".repeat(3_000) + "TAIL😀").toString()
        val messages = history(output = content)
        AgentToolOutputFolder.fold(messages, 1)
        val raw = messages.getJSONObject(3).getString("content")
        assertTrue(raw.length <= AgentToolOutputFolder.MAX_READ_FILE_FOLDED_CHARS)
        val folded = JSONObject(raw)
        assertEquals("opaque-exact", folded.getString("revision"))
        assertEquals("opaque-cursor", folded.getString("next_cursor"))
        assertEquals(16_000, folded.getInt("next_offset_bytes"))
        assertTrue(folded.getBoolean("partial"))
        val text = folded.getString("preview")
        assertTrue(content.startsWith(text.substringBefore("\n[...")))
        assertTrue(content.endsWith(text.substringAfter("...]\n")))
        text.forEachIndexed { i, c ->
            if (c.isHighSurrogate()) assertTrue(i + 1 < text.length && text[i + 1].isLowSurrogate())
            if (c.isLowSurrogate()) assertTrue(i > 0 && text[i - 1].isHighSurrogate())
        }
    }

    @Test fun oversizedMetadataNeverExpandsTheEnvelopeOrTruncatesAnOpaqueRevision() {
        val content = JSONObject().put("revision", "r".repeat(4_000)).put("path", "p".repeat(4_000))
            .put("content", "body".repeat(4_000)).toString()
        val messages = history(output = content)
        AgentToolOutputFolder.fold(messages, 1)
        val raw = messages.getJSONObject(3).getString("content")
        assertTrue(raw.length <= AgentToolOutputFolder.MAX_READ_FILE_FOLDED_CHARS)
        assertFalse(JSONObject(raw).has("revision"))
        assertFalse(JSONObject(raw).has("path"))
    }

    @Test fun foldingIsMonotonicAndLeavesPreviouslyFoldedPrefixByteStable() {
        val messages = history()
        val folded = linkedMapOf<Int, String>()
        repeat(6) { turn ->
            AgentToolOutputFolder.fold(messages, 1)
            folded.forEach { (index, raw) -> assertEquals(raw, messages.getJSONObject(index).toString()) }
            for (i in 1 until messages.length()) {
                val message = messages.getJSONObject(i)
                if (message.optString("role") == "tool" && message.optString("content").contains("older_tool_output_folded")) {
                    folded[i] = message.toString()
                }
            }
            appendExchange(messages, 9 + turn, "z".repeat(8_000))
            messages.put(AgentConversationCodec.userTextMessage("next $turn"))
        }
        assertTrue(folded.size >= 8)
    }

    @Test fun closedBatchBoundaryProtectsAllSiblingToolResults() {
        val messages = history(rounds = 4, output = "x".repeat(10_000))
        val assistant = messages.getJSONObject(4)
        val call = AgentModelClient.ToolCall("sibling", "read_file", "{}")
        assistant.getJSONArray("tool_calls").put(JSONObject().put("id", call.id).put("type", "function")
            .put("function", JSONObject().put("name", call.name).put("arguments", "{}")))
        val sibling = AgentConversationCodec.toolResultMessage(call, AgentModelClient.ToolResult("s".repeat(10_000)))
        val expanded = JSONArray()
        for (i in 0 until messages.length()) {
            expanded.put(messages.getJSONObject(i))
            if (i == 5) expanded.put(sibling)
        }
        val before = expanded.toString()
        AgentToolOutputFolder.fold(expanded, 1)
        // The second assistant is in the protected three; both its results survive exactly.
        assertEquals(messages.getJSONObject(5).toString(), expanded.getJSONObject(5).toString())
        assertSame(sibling, expanded.getJSONObject(6))
        assertNotEquals(before, expanded.toString())
    }

    @Test fun incompleteOrMalformedToolBatchesDisableFolding() {
        for (kind in listOf("missing", "orphan", "interleaved")) {
            val messages = history(endTurn = false)
            when (kind) {
                "missing" -> messages.remove(messages.length() - 1)
                "orphan" -> messages.getJSONObject(3).put("tool_call_id", "not-a-call")
                else -> messages.getJSONObject(3).put("role", "user")
            }
            val before = messages.toString()
            assertEquals(kind, 0, AgentToolOutputFolder.fold(messages, 1).changedMessages)
            assertEquals(before, messages.toString())
        }
    }

    @Test fun allSignedProviderChainsLockTheWholePrefixUntilTheChainEnds() {
        for (provider in 0..2) {
            val messages = history()
            messages.remove(messages.length() - 1) // 签名工具链仍在当前用户轮次
            val signed = messages.getJSONObject(messages.length() - 2)
            when (provider) {
                0 -> AnthropicEphemeralState.attachContentBlocks(signed, JSONArray().put(JSONObject()
                    .put("type", "thinking").put("signature", "exact-signature")))
                1 -> GeminiEphemeralState.attachSteps(signed, JSONArray().put(JSONObject()
                    .put("type", "function_call").put("thought_signature", "exact-signature")))
                2 -> ResponsesEphemeralState.attachOutputItems(signed, JSONArray().put(JSONObject()
                    .put("type", "function_call").put("call_id", "call-8")))
            }
            val before = messages.toString()
            val signedBefore = signed.toString()
            assertEquals(0, AgentToolOutputFolder.fold(messages, 1).changedMessages)
            assertEquals(before, messages.toString())
            messages.put(JSONObject().put("role", "assistant").put("content", "done"))
            messages.put(AgentConversationCodec.userTextMessage("next"))
            assertTrue(AgentToolOutputFolder.fold(messages, 1).changedMessages > 0)
            assertEquals(signedBefore, signed.toString())
        }
    }

    @Test fun sessionRetainsObservedUsageWhenFoldingAndSavesAMatchingBaseline() {
        val messages = history(rounds = 3, endTurn = false)
        val journal = JSONArray((0 until messages.length()).map(messages::getJSONObject))
        val journalBefore = journal.toString()
        val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "key", model = "fixture", systemPrompt = "",
            autoCompactionEnabled = false)
        val provider = object : AgentProviderClient {
            override val id = "unused"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, true)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse = error("Folding is local")
        }
        val session = AgentContextSession(config, messages, 1, "fold", provider, AgentRunController(),
            { emptySet() }, {}, {}, { journal.length() })
        session.prepareRequest(JSONArray())
        session.captureRequest()
        session.observeInputTokens(90_000)
        assertEquals(90_000, session.effectiveTokens())
        session.prepareRequest(JSONArray())
        assertEquals(90_000, session.effectiveTokens())
        for (i in 4..8) appendExchange(messages, i, "x".repeat(8_000))
        messages.put(AgentConversationCodec.userTextMessage("next"))
        val expected = JSONArray(messages.toString())
        val saved = AgentToolOutputFolder.fold(expected, 1).savedTokens
        val beforeFold = session.effectiveTokens()
        session.prepareRequest(JSONArray())
        assertEquals(beforeFold - saved, session.effectiveTokens())
        session.checkpoint()
        assertTrue(session.snapshot()!!.tokenBaseline!!.matches(session.snapshot()!!.messages, config))
        assertTrue(session.effectiveTokens() > AgentTokenEstimator.messages(messages))
        session.captureRequest()
        session.observeInputTokens(12_345)
        session.prepareRequest(JSONArray())
        assertEquals(12_345, session.effectiveTokens())
        session.checkpoint()
        val snapshot = checkNotNull(session.snapshot())
        val baseline = checkNotNull(snapshot.tokenBaseline)
        assertEquals(12_345, baseline.lastContextTokens)
        assertTrue(snapshot.messages.any { it.role == "tool" && it.content.contains("older_tool_output_folded") })
        assertTrue(baseline.matches(snapshot.messages, config))
        assertEquals(journalBefore, journal.toString())
        assertEquals(journal.length(), snapshot.consumedTranscriptMessages)
    }

    private fun history(rounds: Int = 8, output: String = "x".repeat(8_000), endTurn: Boolean = true) = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "fixed"))
        .put(AgentConversationCodec.userTextMessage("one task")).also { messages ->
            for (i in 1..rounds) appendExchange(messages, i, output)
            if (endTurn) messages.put(AgentConversationCodec.userTextMessage("next task"))
        }

    private fun appendExchange(messages: JSONArray, round: Int, output: String) {
        val call = AgentModelClient.ToolCall("call-$round", "read_file", "{}")
        messages.put(AgentConversationCodec.assistantHistoryMessage(JSONObject().put("content", ""), listOf(call)))
        messages.put(AgentConversationCodec.toolResultMessage(call, AgentModelClient.ToolResult(output)))
    }
}
