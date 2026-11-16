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
        for (i in 7..14) assertEquals(journal.getJSONObject(i * 2 + 1).toString(), messages.getJSONObject(i * 2 + 1).toString())
        for (i in 0 until messages.length()) if (messages.getJSONObject(i).getString("role") != "tool") {
            assertSame(journal.getJSONObject(i), messages.getJSONObject(i))
        }
        assertEquals(0, AgentToolOutputFolder.fold(messages, 1).changedMessages)
    }

    @Test fun pressurePrunerCanFoldSameTurnButPreservesLastEightExchangesAnd120KTail() {
        // 12K 输出由字符尾部主导，20K 输出由最近八组交换主导；均按索引验证保护区。
        for (outputChars in listOf(12_000, 20_000)) {
            val rounds = 200
            val messages = history(rounds = rounds, output = "x".repeat(outputChars), endTurn = false)
            val before = (0 until messages.length()).map { messages.getJSONObject(it).toString() }
            var tailStart = messages.length()
            var tailChars = 0
            while (tailStart > 1 && tailChars < 120_000) tailChars += before[--tailStart].length
            val protectedAssistantStart = 2 + (rounds - 8) * 2
            assertTrue(AgentToolOutputFolder.fold(messages, 1).changedMessages > 0)
            assertTrue(JSONObject(messages.getJSONObject(3).getString("content")).getBoolean("older_tool_output_folded"))
            for (index in minOf(tailStart, protectedAssistantStart) until messages.length()) {
                assertEquals("protected message $index", before[index], messages.getJSONObject(index).toString())
            }
        }
    }

    @Test fun readFileKeepsThreeToSixThousandCharactersAndRangeMetadata() {
        val content = JSONObject().put("tool", "read_file").put("path", "src/File.kt")
            .put("start_line", 10).put("end_line", 900).put("offset", 9).put("limit", 891).put("total_chars", 12_000)
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
        assertEquals(12_000, result.getInt("total_chars"))
        assertTrue(result.getString("preview").length in 3_000..6_000)
        assertTrue(result.getString("preview").contains("不要从头重读"))
        assertFalse(result.getString("preview").contains("分段重读该文件"))
    }

    @Test fun protectsAtLeast120KOfTailAndDoesNotFoldSmallOrStructuredContent() {
        val short = history(rounds = 14, output = "x".repeat(6_001))
        assertEquals(0, AgentToolOutputFolder.fold(short, 1).changedMessages)
        val messages = history(rounds = 16, output = "x".repeat(12_000))
        val structured = JSONArray().put(JSONObject().put("type", "text").put("text", "image/text".repeat(2_000)))
        messages.getJSONObject(3).put("content", structured)
        messages.getJSONObject(5).put("content", "s".repeat(6_000))
        assertTrue(AgentToolOutputFolder.fold(messages, 1).changedMessages > 0)
        assertSame(structured, messages.getJSONObject(3).getJSONArray("content"))
        assertEquals("s".repeat(6_000), messages.getJSONObject(5).getString("content"))
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
            appendExchange(messages, 15 + turn, "z".repeat(12_000))
            messages.put(AgentConversationCodec.userTextMessage("next $turn"))
        }
        assertTrue(folded.size >= 8)
    }

    @Test fun closedBatchBoundaryProtectsAllSiblingToolResults() {
        val messages = history(rounds = 14, output = "x".repeat(20_000))
        val assistantIndex = 2 + (14 - 8) * 2
        val assistant = messages.getJSONObject(assistantIndex)
        val call = AgentModelClient.ToolCall("sibling", "read_file", "{}")
        assistant.getJSONArray("tool_calls").put(JSONObject().put("id", call.id).put("type", "function")
            .put("function", JSONObject().put("name", call.name).put("arguments", "{}")))
        val sibling = AgentConversationCodec.toolResultMessage(call, AgentModelClient.ToolResult("s".repeat(10_000)))
        val expanded = JSONArray()
        for (i in 0 until messages.length()) {
            expanded.put(messages.getJSONObject(i))
            if (i == assistantIndex + 1) expanded.put(sibling)
        }
        val before = expanded.toString()
        AgentToolOutputFolder.fold(expanded, 1)
        // 边界落在最近八组交换之前，同一组内的兄弟结果必须一起保持原样。
        assertEquals(messages.getJSONObject(assistantIndex + 1).toString(), expanded.getJSONObject(assistantIndex + 1).toString())
        assertSame(sibling, expanded.getJSONObject(assistantIndex + 2))
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
                    .put("type", "function_call").put("call_id", "call-14")))
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

    @Test fun normalRequestsNeverFoldEvenInLongSingleTurnsOrWhenCompactionIsDisabled() {
        for ((window, enabled) in listOf(575_000 to true, 575_000 to false, 10_000 to false, null to true)) {
            val messages = history(rounds = 45, output = "CODE".repeat(5_000), endTurn = false)
            val before = messages.toString()
            val config = config(window, enabled)
            val session = AgentContextSession(config, messages, 1, "stable", unusedProvider(), AgentRunController(),
                { emptySet() }, {}, {})
            session.captureRequest()
            session.observeInputTokens(328_000)
            repeat(3) {
                session.prepareRequest(JSONArray())
                session.compact()
                assertEquals(before, messages.toString())
                assertEquals(328_000, session.effectiveTokens())
            }
            session.checkpoint()
            val snapshot = checkNotNull(session.snapshot())
            assertFalse(snapshot.messages.any { it.content.contains("older_tool_output_folded") })
            assertTrue(snapshot.tokenBaseline!!.matches(snapshot.messages, config))
        }
    }

    @Test fun pressurePrunesBeforeSummarizingAndPreservesCalibratedUsageJournalAndCheckpoint() {
        val messages = history(rounds = 32, output = "CODE".repeat(5_000), endTurn = false)
        val journal = JSONArray((0 until messages.length()).map(messages::getJSONObject))
        val original = journal.toString()
        val config = config()
        val session = AgentContextSession(config, messages, 1, "pressure", unusedProvider(), AgentRunController(),
            { emptySet() }, {}, {}, { journal.length() })
        session.prepareRequest(JSONArray())
        session.captureRequest()
        session.observeInputTokens(500_000)
        session.checkpoint()
        val scale = session.snapshot()!!.tokenBaseline!!.boundedEstimateScale
        val expected = JSONArray(messages.toString())
        val saved = AgentToolOutputFolder.fold(expected, 1).savedTokens
        assertTrue(saved > 0)
        session.prepareRequest(JSONArray())
        assertEquals(original, messages.toString())
        session.compact() // The provider throws if pruning unnecessarily invokes a summary.
        assertEquals(500_000 - (saved * scale).toInt(), session.effectiveTokens())
        assertTrue(session.effectiveTokens() < 575_000 * 0.85)
        assertEquals(expected.toString(), messages.toString())
        assertEquals(original, journal.toString())
        val snapshot = checkNotNull(session.snapshot())
        assertTrue(snapshot.tokenBaseline!!.matches(snapshot.messages, config))
        assertEquals(journal.length(), snapshot.consumedTranscriptMessages)
        val after = messages.toString()
        repeat(3) { session.prepareRequest(JSONArray()); session.compact() }
        assertEquals(after, messages.toString())
        session.captureRequest()
        session.observeInputTokens(12_345)
        session.prepareRequest(JSONArray())
        assertEquals(12_345, session.effectiveTokens())
        session.checkpoint()
        assertEquals(12_345, session.snapshot()!!.tokenBaseline!!.lastContextTokens)
        assertTrue(session.snapshot()!!.tokenBaseline!!.matches(session.snapshot()!!.messages, config))
    }

    @Test fun pressurePruningPersistenceFailureLeavesLivePrefixAndUsageUntouched() {
        val messages = history(rounds = 32, output = "CODE".repeat(5_000), endTurn = false)
        val original = messages.toString()
        var fail = true
        val session = AgentContextSession(config(), messages, 1, "atomic", unusedProvider(), AgentRunController(),
            { emptySet() }, {}, { if (fail) error("storage unavailable") })
        session.captureRequest()
        session.observeInputTokens(500_000)
        assertThrows(IllegalStateException::class.java) { session.compact() }
        assertEquals(original, messages.toString())
        assertEquals(500_000, session.effectiveTokens())
        assertNull(session.snapshot())
        fail = false
        session.compact()
        assertNotEquals(original, messages.toString())
        assertNotNull(session.snapshot())
    }

    @Test fun cancelledPressurePruningDoesNotPersistOrRewriteAndFinalStorageFailureKeepsAnswer() {
        val controller = AgentRunController()
        val messages = history(rounds = 32, output = "CODE".repeat(5_000), endTurn = false)
        val original = messages.toString()
        val cancelled = AgentContextSession(config(), messages, 1, "cancelled", unusedProvider(), controller,
            { emptySet() }, {}, { fail("cancelled pruning must not persist") })
        cancelled.captureRequest()
        cancelled.observeInputTokens(500_000)
        controller.cancel()
        assertThrows(Exception::class.java) { cancelled.compact() }
        assertEquals(original, messages.toString())
        val finalSession = AgentContextSession(config(), messages, 1, "final", unusedProvider(), AgentRunController(),
            { emptySet() }, {}, { error("storage unavailable") })
        finalSession.captureRequest()
        finalSession.observeInputTokens(500_000)
        finalSession.compact(final = true)
        assertEquals(original, messages.toString())
        assertNotNull(finalSession.snapshot())
    }

    private fun config(window: Int? = 575_000, enabled: Boolean = true) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "key", model = "fixture", systemPrompt = "",
        contextWindow = window, autoCompactionEnabled = enabled,
    )

    private fun unusedProvider() = object : AgentProviderClient {
        override val id = "unused"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, true)
        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit): ProviderResponse = error("No summary expected")
    }

    private fun history(rounds: Int = 14, output: String = "x".repeat(12_000), endTurn: Boolean = true) = JSONArray()
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
