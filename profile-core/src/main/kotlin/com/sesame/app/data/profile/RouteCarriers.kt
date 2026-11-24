package com.sesame.app.data.profile

/**
 * The carrier vocabulary a route branch declares.
 *
 * The document does not carry a per-route carrier flag: a branch declares its
 * carriers by the blocks it states (`ghostlock.syscalls.<name>.native` /
 * `.compat`), and the run's cut states `ghostlock.carrier` from the chosen one.
 *
 * See docs/analysis/route-choice-matrix-plan.md.
 */
object RouteCarriers {
    const val Compat = "compat"
}
