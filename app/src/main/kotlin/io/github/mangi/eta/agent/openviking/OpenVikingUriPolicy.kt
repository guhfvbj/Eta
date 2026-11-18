package io.github.mangi.eta.agent.openviking

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal object OpenVikingUriPolicy {
    fun validate(raw: String): String {
        require(raw.length in 10..2048 && raw.startsWith("viking://"))
        // No encoded traversal, authority confusion, query, or alternate scheme handling.
        require(raw.none { it.isWhitespace() || it.isISOControl() || it in "%\\?#@" })
        val uri = URI(raw)
        require(uri.scheme == "viking" && !uri.rawAuthority.isNullOrBlank() && uri.port == -1 && uri.rawUserInfo == null)
        require(uri.rawAuthority in setOf("~", "user", "agent", "resources", "skills", "session"))
        require(uri.path.orEmpty().split('/').none { it in setOf(".", "..") })
        return raw.trimEnd('/')
    }

    fun entries(value: Any?): JSONArray = when (value) {
        is JSONArray -> value
        is JSONObject -> value.optJSONArray("entries") ?: value.optJSONArray("items") ?: value.optJSONArray("files") ?: JSONArray()
        else -> JSONArray()
    }

    /** Fail closed when 0.5.4 does not return sufficient peer ownership metadata. Never infer an
     * actor namespace from a display name, URI suffix, API key, or a caller-supplied peer label. */
    fun verifiedForgetTarget(raw: String, actorPeer: String, listing: Any?): String {
        val uri = validate(raw)
        require(URI(uri).rawAuthority in setOf("~", "user", "agent") && uri.contains("/memories/") && !uri.endsWith("/memories"))
        val entries = entries(listing)
        val match = (0 until entries.length()).mapNotNull(entries::optJSONObject).singleOrNull {
            it.optString("uri") == uri && it.optString("peer_id", it.optString("actor_peer")) == actorPeer &&
                it.opt("is_dir") != true && it.opt("is_directory") != true && it.optString("type") !in setOf("directory", "dir") &&
                (it.opt("is_dir") == false || it.optString("type") == "file")
        }
        require(match != null) { "Actor ownership is not verifiable" }
        return uri
    }

    /** Descend only through an actual directory returned by the authenticated parent listing.
     * This permits nested files without trusting caller-supplied account/actor path segments. */
    fun forgetChildDirectory(raw: String, listing: Any?): String? {
        val target = validate(raw)
        val entries = entries(listing)
        return (0 until entries.length()).mapNotNull(entries::optJSONObject).mapNotNull { entry ->
            if (entry.opt("is_dir") == false || entry.optString("type") == "file") return@mapNotNull null
            if (entry.opt("is_dir") != true && entry.opt("is_directory") != true &&
                entry.optString("type") !in setOf("dir", "directory")) return@mapNotNull null
            val directory = runCatching { validate(entry.optString("uri")) }.getOrNull() ?: return@mapNotNull null
            directory.takeIf { target.startsWith("$it/") }
        }.singleOrNull()
    }
}
