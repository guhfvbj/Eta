package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentFeatureSession
import org.json.JSONArray
import org.json.JSONObject

/** The public contract matches AgentSelfAuthorTools.persist, including the nested definition. */
internal object AgentRecipeToolCatalog {
    fun authorSchema(): JSONObject {
        fun type(value: String) = JSONObject().put("type", value)
        val definition = type("object").put("properties", JSONObject()
            .put("name", type("string").put("pattern", "^[a-z][a-z0-9_]{2,39}$"))
            .put("description", type("string"))
            .put("enabled", type("boolean"))
            .put("parameters", type("object"))
            .put("steps", type("array").put("minItems", 1).put("maxItems", 24).put("items", type("object"))))
            .put("required", JSONArray(listOf("name", "description", "parameters", "steps")))
        return AgentFeatureSession.schema("eta_author_tool",
            "创建、更新、删除、启停持久化配方。create/update 提供完整 recipe 对象；name 用于定位已有配方。" +
                "步骤：literal(text), http_request(url,method,headers,body), root_shell(command,timeout_ms), " +
                "settings_read(namespace,key), settings_write(namespace,key,value), file_read(path), file_write(path,content), " +
                "content_query(uri,projection,selection,limit), compose(recipe,pass_output)。占位符 {{arg}} 必须声明于 parameters。" +
                "保存后从下一轮开始可用；不自动获得系统权限。update 省略 enabled 会保留原启停状态。",
            JSONObject().put("action", type("string").put("enum", JSONArray(listOf("create", "update", "delete", "enable", "disable"))))
                .put("name", type("string")).put("recipe", definition), listOf("action"))
    }
}
