package com.sesame.app.ui

import android.content.Context
import com.sesame.app.R

/**
 * A resolver reason for refusing an external profile, in the user's language
 * when the reason class is known. The import rejection dialog uses it; the raw
 * sentence stays in the log/report.
 */
internal fun externalReasonText(reason: String, context: Context): String {
    val schema = SCHEMA.find(reason)
    return when {
        schema != null -> context.getString(
            R.string.external_reason_schema,
            schema.groupValues[1],
            schema.groupValues[2],
        )

        reason.startsWith(NO_ENTRY_PREFIX) -> context.getString(
            R.string.external_reason_no_entry,
            reason.removePrefix(NO_ENTRY_PREFIX),
        )

        reason.startsWith(CARRIES_NO_PROFILE_PREFIX) -> context.getString(
            R.string.external_reason_no_entry,
            reason.removePrefix(CARRIES_NO_PROFILE_PREFIX),
        )

        reason == "no profile resolved for this kernel" ->
            context.getString(R.string.external_reason_no_kernel)

        reason == "the profile could not be resolved" ->
            context.getString(R.string.external_reason_unresolved)

        reason.startsWith("the profile copy could not be written") ->
            context.getString(R.string.external_reason_write)

        reason.startsWith("cannot read") || reason.startsWith("cannot parse") ||
            reason.startsWith("the file cannot be read") ||
            reason.startsWith("the profile copy cannot be read") ->
            context.getString(R.string.external_reason_read)

        else -> reason
    }
}

private val SCHEMA = Regex("unsupported profile schema (\\S+); this build reads schema (\\S+)")
private const val NO_ENTRY_PREFIX = "the imported profile has no entry for "
private const val CARRIES_NO_PROFILE_PREFIX = "the file carries no profile for "
