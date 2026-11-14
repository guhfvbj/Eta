package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/** A terminal activity does not claim that an interrupted external side effect was rolled back. */
internal fun ToolActivityMessageUi.terminated(reason: String, cancelled: Boolean): ToolActivityMessageUi = copy(
    status = ToolActivityStatusUi.Failed,
    resultSummary = reason,
    command = if (toolName == "subagent") {
        SubagentActivitySnapshot.decode(command)?.terminated(reason, cancelled)?.encode() ?: command
    } else command,
)
