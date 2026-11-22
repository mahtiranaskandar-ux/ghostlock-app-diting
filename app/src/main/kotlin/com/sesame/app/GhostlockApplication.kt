package com.sesame.app

import android.app.Application
import com.sesame.app.data.AndroidGhostlockRepository
import com.sesame.app.domain.repository.GhostlockRepository

/** Application composition root. It is the only place that binds data implementations to domain ports. */
class GhostlockApplication : Application() {
    fun createRepository(): GhostlockRepository = AndroidGhostlockRepository(this)
}
