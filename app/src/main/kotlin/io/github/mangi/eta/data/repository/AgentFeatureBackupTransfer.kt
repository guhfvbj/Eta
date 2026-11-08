package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.model.AgentFeaturePreferences
import io.github.mangi.eta.agent.tool.*
import org.json.JSONObject

/** Logical JSON backups preserve local enablement; untrusted recipe sharing still imports disabled. */
internal object AgentFeatureBackupTransfer {
    data class Snapshot(val settings: String, val recipes: List<String>)
    fun snapshot(context: Context) = Snapshot(AgentFeaturePreferences.exportAll(context),
        AgentToolRecipeStore.get(context).all().map { ToolRecipeShareFormat.entryOf(it).put("enabled",it.enabled)
            .put("version",it.version).put("created_at",it.createdAtMillis).put("updated_at",it.updatedAtMillis).toString() })
    private fun parse(raw: List<String>): List<ToolRecipe> {
        require(raw.size <= ToolRecipeConstants.MAX_STORED_RECIPES) { "配方数量过多" }
        require(raw.sumOf { it.length.toLong() } <= 8L*1024*1024) { "配方内容过大" }
        val objects = raw.map(::JSONObject)
        val names = objects.map { it.getString("name") }.toSet()
        require(names.size == objects.size) { "配方名称重复" }
        val recipes = objects.map { json ->
            when(val parsed = ToolRecipeParser.parse(json,names)) {
                is ToolRecipeParser.ParseResult.Invalid -> error(parsed.message)
                is ToolRecipeParser.ParseResult.Ok -> parsed.value.copy(version=json.optInt("version",1),
                    createdAtMillis=json.optLong("created_at"),updatedAtMillis=json.optLong("updated_at"))
            }
        }
        require(recipes.all { names.containsAll(it.composedRecipes) }) { "组合配方引用不存在" }
        // The store performs the same cycle check under its write lock; validate before DB mutation too.
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>(); val byName=recipes.associateBy { it.name }
        fun visit(name: String) {
            require(name !in visiting) { "配方组合循环" }; if(!visited.add(name)) return
            visiting += name; byName[name]?.composedRecipes?.forEach(::visit); visiting -= name
        }
        names.forEach(::visit)
        return recipes
    }
    fun validate(settings: String?, recipes: List<String>?) { settings?.let(AgentFeaturePreferences::validateImport); recipes?.let(::parse) }
    fun restore(context: Context, settings: String?, recipes: List<String>?) {
        validate(settings,recipes)
        recipes?.let { AgentToolRecipeStore.get(context).replaceAll(parse(it)) }
        settings?.let { AgentFeaturePreferences.restoreAll(it,context) }
    }
}
