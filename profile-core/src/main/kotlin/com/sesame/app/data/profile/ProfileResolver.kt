package com.sesame.app.data.profile

/**
 * The cleaned profile schema (v3) and its single validator.
 *
 * The document is one tree: `devices`, `kernel`, one block per engine
 * (`ghostlock`, `timer`). Every declared key is present; a key with no value
 * is `null` (never omitted), and a block may itself be null. The validator
 * refuses an unknown key in a block it owns, which is the same rule the native
 * mapper applies at the transport edge.
 */
object ProfileResolver {
    /** The one accepted document schema. */
    const val ProfileSchemaVersion = 3

    /** The one refusal sentence for a non-v3 document (import and run paths). */
    fun schemaErrorMessage(schema: Int?): String =
        "unsupported profile schema ${schema ?: "absent"}; " +
            "this build reads schema $ProfileSchemaVersion"

    /** The fixed syscall vocabulary, in the picker's display order. */
    val Syscalls = listOf("select_stack", "sigreturn", "multicast_waiter", "tcp_zerocopy")

    /** The carrier pair every branch states. */
    val Carriers = listOf("native", "compat")

    const val TimerKey = "timer"
    const val GhostlockKey = "ghostlock"
    const val KernelKey = "kernel"

    val TopKeys = setOf("schema_version", "release", "devices", "kernel", "ghostlock", "timer")

    private val SymbolKeys = listOf(
        "init_task", "init_cred", "root_task_group", "empty_zero_page",
        "selinux_state", "selinux_blob_sizes", "security_hook_heads",
        "nf_logger", "loggers_0_1", "boot_id", "panic_on_warn", "root_user",
        "init_user_ns", "init_ucounts", "init_groups",
    )
    private val StructKeys = listOf("task", "cred", "waiter", "rt_mutex", "selinux_state")
    private val TaskKeys = listOf(
        "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters",
        "pi_top_task", "pi_blocked_on", "pid", "tgid", "atomic_flags",
        "real_cred", "cred", "comm", "tasks", "seccomp",
    )
    private val CredKeys = listOf(
        "copy_size", "usage", "uid", "security", "security_sid", "caps", "refs",
    )
    private val UsageKeys = listOf("offset", "size", "value")
    private val CapsKeys = listOf("offset", "count", "mask")
    private val RefNames = listOf("user", "user_ns", "ucounts", "group_info")
    private val RefKeys = listOf("offset", "image")
    private val WaiterKeys = listOf("shape", "pi_tree", "task", "lock", "wake_state")
    private val AnchorKeys = listOf("image", "bytes", "stride", "scratch")
    private val GhostlockKeys = listOf(
        "syscall", "carrier", "anchor", "syscalls", "module", "execution",
    )
    private val ModuleKeys = listOf("asset", "sha256", "vendor_files")
    private val TimerKeys = listOf(
        "trigger", "match_bits", "sites", "object_size", "task_alloc_size",
        "node_offset", "cred", "trace",
    )
    private val TimerSitesKeys = listOf("timer", "task_alloc")
    private val TimerSiteKeys = listOf("offset")
    private val TaskAllocSiteKeys = listOf("low_bits")
    private val TimerCredKeys = listOf("request_size", "alloc_size")
    private val TraceKeys = listOf("gfp")
    private val GfpKeys = listOf("offset", "size")
    private val ExecutionKeys = listOf(
        "recommended_cpus", "heap", "race", "stages", "handoff", "consumer", "routes",
    )
    private val ExecCpusKeys = listOf("main", "consumer")
    private val ExecHeapKeys = listOf(
        "prepare_max_attempts", "prepare_timeout_ms", "kernelsnitch_timeout_ms",
    )
    private val ExecRaceKeys = listOf(
        "route_wait_ms", "route_done_timeout_ms", "setup_settle_us",
        "state_poll_interval_us",
    )
    private val ExecStagesKeys = listOf(
        "w1_attempts", "w1_settle_us", "w1_scratch_repair_attempts", "w2_attempts",
        "w2_settle_us", "w3_chain_rounds", "w3_attempts", "w3_settle_us",
    )
    private val ExecHandoffKeys = listOf(
        "pre_dispatch_settle_ms", "module_poll_attempts", "module_poll_interval_ms",
        "enforce_poll_attempts", "enforce_poll_interval_ms",
    )
    private val ExecConsumerKeys = listOf("max_calls", "burst_calls")
    private val ExecRoutesKeys = listOf("select_stack", "tcp_zerocopy")
    private val ExecSelectKeys = listOf("enter_delay_us", "timeout_us")
    private val ExecTcpKeys = listOf(
        "attempts", "arm_sequence", "post_receive_hold_iterations",
    )
    private val BranchCarrierKeys = mapOf(
        "select_stack" to mapOf(
            "native" to listOf("waiter_shift"),
            "compat" to listOf("waiter_home", "dst_depth", "futex_abi"),
        ),
        "sigreturn" to mapOf("native" to listOf("waiter_off"), "compat" to emptyList()),
        "multicast_waiter" to mapOf(
            "native" to listOf("waiter_off", "buffer_size"),
            "compat" to listOf("waiter_off", "buffer_size", "futex_abi"),
        ),
        "tcp_zerocopy" to mapOf("native" to emptyList(), "compat" to emptyList()),
    )
    private val SigWaiterOffKeys = listOf("plain", "sve")

