package com.sesame.app.data

import android.content.Context
import com.sesame.app.data.profile.ModuleConfig
import java.io.File
import java.security.MessageDigest

/**
 * Stages the module chain's bundled artifact: opens the `module` block's
 * `asset` from the APK, verifies `asset_sha256`, and writes it where the
 * DirtyFrag stage reads it. A missing asset or a hash mismatch returns null;
 * the pipeline resolver then refuses the chain before any delivery.
 */
object BundledModule {
    /** True when the asset opens and its bytes match the block's hash. */
    fun verify(context: Context, spec: ModuleConfig): Boolean {
        val bytes = readAsset(context, spec.asset) ?: return false
        return sha256(bytes).equals(spec.assetSha256, ignoreCase = true)
    }

    fun stage(context: Context, spec: ModuleConfig, destDir: File): File? {
        val bytes = readAsset(context, spec.asset) ?: return null
        if (!sha256(bytes).equals(spec.assetSha256, ignoreCase = true)) return null
        if (!destDir.isDirectory && !destDir.mkdirs()) return null
        val out = File(destDir, File(spec.asset).name)
        return runCatching {
            out.writeBytes(bytes)
            out
        }.getOrNull()
    }

    private fun readAsset(context: Context, asset: String): ByteArray? = runCatching {
        context.assets.open(asset).use { it.readBytes() }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
