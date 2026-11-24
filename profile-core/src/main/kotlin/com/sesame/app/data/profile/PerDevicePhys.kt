package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.copyValue
import com.sesame.app.data.valueMapOf

/**
 * Resolves the document's per-device physical pair (`devices.<id>.phys`).
 *
 * The authored document states one entry per device identity it was authored
 * for. The run path selects the running device's entry and leaves exactly that
 * one in the resolved tree, so the transport document carries the pair the
 * native must use (the native refuses more than one entry). A document whose
 * `devices` names none of the running device's identities is a **refusal**, not
 * a null pair: falling back to the native default would run a profile that was
 * never authored for this device. A null/absent `devices` is the native
 * default and is accepted.
 *
 * The exporter and other device-neutral callers use [resolveNeutral], which
 * drops the map (the native default) and never refuses.
 */
object PerDevicePhys {
    data class PhysResolution(val document: ValueMap, val device: String?)

    fun resolve(document: ValueMap, device: String?): ValueMap =
        resolveFor(document, listOfNotNull(device)).document

    fun resolveNeutral(document: ValueMap): ValueMap {
        requireSchema(document)
        val resolved = document.copyValue().asValueMap() ?: ValueMap()
        if (resolved.containsKey("devices")) resolved["devices"] = null
        return resolved
    }

    fun resolveFor(
        document: ValueMap,
        devices: List<String>,
        requireMatch: Boolean = true,
    ): PhysResolution {
        requireSchema(document)
        val resolved = document.copyValue().asValueMap() ?: ValueMap()
        val raw = resolved["devices"] ?: return PhysResolution(resolved, null)
        val entries = raw.asValueMap()
            ?: throw IllegalArgumentException("devices is not an object")
        if (entries.isEmpty()) return PhysResolution(resolved, null)
        val candidates = devices.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val matched = candidates.firstNotNullOfOrNull { candidate ->
            entries.keys.firstOrNull { it.equals(candidate, ignoreCase = true) }
        }
        if (matched == null) {
            if (requireMatch) {
                val names = devices.map { it.trim() }.filter { it.isNotEmpty() }
                    .distinct().joinToString("/").ifEmpty { "this device" }
                throw IllegalArgumentException("the profile's devices map does not name $names")
            }
            return PhysResolution(resolved, null)
        }
        val entry = entries[matched]
        resolved["devices"] = valueMapOf(matched to entry)
        return PhysResolution(resolved, matched)
    }

    /** Both entry points accept only the one document schema. */
    private fun requireSchema(document: ValueMap) {
        val schema = (document["schema_version"] as? Number)?.toInt()
        if (schema != ProfileResolver.ProfileSchemaVersion) {
            throw IllegalArgumentException(ProfileResolver.schemaErrorMessage(schema))
        }
    }
}
