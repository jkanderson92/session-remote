package com.sessionremote.app

import org.json.JSONObject

/**
 * The camera's own capability schema from GET /gp/gpControl: every setting with its options,
 * plus "filters" — the camera's rules for which values are invalid given other settings.
 * Using this instead of a hand-written table means crossed-out options match the camera exactly
 * (NTSC/PAL, Protune and looping all change what's allowed).
 */
data class SchemaOption(val name: String, val value: Int)
data class SchemaSetting(val id: Int, val name: String, val options: List<SchemaOption>)
data class SchemaFilter(val activatedBy: List<Pair<Int, Int>>, val settingId: Int, val values: Set<Int>)

class CameraSchema(
    val settings: Map<Int, SchemaSetting>,
    val filters: List<SchemaFilter>,
) {
    fun blocked(current: Map<Int, Int>, id: Int): Set<Int> =
        filters.asSequence()
            .filter { f -> f.settingId == id && f.activatedBy.all { (k, v) -> current[k] == v } }
            .flatMap { it.values.asSequence() }
            .toSet()

    fun allowed(current: Map<Int, Int>, id: Int, value: Int): Boolean = value !in blocked(current, id)

    /** Allowed value for [id] closest (by the number in its label) to [target], given [hypothetical] settings. */
    fun closestAllowed(hypothetical: Map<Int, Int>, id: Int, target: Double?): Int? {
        val opts = settings[id]?.options ?: return null
        val blockedNow = blocked(hypothetical, id)
        val ok = opts.filter { it.value !in blockedNow }
        if (ok.isEmpty()) return null
        if (target == null) return ok.first().value
        return ok.minByOrNull { o -> o.name.toDoubleOrNull()?.let { kotlin.math.abs(it - target) } ?: Double.MAX_VALUE }?.value
    }

    fun asDef(id: Int, title: String? = null): SettingDef? = settings[id]?.let { s ->
        SettingDef(id, title ?: s.name, s.options.map { Opt(prettify(it.name), it.value) })
    }

    companion object {
        private fun prettify(name: String) = when (name) {
            "ON" -> "On"; "OFF" -> "Off"; "AUTO" -> "Auto"
            else -> name
        }

        fun parse(root: JSONObject): CameraSchema {
            val settings = HashMap<Int, SchemaSetting>()
            val modes = root.optJSONArray("modes")
            if (modes != null) {
                for (i in 0 until modes.length()) {
                    val list = modes.optJSONObject(i)?.optJSONArray("settings") ?: continue
                    for (j in 0 until list.length()) {
                        val s = list.optJSONObject(j) ?: continue
                        val id = s.optInt("id", -1).takeIf { it >= 0 } ?: continue
                        val optsJson = s.optJSONArray("options") ?: continue
                        val opts = (0 until optsJson.length()).mapNotNull { k ->
                            optsJson.optJSONObject(k)?.let { o -> SchemaOption(o.optString("display_name"), o.optInt("value")) }
                        }
                        settings[id] = SchemaSetting(id, s.optString("display_name"), opts)
                    }
                }
            }
            val filters = ArrayList<SchemaFilter>()
            val fj = root.optJSONArray("filters")
            if (fj != null) {
                for (i in 0 until fj.length()) {
                    val f = fj.optJSONObject(i) ?: continue
                    val act = f.optJSONArray("activated_by") ?: continue
                    val conds = (0 until act.length()).mapNotNull { k ->
                        act.optJSONObject(k)?.let { it.optInt("setting_id") to it.optInt("setting_value") }
                    }
                    val bl = f.optJSONObject("blacklist") ?: continue
                    val vals = bl.optJSONArray("values") ?: continue
                    filters += SchemaFilter(conds, bl.optInt("setting_id"), (0 until vals.length()).map { vals.optInt(it) }.toSet())
                }
            }
            return CameraSchema(settings, filters)
        }
    }
}
