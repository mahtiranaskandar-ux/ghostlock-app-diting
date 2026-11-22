package com.sesame.app.domain.model

/**
 * The outcome of importing a profile document. Import is transactional: a
 * rejected document leaves the previous selection untouched, and the errors
 * name the field paths that failed verification.
 */
sealed interface ProfileImportResult {
    data class Accepted(val config: ProfileConfig) : ProfileImportResult
    data class Rejected(val errors: List<String>) : ProfileImportResult
}
