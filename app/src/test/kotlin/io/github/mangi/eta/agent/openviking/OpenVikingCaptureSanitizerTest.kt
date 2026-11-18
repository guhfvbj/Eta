package io.github.mangi.eta.agent.openviking

import org.junit.Assert.*
import org.junit.Test
import io.github.mangi.eta.agent.runtime.AgentEvent

class OpenVikingCaptureSanitizerTest {
    @Test fun stripsEveryInjectedBlockBeforeApplyingBudgets() {
        for (tag in listOf("remote_memory", "memory_core", "memory_headings", "openviking-context", "relevant-memories", "screen_context", "assistant_screen_context", "tool_result", "tool_output")) {
            assertEquals("I prefer Kotlin", OpenVikingCaptureSanitizer.clean("I prefer Kotlin\n<$tag source=\"fixture\">private tool data</$tag>"))
            assertNull(OpenVikingCaptureSanitizer.clean("I prefer Kotlin\n<$tag>unfinished"))
        }
        assertEquals("I prefer Kotlin", OpenVikingCaptureSanitizer.clean("I prefer Kotlin\n以下是系统在本次助理唤醒时提供的屏幕与应用数据，供理解用户指代；secret"))
        assertNull(OpenVikingCaptureSanitizer.clean("<remote_memory>unfinished data"))
    }
    @Test fun removesSecretsAndPrivateKeysWithoutDependingOnCurrentCredential() {
        val clean = OpenVikingCaptureSanitizer.clean("My preference is Kotlin\nAuthorization: Bearer fixture-token\napi_key=fixture-secret\npassword=fixture-password\nsk-12345678901234567890\ncurrent-credential", "current-credential")!!
        for (secret in listOf("fixture-token", "fixture-secret", "fixture-password", "sk-12345678901234567890", "current-credential")) assertFalse(clean.contains(secret))
        val privateKey = "Durable fact\n-----BEGIN PRIVATE KEY-----\nprivate\n-----END PRIVATE KEY-----"
        assertFalse(OpenVikingCaptureSanitizer.clean(privateKey)!!.contains("\nprivate\n"))
    }
    @Test fun taintedAssistantSpansAreDroppedRatherThanPartiallyRedacted() {
        assertNull(OpenVikingCaptureSanitizer.clean("A fluent paraphrase of a sensitive tool result", assistant = true, tainted = true))
        assertEquals("I prefer Kotlin", OpenVikingCaptureSanitizer.clean("I prefer Kotlin", assistant = true))
    }
    @Test fun removesCredentialUrisCookiesAndStandaloneProviderTokensAndRejectsPartialPrivateKeys() {
        val clean = OpenVikingCaptureSanitizer.clean("I prefer Kotlin\nhttps://user:private-pass@example.invalid/path\nCookie: session=private-cookie; another=private-cookie2\nrefresh_token=private-refresh\nglpat-12345678901234567890\nAIza12345678901234567890123456789012345")!!
        for (secret in listOf("private-pass", "private-cookie", "private-cookie2", "private-refresh", "glpat-12345678901234567890", "AIza12345678901234567890123456789012345")) assertFalse(clean.contains(secret))
        assertNull(OpenVikingCaptureSanitizer.clean("My credential\n-----BEGIN PRIVATE KEY-----\nunfinished payload"))
    }
    @Test fun refusesNoiseErrorsUiTreesAndTerminalLogs() {
        for (text in listOf("", "ok", "谢谢", "已停止", "[sensitive content omitted]", "Error: tool failed",
            "工具执行已结束，未取得完整结果；请先核实执行状态。", "模型未返回正文或工具调用，请检查服务商状态。", "操作失败：已取消请求",
            "{\"ui_nodes\":[{\"text\":\"private\"}]}", "<hierarchy><node bounds=\"[0,0]\" /></hierarchy>",
            "11-17 14:20:22 System: raw log", "Traceback (most recent call last):\nraw")) assertNull(text, OpenVikingCaptureSanitizer.clean(text))
    }
    @Test fun provenanceRemembersToolExposureAndNeverResetsOnCompactionOrCheckpoint() {
        val gate = OpenVikingCaptureProvenance()
        assertFalse(gate.tainted)
        gate.accept(AgentEvent.ToolStarted(1, "call", "search_messages", "safe preview"))
        assertTrue(gate.tainted)
        gate.accept(AgentEvent.ContextCompaction("compact", "completed"))
        assertTrue(gate.tainted)
        gate.accept(AgentEvent.RunFailed("failure"))
        assertTrue(gate.failed)
        assertTrue(gate.explicitStoreBlocked)
        assertNull(OpenVikingCaptureSanitizer.clean("Apparently ordinary paraphrase", assistant = true, tainted = gate.tainted))
    }
    @Test fun explicitStoreDoesNotVetoItselfButAnyOtherExposureBlocksDerivativeStores() {
        val gate = OpenVikingCaptureProvenance()
        gate.accept(AgentEvent.ToolStarted(1, "store", "ov_memory_store", ""))
        assertTrue(gate.tainted)
        assertFalse(gate.explicitStoreBlocked)
        gate.accept(AgentEvent.HostedToolStarted(2, "search", "web_search"))
        assertTrue(gate.explicitStoreBlocked)
        gate.accept(AgentEvent.ToolStarted(3, "store2", "ov_memory_store", ""))
        assertTrue(gate.explicitStoreBlocked)
        gate.userInput("Please remember: I prefer Kotlin\n<remote_memory>untrusted fact</remote_memory>")
        assertTrue(gate.allowsExplicitStore("I prefer Kotlin"))
        assertFalse(gate.allowsExplicitStore("A paraphrase of private tool data"))
        assertFalse(gate.allowsExplicitStore("untrusted fact"))
    }
    @Test fun limitsUtf8BytesAndNeverTruncatesASecretAcrossTheBoundary() {
        assertNull(OpenVikingCaptureSanitizer.clean("x".repeat(8001)))
        assertNull(OpenVikingCaptureSanitizer.clean("中".repeat(4100)))
        assertEquals(4000, OpenVikingCaptureSanitizer.clean("中".repeat(4000))!!.length)
    }
}
