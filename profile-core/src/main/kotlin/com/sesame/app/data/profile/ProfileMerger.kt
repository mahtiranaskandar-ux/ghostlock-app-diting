package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.copyValue
import com.sesame.app.data.deepMergeValues
import com.sesame.app.data.mutableChild
import com.sesame.app.data.route.RouteKind
import com.sesame.app.data.valueMapOf

/**
 * Pure merge of the profile layers, lifted out of the Android controller so the
 * app and the Gradle exporter share one implementation. All inputs are already
 * loaded value maps; this class performs no IO.
 */
object ProfileMerger {
    private val RouteNames: List<String> = RouteKind.entries.map { it.token }

    /**
     * common execution preset -> built-in -> imported, then the selected route
     * groups are filled.
     */
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
        /* The caller's tuning map is shared by every profile it resolves (the
         * Gradle exporter resolves a whole index in one process), and the merge
         * below writes into the maps it is handed. Alias it and the *first*
         * profile's values leak into every later one -- measured 2026-10-14:
         * with the diting profile listed first in index.conf, an unrelated 6.x
         * profile inherited its route_wait_ms (30 instead of the tuning's 1000),
         * and the exporter and the app disagreed. Copy before merging. */
        val defaults = valueMapOf("release" to deviceRelease).apply {
            /* The native wire always carries an execution block (its schema
             * requires it); an absent tuning leaves it empty, which resolves
             * every knob to its shipped default. */
            put("execution", tuningExecution?.copyValue()?.asValueMap() ?: valueMapOf())
        }
        val resolved = mergeSource(
            if (builtin == null) defaults else deepMergeValues(defaults, builtin),
            imported,
        )
        resolved["schema_version"] = ProfileResolver.ProfileSchemaVersion.toLong()
        resolved["release"] = deviceRelease
        fillRouteExecutionDefaults(resolved, routePresets)
        validateResolved(resolved, deviceRelease)
        return resolved
    }

    /** Fills the route-tuning groups absent from the profile from the presets. */
    fun fillRouteExecutionDefaults(profile: ValueMap, routePresets: Map<String, ValueMap>) {
        val execution = profile["execution"].asValueMap() ?: return
        val routes = execution.mutableChild("routes")
        for (route in RouteNames) {
            val defaults = routePresets[route] ?: continue
            val existing = routes[route].asValueMap()
            if (existing == null) {
                /* Same sharing hazard as the tuning map above: hand the profile
                 * its own copy, never the preset object itself. */
                routes[route] = defaults.copyValue()
                continue
            }
            for ((key, value) in defaults) {
                /* null means "use the shipped default", the same contract as the
                 * execution tuning keys: an explicit null must not shadow the
                 * route preset. */
                if (existing[key] == null) existing[key] = value
            }
        }
    }

    /**
     * Route objects hold exactly one branch. An incoming branch that differs
     * from the base replaces it (the old geometry belongs to the old route),
     * while the same branch merges field-wise.
     */
    private fun mergeRouteObjects(base: ValueMap?, incoming: Map<*, *>?): ValueMap? {
        if (incoming == null) return base
        val incomingBranch = incoming.keys.filterIsInstance<String>()
            .firstOrNull { it in RouteNames } ?: return base
        val incomingBody = incoming[incomingBranch].asValueMap() ?: valueMapOf()
        val baseBranch = base?.keys?.toList()?.firstOrNull { it in RouteNames }
        return when {
            baseBranch == incomingBranch -> valueMapOf(
                incomingBranch to deepMergeValues(
                    base[incomingBranch].asValueMap() ?: valueMapOf(),
                    incomingBody,
                ),
            )

            else -> valueMapOf(incomingBranch to incomingBody)
        }
    }

    /** deepMerge plus branch-replacement semantics for route/fallback.route. */
    private fun mergeSource(base: ValueMap, incoming: ValueMap?): ValueMap {
        val baseRoute = base["route"].asValueMap()?.copyValue().asValueMap()
        val baseFallbackRoute = base["fallback"].asValueMap()
            ?.get("route").asValueMap()?.copyValue().asValueMap()
        val merged = deepMergeValues(base, incoming)
        if (incoming == null) return merged
        incoming["route"].asValueMap()?.let { route ->
            mergeRouteObjects(baseRoute, route)?.let { merged["route"] = it }
        }
        incoming["fallback"].asValueMap()?.get("route").asValueMap()?.let { fallbackRoute ->
            val fallback = merged["fallback"].asValueMap() ?: return@let
            mergeRouteObjects(baseFallbackRoute, fallbackRoute)?.let { fallback["route"] = it }
        }
        return merged
    }

    private fun requireIdentity(layer: String, profile: ValueMap?, deviceRelease: String) {
        val release = profile?.get("release") as? String ?: return
        require(release == deviceRelease) {
            "wrong_image: $layer profile release $release does not match device $deviceRelease"
        }
    }

    private fun validateResolved(profile: ValueMap, release: String) {
        require(profile["release"] == release) { "profile release mismatch" }
        require((profile["kernel_major"] as? Number)?.toInt() in 5..6) { "invalid kernel_major" }
        require(profile.containsKey("execution")) { "missing execution tuning" }
    }
}
