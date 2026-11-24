package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.copyValue
import com.sesame.app.data.deepMergeValues
import com.sesame.app.data.mutableChild
import com.sesame.app.data.valueMapOf

/**
 * Pure merge of the profile layers, lifted out of the Android controller so the
 * app and the Gradle exporter share one implementation. All inputs are already
 * loaded value maps; this class performs no IO.
 *
 * The merged tree is the cleaned document: the tuning presets land under
 * `ghostlock.execution`, the document's own values win, and a null in a
 * document means "use the preset" (the deep merge ignores incoming nulls).
 */
object ProfileMerger {
    fun resolveMerged(
        deviceRelease: String,
        builtin: ValueMap?,
        imported: ValueMap?,
        tuningExecution: ValueMap?,
        routePresets: Map<String, ValueMap>,
    ): ValueMap {
        /* Identity before the merge. The merge stamps the device's release over
         * the resolved map below; checking afterwards can only ever agree with
         * itself. A profile whose own release names another image is the
         * `wrong_image` case, not a value dispute: refuse and name both. */
        requireIdentity("builtin", builtin, deviceRelease)
        requireIdentity("imported", imported, deviceRelease)
        val base = cleaned(builtin) ?: valueMapOf()
        val incoming = cleaned(imported)
        val merged = if (incoming == null) base else deepMergeValues(base, incoming)
        merged["schema_version"] = ProfileResolver.ProfileSchemaVersion.toLong()
        merged["release"] = deviceRelease
        fillExecutionDefaults(merged, tuningExecution, routePresets)
        require(merged["release"] == deviceRelease) { "profile release mismatch" }
        val ghostlock = merged[ProfileResolver.GhostlockKey].asValueMap()
        val timer = merged[ProfileResolver.TimerKey] as? Map<*, *>
        require(ghostlock != null || timer != null) { "the profile selects no engine" }
        return merged
    }

    /**
     * The execution tuning lives under `ghostlock.execution`. The document's
     * own values win; the presets fill what it leaves null. The route presets
     * are shared objects, so the profile always gets its own copy.
     */
    fun fillExecutionDefaults(
        profile: ValueMap,
        tuningExecution: ValueMap?,
        routePresets: Map<String, ValueMap>,
    ) {
        val ghostlock = profile[ProfileResolver.GhostlockKey].asValueMap() ?: return
        val tuning = tuningExecution?.copyValue()?.asValueMap() ?: valueMapOf()
        val document = ghostlock["execution"].asValueMap() ?: valueMapOf()
        val execution = deepMergeValues(tuning, document)
        val routes = execution.mutableChild("routes")
        for ((route, defaults) in routePresets) {
            val existing = routes[route].asValueMap()
            if (existing == null) {
                routes[route] = defaults.copyValue()
                continue
            }
            for ((key, value) in defaults) {
                if (existing[key] == null) existing[key] = value
            }
        }
        ghostlock["execution"] = execution
    }

    /**
     * A layer must already be the cleaned document. The schema is checked here,
     * not by the caller or by the schema stamp on the merged output, so the
     * export/device-neutral path cannot relabel an older schema as v3.
     */
    private fun cleaned(layer: ValueMap?): ValueMap? {
        if (layer == null) return null
        require(ProfileResolver.isCleanedShape(layer)) {
            "the profile is not the cleaned v3 layout; regenerate it with this build"
        }
        val schema = (layer["schema_version"] as? Number)?.toInt()
        require(schema == ProfileResolver.ProfileSchemaVersion) {
            ProfileResolver.schemaErrorMessage(schema)
        }
        return layer.copyValue().asValueMap()
    }

    private fun requireIdentity(layer: String, profile: ValueMap?, deviceRelease: String) {
        val release = profile?.get("release") as? String ?: return
        require(release == deviceRelease) {
            "wrong_image: $layer profile release $release does not match device $deviceRelease"
        }
    }
}
