package com.sesame.app.data

/**
 * The extractor's `--options-out` document: every route, anchor candidate and
 * page-free plan for one image, plus the cleaned v3 profile document. One
 * parse produces it; a runnable profile is a **cut** of that document.
 *
 * The extractor omits nothing. The UI applies the display rules over the full
 * list ([RoutePlans.safeOptions] / [RoutePlans.conditionalOptions]): a single
 * safe option is taken automatically, several safe options are offered without
 * a page row, and page is offered only when no safe option exists.
 */
data class OptionAnchor(
    val image: Long,
    val bytes: Long,
    val stride: Long,
)

data class OptionScratch(
    val image: Long,
)

/** The `scratchSource` spelling for a scratch inside the anchor's own region. */
const val ScratchInAnchor = "in-anchor"

data class OptionPlan(
    val anchor: OptionAnchor?,
    val scratch: OptionScratch?,
    val conditioned: Boolean,
    val anchorName: String?,
    val scratchSource: String?,
) {
    /** A short technical label (logs and tests): the anchor region and, when it
     * is not in-anchor, the scratch region. */
    val label: String
        get() {
            val anchor = anchorName ?: "page"
            val scratch = scratchSource?.takeIf { it != ScratchInAnchor }
            return if (scratch == null) anchor else "$anchor + $scratch"
        }
}

/** One non-primary carrier of a route. */
data class OptionRouteVariant(
    val carrier: String,
    val verdict: String,
)

data class OptionRoute(
    val route: String,
    val verdict: String,
    /** The primary carrier: `native` or `compat`. */
    val carrier: String = "native",
    /** The route's other feasible carriers (the carrier × route matrix). */
    val alternatives: List<OptionRouteVariant> = emptyList(),
)

/** One `device_analysis[]` record: the sidecar set the derivations were made
 * for. The pair itself lives in the document (`devices.<codename>.phys`).
 */
data class OptionDevice(
    /** Codename; null when the pass had no device sidecar set. */
    val device: String?,
)

data class RoutePlans(
    val route: String,
    val page: OptionPlan,
    /** The safe anchor with a page W1, when no page-free plan is selected. */
    val anchorOnly: OptionPlan?,
    /** Every page-free plan: safe first, then conditioned. */
    val pageFree: List<OptionPlan>,
) {
    /** Options the UI may auto-take or offer; page is not shown when non-empty. */
    val safeOptions: List<OptionPlan>
        get() = pageFree.filter { !it.conditioned }

    /** The needs-review options, offered only when no safe option exists. */
    val conditionalOptions: List<OptionPlan>
        get() = pageFree.filter { it.conditioned }
}

/** The UI's display rule over the full option list (operator decision). */
sealed interface OptionDecision {
    /** Exactly one safe page-free option: take it without asking. */
    data class Auto(val plan: OptionPlan) : OptionDecision

    /** Offer these plans. When [pageOffered] is true the UI adds a page row. */
    data class Offer(val options: List<OptionPlan>, val pageOffered: Boolean) : OptionDecision

    /** Nothing page-free; the page-based path (and the lock-source choice). */
    data object NoPageFree : OptionDecision
}

fun RoutePlans.decision(): OptionDecision = when {
    safeOptions.size == 1 -> OptionDecision.Auto(safeOptions.single())
    safeOptions.isNotEmpty() -> OptionDecision.Offer(safeOptions, pageOffered = false)
    conditionalOptions.isNotEmpty() -> OptionDecision.Offer(conditionalOptions, pageOffered = true)
    else -> OptionDecision.NoPageFree
}

