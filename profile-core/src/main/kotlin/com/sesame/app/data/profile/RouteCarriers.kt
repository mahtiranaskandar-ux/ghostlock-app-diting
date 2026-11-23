package com.sesame.app.data.profile

/**
 * The selectable carriers of one route branch.
 *
 * The document expresses the carrier choice as a list key inside the route
 * block (`carriers = [ "native", "compat" ]`), like the `pipeline` list. A
 * branch without the key keeps the legacy semantics: `compat_carrier = 1`
 * selects the AArch32 carrier, its absence the native one. The wire schema is
 * unchanged; the app injects the wire's `compat_carrier` from the chosen
 * carrier when it cuts the run.
 *
 * See docs/analysis/route-choice-matrix-plan.md.
 */
object RouteCarriers {
    const val Native = "native"
    const val Compat = "compat"
    val Known = listOf(Native, Compat)

    /** The branch's carriers, primary first; empty when the branch is absent. */
    fun of(profile: Map<String, Any?>, route: String): List<String> {
        val branch = branch(profile, route) ?: return emptyList()
        val declared = branch["carriers"]
        if (declared is List<*>) {
            return declared
                .mapNotNull { (it as? String)?.trim()?.lowercase() }
                .filter { it in Known }
                .distinct()
        }
        return if ((branch["compat_carrier"] as? Number)?.toLong() == 1L) {
            listOf(Compat)
        } else {
            listOf(Native)
        }
    }

    /** Whether the branch states the list key (as opposed to the legacy flag). */
    fun declaresList(profile: Map<String, Any?>, route: String): Boolean =
        branch(profile, route)?.containsKey("carriers") == true

    private fun branch(profile: Map<String, Any?>, route: String): Map<*, *>? =
        (profile["route"] as? Map<*, *>)?.get(route) as? Map<*, *>
}
