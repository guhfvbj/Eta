package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.EtaTrace

/** Durable diagnostic fields only; never log human text, tool output or credentials. */
internal object DecisionTraceLogger : AgentLogger {
    override fun debug(message: () -> String) { }
    override fun info(message: String) = EtaTrace.log("decision", message)
    override fun warn(message: String) = EtaTrace.log("decision.warn", message)
    override fun error(message: String, throwable: Throwable?) = EtaTrace.log("decision.error", message)
}