data class OptionSuperset(
    val release: String,
    val routes: List<OptionRoute>,
    val plans: List<RoutePlans>,
    /** The cleaned v3 profile document the extractor rendered for this image:
     * the route-superset form. A cut selects the route, carrier, anchor and
     * device inside it; nothing is assembled from blocks. Null when the image
     * has no feasible route. */
    val document: ValueMap?,
    /** The device sidecar sets the extractor derived (`device_analysis[]`). */
    val devices: List<OptionDevice> = emptyList(),
) {
    /** The device key this superset was generated for, when it has one. */
    val device: String? get() = devices.firstOrNull()?.device

    fun route(name: String): OptionRoute? = routes.firstOrNull { it.route == name }

    fun plansFor(name: String): RoutePlans? = plans.firstOrNull { it.route == name }

    /**
     * Materialize a runnable profile: the extractor's document with the
     * chosen route, carrier and plan anchor applied and narrowed to the
     * chosen device's pair. No derivation happens here.
     */
    fun cut(
        route: String,
        plan: OptionPlan,
        device: String? = null,
        carrier: String? = null,
    ): String? {
        val routeOption = routes.firstOrNull { it.route == route && it.verdict == Feasible }
            ?: return null
        val base = document?.copyValue()?.asValueMap() ?: return null
        val ghostlock = base["ghostlock"].asValueMap() ?: return null
        fun hex(value: Long?): String? = value?.let { "0x" + it.toULong().toString(16) }
        /* The carrier choice is a geometry choice inside the route; the primary
         * carrier is the default, and an absent or unknown carrier fails
         * closed. */
        val declared = (
            listOf(routeOption.carrier) +
                routeOption.alternatives
                    .filter { it.verdict == Feasible }
                    .map { it.carrier }
            ).distinct()
        val selectedCarrier = when {
            carrier != null -> if (carrier in declared) carrier else return null
            "compat" in declared && "native" !in declared -> "compat"
            "native" in declared -> "native"
            else -> null
        }
        ghostlock["syscall"] = route
        ghostlock["carrier"] = selectedCarrier
        ghostlock["anchor"] = plan.anchor?.let { anchor ->
            linkedMapOf<String, Any?>(
                "image" to hex(anchor.image),
                "bytes" to anchor.bytes,
                "stride" to anchor.stride,
                "scratch" to plan.scratch?.let { scratch -> hex(scratch.image) },
            )
        }
        /* The document already states the pair for the device it was generated
         * for (`devices.<codename>.phys`); a multi-device document is narrowed
         * to the chosen codename. A pass with no device key keeps the document
         * as rendered (the pair is null and the native default applies). */
        val declaredDevices = base["devices"].asValueMap()
        val wanted = device ?: declaredDevices?.keys?.firstOrNull()
        if (declaredDevices != null && wanted != null) {
            declaredDevices.entries
                .firstOrNull { (name, _) -> name.equals(wanted, ignoreCase = true) }
                ?.let { (name, entry) -> base["devices"] = linkedMapOf<String, Any?>(name to entry) }
        }
        base["release"] = release
        return HoconSupport.render(base) + "\n"
    }

    companion object {
        const val Feasible = "feasible"

        fun parse(text: String): OptionSuperset? = runCatching {
            val root = HoconSupport.parseValue(text).asValueMap() ?: return@runCatching null
            val document = root["document"].asValueMap()
            val routes = root["routes"].asValueList().orEmpty().mapNotNull { entry ->
                val map = entry.asValueMap() ?: return@mapNotNull null
                val route = map["route"] as? String ?: return@mapNotNull null
                val alternatives = map["alternatives"].asValueList().orEmpty().mapNotNull { alt ->
                    val altMap = alt.asValueMap() ?: return@mapNotNull null
                    val carrier = altMap["carrier"] as? String ?: return@mapNotNull null
                    OptionRouteVariant(
                        carrier = carrier,
                        verdict = altMap["verdict"] as? String ?: "",
                    )
                }
                OptionRoute(
                    route = route,
                    verdict = map["verdict"] as? String ?: "",
                    carrier = map["carrier"] as? String ?: "native",
                    alternatives = alternatives,
                )
            }
            /* The per-device evidence: the plans are derived against the
             * device's pair, so the first analysis record's plans are the
             * list the picker uses. The pair itself is in the document. */
            val analysis = root["device_analysis"].asValueList().orEmpty()
                .mapNotNull { entry -> entry.asValueMap() }
            val plans = analysis.firstOrNull()
                ?.get("plans").asValueList().orEmpty()
                .mapNotNull { entry ->
                    val map = entry.asValueMap() ?: return@mapNotNull null
                    val route = map["route"] as? String ?: return@mapNotNull null
                    val page = plan(map["page"]) ?: return@mapNotNull null
                    RoutePlans(
                        route = route,
                        page = page,
                        anchorOnly = plan(map["anchor_only"]),
                        pageFree = map["page_free"].asValueList().orEmpty()
                            .mapNotNull { plan(it) },
                    )
                }
            val devices = analysis.map { map ->
                OptionDevice(device = map["device"] as? String)
            }
            OptionSuperset(
                release = root["release"] as? String ?: return@runCatching null,
                routes = routes,
                plans = plans,
                document = document,
                devices = devices,
            )
        }.getOrNull()

        private fun plan(value: Any?): OptionPlan? {
            val map = value.asValueMap() ?: return null
            return OptionPlan(
                anchor = map["anchor"].asValueMap()?.let { anchor ->
                    OptionAnchor(
                        image = (anchor["image"] as? Number)?.toLong() ?: return@let null,
                        bytes = (anchor["bytes"] as? Number)?.toLong() ?: return@let null,
                        stride = (anchor["stride"] as? Number)?.toLong() ?: return@let null,
                    )
                },
                scratch = map["scratch"].asValueMap()?.let { scratch ->
                    OptionScratch(
                        image = (scratch["image"] as? Number)?.toLong() ?: return@let null,
                    )
                },
                conditioned = map["conditioned"] as? Boolean ?: false,
                anchorName = map["anchor_name"] as? String,
                scratchSource = map["scratch_source"] as? String,
            )
        }

    }
}