    /** True when [document] is the cleaned shape rather than the old layout. */
    fun isCleanedShape(document: Map<String, Any?>): Boolean =
        document[KernelKey] is Map<*, *>

    // ---- validation -------------------------------------------------------

    /**
     * Validates one cleaned document.
     *
     * [requireStride] is false only for the generation flow's **probe frame**:
     * the frame is the measurement request itself, so the stride it is meant
     * to measure is legitimately absent (the run/import/export callers keep
     * the default).
     */
    fun validateMerged(
        document: Map<String, Any?>,
        requireStride: Boolean = true,
    ): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()
        validateTop(document, errors)
        if (errors.isNotEmpty()) return errors
        validateDevices(document, errors)
        val kernel = block(document, KernelKey, KernelKey, errors)
        if (kernel != null) validateKernel(kernel, errors)
        val ghostlock = block(document, GhostlockKey, GhostlockKey, errors)
        if (ghostlock != null) validateGhostlock(ghostlock, kernel, document["release"] as? String, errors)
        validateTimerBlock(block(document, TimerKey, TimerKey, errors), errors)
        if (document[GhostlockKey] == null && document[TimerKey] == null) {
            errors += ConfigError(GhostlockKey, "the profile selects no engine")
        }
        val kdp = (kernel?.get("kdp") as? Boolean) == true
        if (kdp) {
            val verdict = kernel?.get("dirtyfrag") as? String
            if (verdict != "feasible" && verdict != "unknown") {
                errors += ConfigError(
                    "kernel.dirtyfrag",
                    "a KDP image requires the DirtyFrag ladder",
                )
            }
        }
        /* A chain that needs W1 sprays mm_struct objects, and the spray is
         * sized by the measured slab stride. The measured database no longer
         * supplies one to a profile that lacks it (the generation flow writes
         * it into the document; only a root measurement may override it), so a
         * null stride is a refusal, not a fallback to the native default.
         * Module-only and timer-only documents do not spray. */
        val heap = kernel?.get("heap") as? Map<*, *>
        if (requireStride && heap != null && number(heap["stride"]) == null &&
            requiresW1(pipelineOrder(document))
        ) {
            errors += ConfigError(
                "kernel.heap.stride",
                "missing: the chain needs W1, and the spray is sized by the measured stride",
            )
        }
        return errors
    }

    private fun validateTop(document: Map<String, Any?>, errors: MutableList<ConfigError>) {
        for (key in document.keys) {
            if (key !in TopKeys) {
                errors += ConfigError(key, "unknown top-level key")
            }
        }
        for (key in TopKeys) {
            if (!document.containsKey(key)) {
                errors += ConfigError(key, "missing required key")
            }
        }
        val schema = (document["schema_version"] as? Number)?.toInt()
        if (schema != ProfileSchemaVersion) {
            errors += ConfigError("schema_version", schemaErrorMessage(schema))
        }
        val release = document["release"]
        if (release !is String || release.isBlank()) {
            errors += ConfigError("release", "missing or empty")
        }
    }

    private fun validateDevices(document: Map<String, Any?>, errors: MutableList<ConfigError>) {
        val value = document["devices"] ?: return
        val devices = value as? Map<*, *> ?: run {
            errors += ConfigError("devices", "not an object")
            return
        }
        for ((rawName, rawEntry) in devices) {
            val name = rawName?.toString() ?: continue
            val entry = rawEntry as? Map<*, *> ?: run {
                errors += ConfigError("devices.$name", "not an object")
                continue
            }
            checkKeys(entry, listOf("phys"), "devices.$name", errors)
            val phys = entry["phys"] ?: continue
            val body = phys as? Map<*, *> ?: run {
                errors += ConfigError("devices.$name.phys", "not an object")
                continue
            }
            checkKeys(body, listOf("load", "offset"), "devices.$name.phys", errors)
            for (key in listOf("load", "offset")) {
                if (!body.containsKey(key)) {
                    errors += ConfigError("devices.$name.phys.$key", "missing")
                } else if (body[key] != null && unsigned(body[key]) == null) {
                    errors += ConfigError("devices.$name.phys.$key", "not an address")
                }
            }
        }
    }

    private fun validateKernel(kernel: Map<*, *>, errors: MutableList<ConfigError>) {
        checkKeys(
            kernel,
            listOf("image_base", "symbols", "struct", "heap", "dirtyfrag", "kdp"),
            KernelKey,
            errors,
        )
        for (key in listOf("image_base", "symbols", "struct", "heap", "dirtyfrag", "kdp")) {
            if (!kernel.containsKey(key)) errors += ConfigError("kernel.$key", "missing")
        }
        if (kernel["image_base"] != null && unsigned(kernel["image_base"]) == null) {
            errors += ConfigError("kernel.image_base", "not an address")
        }
        block(kernel, "symbols", "kernel.symbols", errors)?.let { symbols ->
            checkKeys(symbols, SymbolKeys, "kernel.symbols", errors)
            for (key in SymbolKeys) {
                if (!symbols.containsKey(key)) errors += ConfigError("kernel.symbols.$key", "missing")
                else if (symbols[key] != null && unsigned(symbols[key]) == null) {
                    errors += ConfigError("kernel.symbols.$key", "not an address")
                }
            }
        }
        val structure = block(kernel, "struct", "kernel.struct", errors)
        if (structure != null) validateStruct(structure, errors)
        block(kernel, "heap", "kernel.heap", errors)?.let { heap ->
            checkKeys(heap, listOf("stride", "collisions"), "kernel.heap", errors)
            numbers(heap, listOf("stride", "collisions"), "kernel.heap", errors, required = false)
            val stride = number(heap["stride"])
            if (stride != null && stride !in 64L..8192L) {
                errors += ConfigError("kernel.heap.stride", "outside the plausible stride range")
            }
        }
        val verdict = kernel["dirtyfrag"]
        if (verdict != null && verdict !is String) {
            errors += ConfigError("kernel.dirtyfrag", "not a word")
        } else if (verdict is String && verdict !in setOf("feasible", "infeasible", "unknown")) {
            errors += ConfigError("kernel.dirtyfrag", "unknown verdict ${verdict}")
        }
        val kdp = kernel["kdp"]
        if (kdp != null && kdp !is Boolean) errors += ConfigError("kernel.kdp", "not a boolean")
        if (kdp == null) errors += ConfigError("kernel.kdp", "missing")
    }

    private fun validateStruct(structure: Map<*, *>, errors: MutableList<ConfigError>) {
        checkKeys(structure, StructKeys, "kernel.struct", errors)
        for (key in StructKeys) {
            if (!structure.containsKey(key)) {
                errors += ConfigError("kernel.struct.$key", "missing")
            }
        }
        block(structure, "task", "kernel.struct.task", errors)?.let { task ->
            checkKeys(task, TaskKeys, "kernel.struct.task", errors)
            numbers(task, TaskKeys, "kernel.struct.task", errors, required = false)
        }
        block(structure, "cred", "kernel.struct.cred", errors)?.let { cred ->
            validateCred(cred, errors)
        }
        block(structure, "waiter", "kernel.struct.waiter", errors)?.let { waiter ->
            checkKeys(waiter, WaiterKeys, "kernel.struct.waiter", errors)
            for (key in WaiterKeys) {
                if (!waiter.containsKey(key)) errors += ConfigError("kernel.struct.waiter.$key", "missing")
            }
            val shape = waiter["shape"]
            if (shape != null && shape !is String) {
                errors += ConfigError("kernel.struct.waiter.shape", "not a word")
            } else if (shape is String && shape !in setOf("compact", "node")) {
                errors += ConfigError("kernel.struct.waiter.shape", "unknown shape $shape")
            }
            numbers(
                waiter, WaiterKeys.filter { it != "shape" }, "kernel.struct.waiter", errors,
                required = false,
            )
        }
        block(structure, "rt_mutex", "kernel.struct.rt_mutex", errors)?.let { rt ->
            checkKeys(rt, listOf("size"), "kernel.struct.rt_mutex", errors)
            numbers(rt, listOf("size"), "kernel.struct.rt_mutex", errors, required = false)
        }
        block(structure, "selinux_state", "kernel.struct.selinux_state", errors)?.let { selinux ->
            checkKeys(selinux, listOf("enforcing", "initialized"), "kernel.struct.selinux_state", errors)
            val enforcing = number(selinux["enforcing"])
            val initialized = number(selinux["initialized"])
            if (enforcing != null && enforcing !in 0L..7L) {
                errors += ConfigError("kernel.struct.selinux_state.enforcing", "must be a byte offset")
            }
            if (initialized != null && initialized !in 0L..7L) {
                errors += ConfigError("kernel.struct.selinux_state.initialized", "must be a byte offset")
            }
        }
        val waiter = structure["waiter"] as? Map<*, *>
        val pi = number(waiter?.get("pi_tree"))
        val task = number(waiter?.get("task"))
        val lock = number(waiter?.get("lock"))
        val wake = number(waiter?.get("wake_state"))
        if (pi != null && task != null && pi >= task) {
            errors += ConfigError("kernel.struct.waiter.pi_tree", "must precede task")
        }
        if (task != null && lock != null && task >= lock) {
            errors += ConfigError("kernel.struct.waiter.task", "must precede lock")
        }
        if (lock != null && wake != null && lock >= wake) {
            errors += ConfigError("kernel.struct.waiter.lock", "must precede wake_state")
        }
    }

    private fun validateCred(cred: Map<*, *>, errors: MutableList<ConfigError>) {
        checkKeys(cred, CredKeys, "kernel.struct.cred", errors)
        for (key in CredKeys) {
            if (!cred.containsKey(key)) errors += ConfigError("kernel.struct.cred.$key", "missing")
        }
        val copy = number(cred["copy_size"])
        block(cred, "usage", "kernel.struct.cred.usage", errors)?.let { usage ->
            checkKeys(usage, UsageKeys, "kernel.struct.cred.usage", errors)
            numbers(usage, UsageKeys, "kernel.struct.cred.usage", errors, required = false)
            val offset = number(usage["offset"]) ?: 0L
            val size = number(usage["size"])
            if (size != null && size != 4L && size != 8L) {
                errors += ConfigError("kernel.struct.cred.usage.size", "must be 4 or 8")
            }
            if (size != null && copy != null && offset + size > copy) {
                errors += ConfigError("kernel.struct.cred.usage.size", "does not fit copy_size")
            }
        }
        block(cred, "caps", "kernel.struct.cred.caps", errors)?.let { caps ->
            checkKeys(caps, CapsKeys, "kernel.struct.cred.caps", errors)
            numbers(caps, CapsKeys.filter { it != "mask" }, "kernel.struct.cred.caps", errors, required = false)
            if (caps["mask"] != null && unsigned(caps["mask"]) == null) {
                errors += ConfigError("kernel.struct.cred.caps.mask", "not an address")
            }
            val offset = number(caps["offset"])
            val count = number(caps["count"])
            if (offset != null && count != null && copy != null && offset + count * 8 > copy) {
                errors += ConfigError("kernel.struct.cred.caps.offset", "does not fit copy_size")
            }
        }
        val uid = number(cred["uid"])
        val security = number(cred["security"])
        if (uid != null && copy != null && uid + 4 > copy) {
            errors += ConfigError("kernel.struct.cred.uid", "does not fit copy_size")
        }
        if (security != null && copy != null && copy < security + 8) {
            errors += ConfigError("kernel.struct.cred.copy_size", "must cover security + 8")
        }
        block(cred, "refs", "kernel.struct.cred.refs", errors)?.let { refs ->
            checkKeys(refs, RefNames, "kernel.struct.cred.refs", errors)
            for (name in RefNames) {
                if (!refs.containsKey(name)) {
                    errors += ConfigError("kernel.struct.cred.refs.$name", "missing")
                    continue
                }
                val ref = refs[name] ?: continue
                val body = ref as? Map<*, *> ?: run {
                    errors += ConfigError("kernel.struct.cred.refs.$name", "not an object")
                    continue
                }
                checkKeys(body, RefKeys, "kernel.struct.cred.refs.$name", errors)
                for (key in RefKeys) {
                    if (!body.containsKey(key)) {
                        errors += ConfigError("kernel.struct.cred.refs.$name.$key", "missing")
                    }
                }
                val offset = number(body["offset"])
                val image = body["image"]
                if (image != null && unsigned(image) == null) {
                    errors += ConfigError("kernel.struct.cred.refs.$name.image", "not an address")
                }
                /* The offset is a write target only when the reference is
                 * written; the named slot may also describe the timer's own
                 * credential object, which is a different copy bound. */
                if (image != null && offset != null && copy != null && offset + 8 > copy) {
                    errors += ConfigError("kernel.struct.cred.refs.$name.offset", "does not fit copy_size")
                }
            }
        }
    }

    private fun validateGhostlock(
        ghostlock: Map<*, *>,
        kernel: Map<*, *>?,
        release: String?,
        errors: MutableList<ConfigError>,
    ) {
        checkKeys(ghostlock, GhostlockKeys, GhostlockKey, errors)
        for (key in GhostlockKeys) {
            if (!ghostlock.containsKey(key)) errors += ConfigError("ghostlock.$key", "missing")
        }
        val syscall = ghostlock["syscall"]
        if (syscall != null && (syscall !is String || syscall !in Syscalls)) {
            errors += ConfigError("ghostlock.syscall", "unknown syscall ${syscall}")
        }
        val carrier = ghostlock["carrier"]
        if (carrier != null && (carrier !is String || carrier !in Carriers)) {
            errors += ConfigError("ghostlock.carrier", "unknown carrier ${carrier}")
        }
        block(ghostlock, "anchor", "ghostlock.anchor", errors)?.let { anchor ->
            checkKeys(anchor, AnchorKeys, "ghostlock.anchor", errors)
            for (key in AnchorKeys) {
                if (!anchor.containsKey(key)) errors += ConfigError("ghostlock.anchor.$key", "missing")
            }
            val image = unsigned(anchor["image"])
            val bytes = number(anchor["bytes"])
            val stride = number(anchor["stride"])
            val scratch = unsigned(anchor["scratch"])
            if (anchor["image"] != null && image == null) {
                errors += ConfigError("ghostlock.anchor.image", "not an address")
            }
            if (anchor["scratch"] != null && scratch == null) {
                errors += ConfigError("ghostlock.anchor.scratch", "not an address")
            }
            if (image != null && bytes != null && bytes >= 0 && scratch != null &&
                scratch >= image && scratch < image + bytes.toULong()
            ) {
                /* Inside the anchor: this is the reserved slot. The value is
                 * alias(scratch) + 0x100 and the erase's collateral spans
                 * 0x18 bytes from it. */
                val window = (scratch - image) + 0x100uL
                if (window + 0x18uL > bytes.toULong()) {
                    errors += ConfigError(
                        "ghostlock.anchor.scratch",
                        "collateral window leaves the anchor region",
                    )
                }
            }
            val strideValue = stride
            val rtSize = rtSize(kernel)
            if (rtSize != null) {
                if (rtSize !in 8L..256L) {
                    errors += ConfigError("kernel.struct.rt_mutex.size", "outside 8..256")
                }
                if (strideValue != null && strideValue < rtSize) {
                    errors += ConfigError(
                        "ghostlock.anchor.stride",
                        "smaller than kernel.struct.rt_mutex.size",
                    )
                }
                if (strideValue != null && rtSize != 0L && strideValue % rtSize != 0L) {
                    errors += ConfigError(
                        "ghostlock.anchor.stride",
                        "not a multiple of kernel.struct.rt_mutex.size",
                    )
                }
            }
        }

        val syscalls = ghostlock["syscalls"]
        val module = ghostlock["module"]
        if (syscalls == null && module == null) {
            errors += ConfigError("ghostlock.syscalls", "one of syscalls/module is required")
        }
        if (syscalls != null && module != null) {
            errors += ConfigError("ghostlock.module", "not allowed with syscalls")
        }
        if (syscalls != null) {
            val branches = syscalls as? Map<*, *> ?: run {
                errors += ConfigError("ghostlock.syscalls", "not an object")
                null
            }
            if (branches != null) {
                checkKeys(branches, Syscalls, "ghostlock.syscalls", errors)
                for (name in Syscalls) {
                    if (!branches.containsKey(name)) {
                        errors += ConfigError("ghostlock.syscalls.$name", "missing")
                        continue
                    }
                    val branch = branches[name] ?: continue
                    val body = branch as? Map<*, *> ?: run {
                        errors += ConfigError("ghostlock.syscalls.$name", "not an object")
                        continue
                    }
                    validateBranch(name, body, errors)
                    validateRouteWaiterShape(name, kernel, release, errors)
                    validateMulticastGroup(name, body, kernel, errors)
                }
            }
            validateW1Facts(kernel, errors)
            if (syscall == null) {
                errors += ConfigError("ghostlock.syscall", "no syscall selected")
            } else if (syscall is String && syscall in Syscalls) {
                val branch = (syscalls as? Map<*, *>)?.get(syscall) as? Map<*, *>
                if (branch == null) {
                    errors += ConfigError("ghostlock.syscall", "$syscall is null in syscalls")
                } else if (carrier is String) {
                    if (branch[carrier] !is Map<*, *>) {
                        errors += ConfigError(
                            "ghostlock.carrier",
                            "$syscall declares no $carrier carrier",
                        )
                    }
                }

            }
        }
        block(ghostlock, "module", "ghostlock.module", errors)?.let { block ->
            checkKeys(block, ModuleKeys, "ghostlock.module", errors)
            errors += ModuleBlock.validate(block)
        }
        block(ghostlock, "execution", "ghostlock.execution", errors)?.let { execution ->
            validateExecution(execution, errors)
        }
    }

    private fun validateBranch(name: String, branch: Map<*, *>, errors: MutableList<ConfigError>) {
        checkKeys(branch, Carriers, "ghostlock.syscalls.$name", errors)
        for (carrier in Carriers) {
            if (!branch.containsKey(carrier)) {
                errors += ConfigError("ghostlock.syscalls.$name.$carrier", "missing")
            }
        }
        for (carrier in Carriers) {
            val body = branch[carrier] ?: continue
            val map = body as? Map<*, *> ?: run {
                errors += ConfigError("ghostlock.syscalls.$name.$carrier", "not an object")
                continue
            }
            val allowed = BranchCarrierKeys[name]?.get(carrier) ?: emptyList()
            checkKeys(map, allowed, "ghostlock.syscalls.$name.$carrier", errors)
            if (name == "sigreturn" && carrier == "native") {
                block(map, "waiter_off", "ghostlock.syscalls.sigreturn.native.waiter_off", errors)
                    ?.let { off ->
                        checkKeys(
                            off, SigWaiterOffKeys,
                            "ghostlock.syscalls.sigreturn.native.waiter_off", errors,
                        )
                        numbers(
                            off, SigWaiterOffKeys,
                            "ghostlock.syscalls.sigreturn.native.waiter_off", errors,
                            required = false,
                        )
                    }
            } else if (name == "select_stack" && carrier == "compat") {
                val abi = map["futex_abi"]
                if (abi != "time32" && abi != "time64") {
                    errors += ConfigError(
                        "ghostlock.syscalls.select_stack.compat.futex_abi",
                        "must be time32 or time64",
                    )
                }
            } else if (name == "multicast_waiter" && carrier == "compat") {
                val abi = map["futex_abi"]
                if (abi != "time32" && abi != "time64") {
                    errors += ConfigError(
                        "ghostlock.syscalls.multicast_waiter.compat.futex_abi",
                        "must be time32 or time64",
                    )
                }
            }
            /* A declared carrier must be complete: the native has no
             * geometry fallback for a half-stated carrier. */
            val required = when {
                name == "select_stack" && carrier == "compat" ->
                    listOf("waiter_home", "dst_depth")
                name == "select_stack" -> listOf("waiter_shift")
                name == "multicast_waiter" && carrier == "compat" ->
                    listOf("waiter_off", "buffer_size")
                name == "multicast_waiter" -> listOf("waiter_off", "buffer_size")
                name == "sigreturn" ->
                    listOf("waiter_off.plain", "waiter_off.sve")
                else -> emptyList()
            }
            for (field in required) {
                val path = "ghostlock.syscalls.$name.$carrier.$field"
                if (field.startsWith("waiter_off.")) {
                    val off = map["waiter_off"] as? Map<*, *>
                    if (off == null || number(off[field.substringAfter('.')]) == null) {
                        errors += ConfigError(path, "required")
                    }
                } else if (number(map[field]) == null) {
                    errors += ConfigError(path, "required")
                }
            }
            if (name != "sigreturn" && name != "tcp_zerocopy") {
                numbers(
                    map, allowed - "futex_abi",
                    "ghostlock.syscalls.$name.$carrier", errors, required = false,
                )
            }
        }
        if ((name == "sigreturn" || name == "tcp_zerocopy") && branch["compat"] != null) {
            errors += ConfigError("ghostlock.syscalls.$name.compat", "this syscall has no compat carrier")
        }
    }

    /**
     * A native (W1) chain needs the credential and task facts; a null there
     * has no compiled default and the run would write at address 0. A
     * timer-only or module-only document never runs W1 and is exempt.
     */
    private fun validateW1Facts(kernel: Map<*, *>?, errors: MutableList<ConfigError>) {
        if (kernel == null) {
            errors += ConfigError("kernel", "required for a native chain")
            return
        }
        val symbols = kernel["symbols"] as? Map<*, *>
        for (name in listOf("init_task", "init_cred", "root_task_group", "selinux_state")) {
            if (unsigned(symbols?.get(name)) == null) {
                errors += ConfigError("kernel.symbols.$name", "required for a native chain")
            }
        }
        val structure = kernel["struct"] as? Map<*, *>
        val task = structure?.get("task") as? Map<*, *>
        if (task == null) {
            errors += ConfigError("kernel.struct.task", "required for a native chain")
        } else {
            for (field in TaskKeys) {
                if (number(task[field]) == null) {
                    errors += ConfigError("kernel.struct.task.$field", "required for a native chain")
                }
            }
        }
        val cred = structure?.get("cred") as? Map<*, *>
        if (cred == null) {
            errors += ConfigError("kernel.struct.cred", "required for a native chain")
        } else {
            val copySize = number(cred["copy_size"])
            if (copySize == null || copySize == 0L) {
                errors += ConfigError("kernel.struct.cred.copy_size", "required for a native chain")
            }
            val caps = cred["caps"] as? Map<*, *>
            if (caps == null || number(caps["offset"]) == null ||
                (number(caps["count"]) ?: 0L) == 0L
            ) {
                errors += ConfigError("kernel.struct.cred.caps", "required for a native chain")
            }
        }
    }

    private fun validateRouteWaiterShape(
        name: String,
        kernel: Map<*, *>?,
        release: String?,
        errors: MutableList<ConfigError>,
    ) {
        if (name != "multicast_waiter" && name != "sigreturn") return
        val waiter = (kernel?.get("struct") as? Map<*, *>)?.get("waiter") as? Map<*, *>
        val shape = waiter?.get("shape") as? String
        val nonCompact = when (shape) {
            "compact" -> false
            "node" -> true
            else -> !releaseIsCompactWaiter(release)
        }
        if (nonCompact) {
            errors += ConfigError(
                "ghostlock.syscalls.$name",
                "needs the compact rt_mutex_waiter strip; this profile's waiter is the " +
                    "6.6+ rt_waiter_node shape",
            )
        }
    }

    private fun validateMulticastGroup(
        name: String,
        branch: Map<*, *>,
        kernel: Map<*, *>?,
        errors: MutableList<ConfigError>,
    ) {
        if (name != "multicast_waiter") return
        val compat = branch["compat"] as? Map<*, *> ?: return
        val lock = number(
            ((kernel?.get("struct") as? Map<*, *>)?.get("waiter") as? Map<*, *>)?.get("lock"),
        )
        val off = number(compat["waiter_off"])
        val bytes = number(compat["buffer_size"])
        if (off != null && bytes != null && lock != null && off > 0 && off + lock + 8 > bytes) {
            errors += ConfigError(
                "ghostlock.syscalls.multicast_waiter.compat.waiter_off",
                "the strip does not fit the compat buffer",
            )
        }
    }

    fun validateTimerBlock(timer: Map<*, *>?, errors: MutableList<ConfigError>) {
        if (timer == null) return
        checkKeys(timer, TimerKeys, TimerKey, errors)
        for (key in TimerKeys) {
            if (!timer.containsKey(key)) errors += ConfigError("timer.$key", "missing")
        }
        val trigger = timer["trigger"]
        if (trigger != "killable" && trigger != "core_state") {
            errors += ConfigError("timer.trigger", "must be killable or core_state")
        }
        val matchBits = number(timer["match_bits"])
        if (matchBits != 21L) errors += ConfigError("timer.match_bits", "must be 21")
        block(timer, "sites", "timer.sites", errors)?.let { sites ->
            checkKeys(sites, TimerSitesKeys, "timer.sites", errors)
            block(sites, "timer", "timer.sites.timer", errors)?.let { site ->
                checkKeys(site, TimerSiteKeys, "timer.sites.timer", errors)
                if (site["offset"] == null || unsigned(site["offset"]) == null) {
                    errors += ConfigError("timer.sites.timer.offset", "not an address")
                }
            }
            block(sites, "task_alloc", "timer.sites.task_alloc", errors)?.let { site ->
                checkKeys(site, TaskAllocSiteKeys, "timer.sites.task_alloc", errors)
                val low = unsigned(site["low_bits"])
                if (low == null || low > 0x1FFFFFUL) {
                    errors += ConfigError("timer.sites.task_alloc.low_bits", "must fit 21 bits")
                }
            }
        }
        val objectSize = number(timer["object_size"])
        if (objectSize != null && objectSize !in 16L..4096L) {
            errors += ConfigError("timer.object_size", "out of range")
        }
        val taskAlloc = number(timer["task_alloc_size"])
        if (taskAlloc != null && taskAlloc !in 512L..16384L) {
            errors += ConfigError("timer.task_alloc_size", "out of range")
        }
        val node = number(timer["node_offset"])
        if (node != null && node !in 0x40L..0x400L) {
            errors += ConfigError("timer.node_offset", "out of range")
        }
        block(timer, "cred", "timer.cred", errors)?.let { cred ->
            checkKeys(cred, TimerCredKeys, "timer.cred", errors)
            val request = number(cred["request_size"])
            val alloc = number(cred["alloc_size"])
            if (request != null && request !in 16L..1024L) {
                errors += ConfigError("timer.cred.request_size", "out of range")
            }
            if (alloc != null && request != null && (alloc < request || alloc > 1024L)) {
                errors += ConfigError("timer.cred.alloc_size", "out of range")
            }
        }
        block(timer, "trace", "timer.trace", errors)?.let { trace ->
            checkKeys(trace, TraceKeys, "timer.trace", errors)
            block(trace, "gfp", "timer.trace.gfp", errors)?.let { gfp ->
                checkKeys(gfp, GfpKeys, "timer.trace.gfp", errors)
                val offset = number(gfp["offset"])
                val size = number(gfp["size"])
                if (offset != null && offset !in 8L..64L) {
                    errors += ConfigError("timer.trace.gfp.offset", "out of range")
                }
                if (size != null && size != 4L && size != 8L) {
                    errors += ConfigError("timer.trace.gfp.size", "must be 4 or 8")
                }
            }
        }
    }

    private fun validateExecution(execution: Map<*, *>, errors: MutableList<ConfigError>) {
        checkKeys(execution, ExecutionKeys, "ghostlock.execution", errors)
        /* `recommended_cpus` and `consumer` are transport-resolved (the CPU
         * pair is the native's runtime choice; the consumer cadence is hoisted
         * out of the selected route's preset at render time). One may be
         * stated in an authored document; both are validated when present. */
        for (key in listOf("heap", "race", "stages", "handoff", "routes")) {
            if (!execution.containsKey(key)) {
                errors += ConfigError("ghostlock.execution.$key", "missing")
            }
        }
        val blocks = mapOf(
            "recommended_cpus" to ExecCpusKeys,
            "heap" to ExecHeapKeys,
            "race" to ExecRaceKeys,
            "stages" to ExecStagesKeys,
            "handoff" to ExecHandoffKeys,
            "consumer" to ExecConsumerKeys,
        )
        for ((name, keys) in blocks) {
            if (!execution.containsKey(name)) continue
            block(execution, name, "ghostlock.execution.$name", errors)?.let { body ->
                checkKeys(body, keys, "ghostlock.execution.$name", errors)
                numbers(body, keys, "ghostlock.execution.$name", errors, required = true)
            }
        }
        block(execution, "routes", "ghostlock.execution.routes", errors)?.let { routes ->
            checkKeys(routes, Syscalls, "ghostlock.execution.routes", errors)
            /* The route presets also carry the consumer cadence, which the
             * render hoists into `execution.consumer`; it is an app-side key
             * and never reaches the native's route block. */
            val appKeys = listOf("consumer_max_calls", "consumer_burst_calls")
            for ((name, keys) in listOf(
                "select_stack" to ExecSelectKeys,
                "tcp_zerocopy" to ExecTcpKeys,
                "sigreturn" to emptyList(),
                "multicast_waiter" to emptyList(),
            )) {
                if (!routes.containsKey(name)) continue
                block(routes, name, "ghostlock.execution.routes.$name", errors)?.let { body ->
                    checkKeys(body, keys + appKeys, "ghostlock.execution.routes.$name", errors)
                    numbers(body, keys, "ghostlock.execution.routes.$name", errors, required = false)
                    numbers(
                        body, appKeys, "ghostlock.execution.routes.$name", errors,
                        required = false,
                    )
                }
            }
        }
    }

    // ---- derivations ------------------------------------------------------

    /** The fixed ladder (decision 4), derived from the document. */
    fun pipelineOrder(document: Map<String, Any?>): List<String> {
        val ghostlock = document[GhostlockKey] as? Map<*, *>
        val timer = document[TimerKey] as? Map<*, *>
        val kernel = document[KernelKey] as? Map<*, *>
        if (ghostlock == null) return if (timer != null) listOf("timer") else emptyList()
        if (ghostlock["module"] is Map<*, *>) return listOf("module")
        val kdp = kernel?.get("kdp") == true
        if (kdp) return listOf("dirtyfrag")
        val verdict = kernel?.get("dirtyfrag") as? String
        return if (verdict == "feasible") listOf("dirtyfrag", "ghostlock") else listOf("ghostlock")
    }

    /** True when the chain needs W1 (module and timer do not). */
    fun requiresW1(order: List<String>): Boolean =
        order.any { it == "ghostlock" || it == "dirtyfrag" }

    /** The branch's declared carriers, in the fixed pair order. */
    fun carriersOf(branch: Map<*, *>?): List<String> {
        if (branch == null) return emptyList()
        return Carriers.filter { branch[it] is Map<*, *> }
    }

    /** Every syscall branch the document declares, in the display order. */
    fun syscallsOf(document: Map<String, Any?>): List<Pair<String, List<String>>> {
        val ghostlock = document[GhostlockKey] as? Map<*, *> ?: return emptyList()
        val syscalls = ghostlock["syscalls"] as? Map<*, *> ?: return emptyList()
        return Syscalls.mapNotNull { name ->
            val branch = syscalls[name] as? Map<*, *> ?: return@mapNotNull null
            name to carriersOf(branch)
        }
    }

    /** The resolved device's physical pair (after [PerDevicePhys.resolveFor]). */
    fun physOf(document: Map<String, Any?>): Pair<Long?, Long?> {
        val devices = document["devices"] as? Map<*, *> ?: return null to null
        val entry = devices.values.firstOrNull() as? Map<*, *> ?: return null to null
        val phys = entry["phys"] as? Map<*, *> ?: return null to null
        return number(phys["load"]) to number(phys["offset"])
    }

    fun anchorOf(document: Map<String, Any?>): Triple<Long?, Long?, Long?>? {
        val anchor = (document[GhostlockKey] as? Map<*, *>)?.get("anchor") as? Map<*, *>
            ?: return null
        return Triple(
            number(anchor["image"]),
            number(anchor["bytes"]),
            number(anchor["stride"]),
        )
    }

    private fun releaseIsCompactWaiter(release: String?): Boolean {
        val parts = release?.split('.') ?: return false
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: return false
        return major == 5 || (major == 6 && minor == 1)
    }

    private fun rtSize(kernel: Map<*, *>?): Long? =
        number(((kernel?.get("struct") as? Map<*, *>)?.get("rt_mutex") as? Map<*, *>)?.get("size"))

    // ---- helpers ----------------------------------------------------------

    private fun block(
        parent: Map<*, *>,
        key: String,
        path: String,
        errors: MutableList<ConfigError>,
    ): Map<*, *>? {
        if (!parent.containsKey(key)) {
            errors += ConfigError(path, "missing required key")
            return null
        }
        val value = parent[key] ?: return null
        val map = value as? Map<*, *>
        if (map == null) {
            errors += ConfigError(path, "not an object")
            return null
        }
        return map
    }

    private fun checkKeys(
        map: Map<*, *>,
        known: List<String>,
        path: String,
        errors: MutableList<ConfigError>,
    ) {
        for (key in map.keys) {
            val name = key?.toString() ?: continue
            if (name !in known) errors += ConfigError("$path.$name", "unknown key")
        }
    }

    private fun numbers(
        map: Map<*, *>,
        keys: List<String>,
        path: String,
        errors: MutableList<ConfigError>,
        required: Boolean,
    ) {
        for (key in keys) {
            if (!map.containsKey(key)) {
                if (required) errors += ConfigError("$path.$key", "missing")
                continue
            }
            val value = map[key] ?: continue
            if (number(value) == null) {
                errors += ConfigError("$path.$key", "not a number")
            }
        }
    }

    private fun number(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Number -> value.toDouble().takeIf { it == Math.floor(it) }?.toLong()
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toLongOrNull(16)
            else text.toLongOrNull()
        }
        else -> null
    }

    private fun unsigned(value: Any?): ULong? = when (value) {
        is Number -> value.toLong().toULong()
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toULongOrNull(16)
            else text.toULongOrNull()
        }
        else -> null
    }
}
