package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryCoordinatorTest {
    @Test
    fun sameProcessActiveCheckpointIsReattached() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active", owner = "same-process")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = "run-active",
            locallyObservedRunId = null,
        )

        assertEquals("run-active", plan.reattach?.runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun terminalResultTakesPrecedenceAndKeepsCheckpointForTraceReplay() {
        val checkpoint = checkpoint("run-complete")
        val completed = completed("run-complete")

        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint),
            completedRuns = listOf(completed),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = "run-complete",
            locallyObservedRunId = null,
        )

        assertEquals(completed, plan.completed.single().result)
        assertEquals(checkpoint, plan.completed.single().checkpoint)
        assertNull(plan.reattach)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun inactiveCheckpointIsInterruptedButLocallyObservedRunIsIgnored() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-stale"), checkpoint("run-local"), checkpoint("run-local-2")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = "run-local-2",
            locallyObservedRunId = "run-local",
            activeRunIds = setOf("run-local", "run-local-2"),
            locallyObservedRunIds = setOf("run-local", "run-local-2"),
        )

        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
        assertNull(plan.reattach)
    }

    @Test
    fun largeSubagentActivitySnapshotStaysValidDecodableJson() {
        val steps = (1..48).map { i ->
            io.github.mangi.eta.agent.subagent.SubagentStepSnapshot(
                id = "tool-$i",
                kind = io.github.mangi.eta.agent.subagent.SubagentStepSnapshot.KIND_TOOL,
                title = "读取文件 $i",
                detail = "详".repeat(2_000),
                status = io.github.mangi.eta.agent.subagent.SubagentStepSnapshot.STATUS_SUCCESS,
                toolName = "read_file",
            )
        }
        val snapshot = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot(
            subagentId = "sub_3",
            subagentType = "explore",
            description = "调研架构",
            prompt = "请".repeat(4_000),
            model = "deepseek-v4.1-flash",
            effort = "high",
            status = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.STATUS_COMPLETED,
            currentAction = "完成 · 48 次工具调用",
            steps = steps,
            output = "总".repeat(12_000),
        )
        val encoded = snapshot.encode()
        assertTrue(encoded.length <= 48_000)
        val decoded = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.decode(encoded)
        org.junit.Assert.assertNotNull(decoded)
        assertEquals("sub_3", decoded?.subagentId)
        assertEquals(io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.STATUS_COMPLETED, decoded?.status)
        assertTrue(decoded!!.output.isNotBlank())
    }

    @Test
    fun unavailableRuntimeLeavesUnresolvedCheckpointUntouched() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = false,
            terminalStateKnown = false,
            activeRunId = null,
            locallyObservedRunId = null,
        )

        assertTrue(plan.interrupted.isEmpty())
        assertNull(plan.reattach)
    }

    @Test
    fun knownActiveRunCanReattachEvenWhenTerminalQueryFailed() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active"), checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = false,
            activeRunId = "run-active",
            locallyObservedRunId = null,
        )

        assertEquals("run-active", plan.reattach?.runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    private fun checkpoint(
        runId: String,
        owner: String = "old-process",
    ) = AgentRunCheckpointStore.Checkpoint(
        runId = runId,
        ownerInstanceId = owner,
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        events = emptyList(),
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun completed(runId: String) = AgentRuntimeWire.CompletedRun(
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = true,
            content = "完成",
        ),
        createdAt = 3L,
    )
}
