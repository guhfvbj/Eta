package io.github.mangi.eta.agent.model

import org.json.JSONArray

/** Runtime sidecars are prepared before budgeting, never serialized by the conversation codec. */
internal fun interface AgentRequestContext {
    fun prepare(messages: JSONArray)
    fun userInput(messageId: String, text: String, source: String) = Unit
}
