package com.sesame.app.domain.usecase

import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.repository.GhostlockRepository

class LoadKernelSnapshotUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke() = repository.snapshot()
}

class ParseSourceUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke(
        input: String,
        xblPath: String? = null,
        uefiPath: String? = null,
        vendorBootPath: String? = null,
        dtboPath: String? = null,
        overwrite: Boolean = false,
        route: String? = null,
        onLog: (String) -> Unit = {},
    ) = repository.parseSource(
        input, xblPath, uefiPath, vendorBootPath, dtboPath, overwrite, route, onLog,
    )
}

class RunExploitUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke(
        config: ProfileConfig,
        useShizuku: Boolean,
        onLog: (String) -> Unit,
    ) = if (useShizuku) {
        repository.runExploitWithShizuku(config, onLog)
    } else {
        repository.runExploit(config, onLog)
    }
}

class ReadDocumentUseCase(private val repository: GhostlockRepository) {
    suspend operator fun invoke(uri: String) = repository.readDocument(uri)
    suspend fun cache(uri: String, fileName: String) = repository.cacheDocument(uri, fileName)
}
