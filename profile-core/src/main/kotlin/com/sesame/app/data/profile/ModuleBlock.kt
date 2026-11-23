package com.sesame.app.data.profile

/**
 * The module chain's pair manifest (`module`).
 *
 * The chain is bundle-only: a bundled profile and the hand-built `.ko` it
 * names were validated together, so the profile is the only declaration of
 * that pair; there is no separate asset index. Selection stays in `pipeline`
 * and this block only makes the `module` entry viable —
 * [ProfileResolver.validateMerged] requires the two together.
 *
 * The block states identity and delivery only. The image's module ABI
 * (vermagic, `sizeof(struct module)`, the `module_layout` CRC) is not
 * duplicated here: the build gate pins the exact Image config, the loader
 * enforces the ABI (a wrong magic half or CRC is a clean `ENOEXEC`), and
 * [assetSha256] binds the exact artifact. A profile-side ABI claim could only
 * re-assert what the bundle already fixes; the app cannot read the running
 * kernel's ABI to check it against.
 */
data class ModuleConfig(
    val version: Int,
    val asset: String,
    val assetSha256: String,
    /** Empty = the app's built-in ordered probe list. */
    val vendorFiles: List<String>,
)

/**
 * Fail-closed parser and validator for the block. The block is present only
 * on a profile that selects the module chain; absence is not an error here
 * (the coupling rule lives in [ProfileResolver]).
 */
object ModuleBlock {
    const val BLOCK_KEY = "module"

    /** The stub's `stub_ko_target` field: 64 bytes including the NUL. */
    private const val KO_TARGET_MAX = 64
    private val BlockKeys = setOf("version", "asset", "asset_sha256", "vendor_files")
    private val Sha256 = Regex("^[0-9a-fA-F]{64}$")

    /** A numeric field: a JSON number or a `"0x..."` / decimal string. */
    private fun asLong(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Number -> value.toDouble().takeIf { it == Math.floor(it) }?.toLong()
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) {
                text.substring(2).toLongOrNull(16)
            } else {
                text.toLongOrNull()
            }
        }
        else -> null
    }

    /** `assets/`-relative, under `modules/`, a `.ko`, and no traversal. */
    private fun validAsset(path: String): Boolean {
        if (!path.startsWith("modules/") || !path.endsWith(".ko")) return false
        if (path.contains('\\') || path.contains('\u0000')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** A `vendor_file` path the stub can carry and the bridge may read. */
    private fun validVendorFile(path: String): Boolean {
        if (!path.startsWith("/vendor/") || path.endsWith("/")) return false
        if (path.any { it.code < 0x20 || it.code > 0x7e }) return false
        if (path.split('/').any { it == "." || it == ".." }) return false
        return path.toByteArray(Charsets.US_ASCII).size < KO_TARGET_MAX
    }

    /** Validate the block; returns the parsed pair plus any errors. */
    fun parse(value: Any?): Pair<ModuleConfig?, List<ConfigError>> {
        val errors = mutableListOf<ConfigError>()
        if (value == null) return null to errors
        val block = value as? Map<*, *>
        if (block == null) {
            errors += ConfigError(BLOCK_KEY, "not an object")
            return null to errors
        }
        for (key in block.keys) {
            val name = key.toString()
            if (name !in BlockKeys) errors += ConfigError("$BLOCK_KEY.$name", "unknown key")
        }
        fun err(field: String, message: String) {
            errors += ConfigError("$BLOCK_KEY.$field", message)
        }

        val version = asLong(block["version"])
        when {
            block["version"] == null -> err("version", "missing")
            version == null -> err("version", "not an integer")
            version != 1L -> err("version", "must be 1")
        }

        val asset = (block["asset"] as? String)?.trim()
        when {
            block["asset"] == null -> err("asset", "missing")
            asset == null -> err("asset", "not a string")
            !validAsset(asset) -> err("asset", "must be a relative modules/<name>.ko path")
        }

        val sha = (block["asset_sha256"] as? String)?.trim()
        when {
            block["asset_sha256"] == null -> err("asset_sha256", "missing")
            sha == null -> err("asset_sha256", "not a string")
            !Sha256.matches(sha) -> err("asset_sha256", "must be 64 hex characters")
        }

        val files = mutableListOf<String>()
        when (val raw = block["vendor_files"]) {
            /* Absent: the app uses its built-in ordered probe list. */
            null -> Unit
            !is List<*> -> err("vendor_files", "not a list")
            else -> {
                if (raw.isEmpty()) {
                    err("vendor_files", "empty (omit the key to use the built-in list)")
                }
                val seen = mutableSetOf<String>()
                for (entry in raw) {
                    val path = (entry as? String)?.trim()
                    when {
                        path == null -> err("vendor_files", "${entry ?: "null"} is not a string")
                        !validVendorFile(path) -> err(
                            "vendor_files",
                            "$path is not a /vendor/ path shorter than $KO_TARGET_MAX bytes",
                        )
                        !seen.add(path) -> err("vendor_files", "$path is listed twice")
                        else -> files += path
                    }
                }
            }
        }

        if (errors.isNotEmpty()) return null to errors
        return ModuleConfig(
            version = version!!.toInt(),
            asset = asset!!,
            assetSha256 = sha!!.lowercase(),
            vendorFiles = files,
        ) to errors
    }

    /** The block's validation errors (empty when absent or valid). */
    fun validate(value: Any?): List<ConfigError> = parse(value).second
}
