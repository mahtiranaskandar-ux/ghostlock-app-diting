package com.sesame.app.data

import com.sesame.app.data.profile.ProfileResolver
import com.sesame.app.data.profile.Timer98260

/**
 * The timer-only projection of a generated document.
 *
 * On a platform where the physical pair is mandatory (MediaTek) and the images
 * did not supply it, GhostLock cannot be derived — but the 98260 timer engine
 * can, because it never reads the pair. This turns the generated document into
 * a profile that claims only what it can run: the shared kernel facts and the
 * timer block, with `ghostlock = null`.
 */
object TimerOnlyDocument {
    fun from(documentText: String?): String? {
        val raw = documentText
            ?.let { runCatching { HoconSupport.parseValue(it).asValueMap() }.getOrNull() }
            ?: return null
        if (!ProfileResolver.isCleanedShape(raw)) return null
        val map = raw
        if (!Timer98260.fakeSupported(map)) return null
        val view = map.copyValue().asValueMap() ?: return null
        view["ghostlock"] = null
        view["devices"] = null
        return HoconSupport.render(view)
    }
}
