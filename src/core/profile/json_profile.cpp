/* SPDX-License-Identifier: MIT
 *
 * The ghostlock engine's document mapper: the app-rendered canonical JSON to
 * the transport struct the attack consumes. One table per block the engine
 * owns; a key outside those tables is refused (the app validator's rule, at
 * the native edge), which is what replaces the old wire digest's key-drift
 * detection.
 */
#include "profile/json_profile.h"

#include "profile/json.h"
#include "profile/pipeline_check.h"

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include <iterator>
#include <optional>
#include <string_view>

namespace ghostlock::json_profile {
    namespace {
        struct ctx {
            char *err;
            size_t errlen;
            bool failed;
        };

        bool fail(ctx &c, const char *fmt, ...) {
            va_list ap;

            if (c.err && c.errlen && !c.failed) {
                va_start(ap, fmt);
                vsnprintf(c.err, c.errlen, fmt, ap);
                va_end(ap);
            }
            c.failed = true;
            return false;
        }

        bool null_or_absent(const gl_json_value *v) {
            return v == nullptr || v->type == GL_JSON_NULL;
        }

        const gl_json_value *member(const gl_json_value *object, const char *key) {
            return gl_json_get(object, key);
        }

        bool want_object(const gl_json_value *v, const char *path, ctx &c) {
            if (v && v->type != GL_JSON_OBJECT) {
                return fail(c, "profile: %s is not an object", path);
            }
            return true;
        }

        bool keys(const gl_json_value *v, const char *const *known, size_t count,
                  const char *path, ctx &c) {
            return gl_json_check_keys(v, known, count, path, c.err, c.errlen) == 0;
        }

        bool u64_field(const gl_json_value *object, const char *key,
                       const char *path, unsigned long long *out, bool required,
                       ctx &c) {
            const gl_json_value *v = member(object, key);

            if (null_or_absent(v)) {
                if (required) {
                    return fail(c, "profile: %s.%s is required", path, key);
                }
                return true;
            }
            if (gl_json_u64(v, out) != 0) {
                return fail(c, "profile: %s.%s is not an integer or a 0x string",
                            path, key);
            }
            return true;
        }

        bool u32_field(const gl_json_value *object, const char *key,
                       const char *path, uint32_t *out, bool required, ctx &c) {
            unsigned long long value = 0;

            if (!u64_field(object, key, path, &value, required, c)) return false;
            if (value > 0xffffffffULL) {
                return fail(c, "profile: %s.%s is out of range", path, key);
            }
            *out = static_cast<uint32_t>(value);
            return true;
        }

        bool opt_u64(const gl_json_value *object, const char *key, const char *path,
                     std::optional<uint64_t> &out, ctx &c) {
            const gl_json_value *v = member(object, key);
            unsigned long long value = 0;

            if (null_or_absent(v)) return true;
            if (gl_json_u64(v, &value) != 0) {
                return fail(c, "profile: %s.%s is not an integer or a 0x string",
                            path, key);
            }
            out = value;
            return true;
        }

        bool opt_u32(const gl_json_value *object, const char *key, const char *path,
                     std::optional<uint32_t> &out, ctx &c) {
            const gl_json_value *v = member(object, key);
            unsigned long long value = 0;

            if (null_or_absent(v)) return true;
            if (gl_json_u64(v, &value) != 0 || value > 0xffffffffULL) {
                return fail(c, "profile: %s.%s is not a 32-bit integer", path, key);
            }
            out = static_cast<uint32_t>(value);
            return true;
        }

        bool opt_u8(const gl_json_value *object, const char *key, const char *path,
                    std::optional<uint8_t> &out, ctx &c) {
            const gl_json_value *v = member(object, key);
            unsigned long long value = 0;

            if (null_or_absent(v)) return true;
            if (gl_json_u64(v, &value) != 0 || value > 0xffULL) {
                return fail(c, "profile: %s.%s is not an 8-bit integer", path, key);
            }
            out = static_cast<uint8_t>(value);
            return true;
        }

        bool opt_i32(const gl_json_value *object, const char *key, const char *path,
                     std::optional<int32_t> &out, ctx &c) {
            const gl_json_value *v = member(object, key);
            long long value = 0;

            if (null_or_absent(v)) return true;
            if (gl_json_i64(v, &value) != 0 || value < INT32_MIN ||
                value > INT32_MAX) {
                return fail(c, "profile: %s.%s is not a 32-bit integer", path, key);
            }
            out = static_cast<int32_t>(value);
            return true;
        }

        struct word {
            const char *token;
            uint8_t value;
        };

        bool word_field(const gl_json_value *object, const char *key,
                        const char *path, const word *table, size_t count,
                        uint8_t *out, bool required, ctx &c) {
            const gl_json_value *v = member(object, key);

            if (null_or_absent(v)) {
                if (required) {
                    return fail(c, "profile: %s.%s is required", path, key);
                }
                return true;
            }
            if (v->type != GL_JSON_STRING) {
                return fail(c, "profile: %s.%s is not a word", path, key);
            }
            for (size_t i = 0; i < count; i++) {
                if (strcmp(v->string, table[i].token) == 0) {
                    *out = table[i].value;
                    return true;
                }
            }
            return fail(c, "profile: %s.%s has an unknown value '%s'", path, key,
                        v->string);
        }

        /* --- word tables --------------------------------------------------- */
        constexpr word kSyscallWords[] = {
            {"tcp_zerocopy", profile::kRouteTcpZerocopy},
            {"select_stack", profile::kRouteSelectStack},
            {"multicast_waiter", profile::kRouteMulticastWaiter},
            {"sigreturn", profile::kRouteSigreturn},
        };

        constexpr uint8_t kCarrierNative = 0;
        constexpr uint8_t kCarrierCompat = 1;
        constexpr word kCarrierWords[] = {
            {"native", kCarrierNative},
            {"compat", kCarrierCompat},
        };

        constexpr word kFutexAbiWords[] = {
            {"time32", uint8_t{0}},
            {"time64", uint8_t{1}},
        };

        constexpr word kWaiterShapeWords[] = {
            {"node", uint8_t{0}},
            {"compact", uint8_t{1}},
        };

        const char *syscall_name(uint8_t route) {
            for (const word &entry: kSyscallWords) {
                if (entry.value == route) return entry.token;
            }
            return "?";
        }

        /* --- key sets ------------------------------------------------------ */
        const char *const kTopKeys[] = {
            "schema_version", "release", "mode",        "safe_mode",
            "devices",        "kernel",  "ghostlock",   "timer",
        };
        const char *const kKernelKeys[] = {
            "image_base", "symbols", "struct", "heap", "dirtyfrag", "kdp",
        };
        const char *const kSymbolKeys[] = {
            "init_task",      "init_cred",       "root_task_group",
            "empty_zero_page", "selinux_state",  "selinux_blob_sizes",
            "security_hook_heads", "nf_logger",  "loggers_0_1",
            "boot_id",        "panic_on_warn",   "root_user",
            "init_user_ns",   "init_ucounts",    "init_groups",
        };
        const char *const kStructKeys[] = {
            "task", "cred", "waiter", "rt_mutex", "selinux_state",
        };
        const char *const kTaskKeys[] = {
            "prio",             "normal_prio", "sched_task_group",
            "pi_lock",          "pi_waiters",  "pi_top_task",
            "pi_blocked_on",    "pid",         "tgid",
            "atomic_flags",     "real_cred",   "cred",
            "comm",             "tasks",       "seccomp",
        };
        const char *const kCredKeys[] = {
            "copy_size", "usage", "uid", "security", "security_sid", "caps",
            "refs",
        };
        const char *const kUsageKeys[] = {"offset", "size", "value"};
        const char *const kCapsKeys[] = {"offset", "count", "mask"};
        const char *const kRefsKeys[] = {
            "user", "user_ns", "ucounts", "group_info",
        };
        const char *const kRefKeys[] = {"offset", "image"};
        const char *const kWaiterKeys[] = {
            "shape", "pi_tree", "task", "lock", "wake_state",
        };
        const char *const kRtMutexKeys[] = {"size"};
        const char *const kSelinuxKeys[] = {"enforcing", "initialized"};
        const char *const kHeapKeys[] = {"stride", "collisions"};
        const char *const kDeviceKeys[] = {"phys"};
        const char *const kPhysKeys[] = {"load", "offset"};
        const char *const kGhostlockKeys[] = {
            "syscall", "carrier", "anchor", "syscalls", "module", "execution",
        };
        const char *const kAnchorKeys[] = {
            "image", "bytes", "stride", "scratch",
        };
        const char *const kSyscallsKeys[] = {
            "tcp_zerocopy", "select_stack", "multicast_waiter", "sigreturn",
        };
        const char *const kBranchKeys[] = {"native", "compat"};
        const char *const kSelectNativeKeys[] = {"waiter_shift"};
        const char *const kSelectCompatKeys[] = {
            "waiter_home", "dst_depth", "futex_abi",
        };
        const char *const kMcastNativeKeys[] = {"waiter_off", "buffer_size"};
        const char *const kMcastCompatKeys[] = {
            "waiter_off", "buffer_size", "futex_abi",
        };
        const char *const kSigNativeKeys[] = {"waiter_off"};
        const char *const kSigWaiterOffKeys[] = {"plain", "sve"};
        const char *const kModuleKeys[] = {"asset", "sha256", "vendor_files"};
        const char *const kExecKeys[] = {
            "recommended_cpus", "heap", "race", "stages", "handoff", "consumer",
            "routes",
        };
        const char *const kExecCpusKeys[] = {"main", "consumer"};
        const char *const kExecHeapKeys[] = {
            "prepare_max_attempts", "prepare_timeout_ms",
            "kernelsnitch_timeout_ms",
        };
        const char *const kExecRaceKeys[] = {
            "route_wait_ms", "route_done_timeout_ms", "setup_settle_us",
            "state_poll_interval_us",
        };
        const char *const kExecStagesKeys[] = {
            "w1_attempts", "w1_settle_us", "w1_scratch_repair_attempts",
            "w2_attempts", "w2_settle_us", "w3_chain_rounds", "w3_attempts",
            "w3_settle_us",
        };
        const char *const kExecHandoffKeys[] = {
            "pre_dispatch_settle_ms", "module_poll_attempts",
            "module_poll_interval_ms", "enforce_poll_attempts",
            "enforce_poll_interval_ms",
        };
        const char *const kExecConsumerKeys[] = {"max_calls", "burst_calls"};
        const char *const kExecRoutesKeys[] = {
            "select_stack", "tcp_zerocopy",
        };
        const char *const kExecSelectKeys[] = {"enter_delay_us", "timeout_us"};
        const char *const kExecTcpKeys[] = {
            "attempts", "arm_sequence", "post_receive_hold_iterations",
        };

        template<size_t N>
        bool keys_of(const gl_json_value *v, const char *const (&known)[N],
                     const char *path, ctx &c) {
            return keys(v, known, N, path, c);
        }

        /* --- block mappers ------------------------------------------------ */
        bool map_symbols(const gl_json_value *kernel, profile::kernel_offsets *out,
                         ctx &c) {
            const gl_json_value *symbols = member(kernel, "symbols");

            if (null_or_absent(symbols)) return true;
            if (!want_object(symbols, "kernel.symbols", c)) return false;
            if (!keys_of(symbols, kSymbolKeys, "kernel.symbols", c)) return false;
            struct {
                const char *key;
                uint64_t *slot;
            } const table[] = {
                {"init_task", &out->offsets.init_task},
                {"init_cred", &out->offsets.init_cred},
                {"root_task_group", &out->offsets.root_task_group},
                {"empty_zero_page", &out->offsets.empty_zero_page},
                {"selinux_state", &out->offsets.selinux_enforcing},
                {"selinux_blob_sizes", &out->offsets.selinux_blob_sizes},
                {"security_hook_heads", &out->offsets.security_hook_heads},
                {"nf_logger", &out->offsets.slide_nfulnl_logger},
                {"loggers_0_1", &out->offsets.slide_loggers_0_1},
                {"boot_id", &out->offsets.slide_boot_id},
            };
            for (const auto &entry: table) {
                unsigned long long value = 0;
                if (!u64_field(symbols, entry.key, "kernel.symbols", &value, false,
                               c)) {
                    return false;
                }
                *entry.slot = value;
            }
            return true;
        }

        bool map_task(const gl_json_value *s, profile::kernel_offsets *out, ctx &c) {
            const gl_json_value *task = member(s, "task");

            if (null_or_absent(task)) return true;
            if (!want_object(task, "kernel.struct.task", c)) return false;
            if (!keys_of(task, kTaskKeys, "kernel.struct.task", c)) return false;
            struct {
                const char *key;
                uint32_t *slot;
            } const table[] = {
                {"prio", &out->task.prio},
                {"normal_prio", &out->task.normal_prio},
                {"sched_task_group", &out->task.sched_task_group},
                {"pi_lock", &out->task.pi_lock},
                {"pi_waiters", &out->task.pi_waiters},
                {"pi_top_task", &out->task.pi_top_task},
                {"pi_blocked_on", &out->task.pi_blocked_on},
                {"pid", &out->task.pid},
                {"tgid", &out->task.tgid},
                {"atomic_flags", &out->task.atomic_flags},
                {"real_cred", &out->task.real_cred},
                {"cred", &out->task.cred},
                {"comm", &out->task.comm},
                {"tasks", &out->task.tasks},
                {"seccomp", &out->task.seccomp},
            };
            for (const auto &entry: table) {
                if (!u32_field(task, entry.key, "kernel.struct.task", entry.slot,
                               false, c)) {
                    return false;
                }
            }
            return true;
        }

        bool map_cred_refs(const gl_json_value *refs, profile::kernel_offsets *out,
                           ctx &c) {
            if (null_or_absent(refs)) return true;
            if (!want_object(refs, "kernel.struct.cred.refs", c)) return false;
            if (!keys_of(refs, kRefsKeys, "kernel.struct.cred.refs", c)) return false;
            /* The transport writes `ref_count` references in slot order, so
             * the image-bearing references are compacted into slots 0..n-1.
             * (The document names them; the kernel only needs offset/image
             * pairs.) A reference with an image must state where to write it. */
            static const char *const order[] = {
                "user", "user_ns", "ucounts", "group_info",
            };
            uint32_t count = 0;
            for (const char *name: order) {
                const gl_json_value *ref = member(refs, name);
                std::optional<uint64_t> image;

                if (null_or_absent(ref)) continue;
                if (!want_object(ref, "kernel.struct.cred.refs.<name>", c)) {
                    return false;
                }
                if (!keys_of(ref, kRefKeys, "kernel.struct.cred.refs.<name>", c)) {
                    return false;
                }
                if (!opt_u64(ref, "image", "kernel.struct.cred.refs.<name>", image,
                             c)) {
                    return false;
                }
                if (!image) continue;
                if (count >= 4) {
                    return fail(c,
                                "profile: kernel.struct.cred.refs carries more "
                                "than four writable references");
                }
                unsigned long long offset = 0;
                if (!u64_field(ref, "offset", "kernel.struct.cred.refs.<name>",
                               &offset, true, c)) {
                    return false;
                }
                if (offset > 0xffffffffULL) {
                    return fail(c,
                                "profile: kernel.struct.cred.refs.%s.offset is out "
                                "of range",
                                name);
                }
                uint32_t *slot_offset = nullptr;
                uint64_t *slot_image = nullptr;
                switch (count) {
                    case 0:
                        slot_offset = &out->credential.ref0_offset;
                        slot_image = &out->credential.ref0_image;
                        break;
                    case 1:
                        slot_offset = &out->credential.ref1_offset;
                        slot_image = &out->credential.ref1_image;
                        break;
                    case 2:
                        slot_offset = &out->credential.ref2_offset;
                        slot_image = &out->credential.ref2_image;
                        break;
                    default:
                        slot_offset = &out->credential.ref3_offset;
                        slot_image = &out->credential.ref3_image;
                        break;
                }
                *slot_offset = static_cast<uint32_t>(offset);
                *slot_image = *image;
                count++;
            }
            out->credential.ref_count = count;
            return true;
        }

        bool map_cred(const gl_json_value *s, profile::kernel_offsets *out, ctx &c) {
            const gl_json_value *cred = member(s, "cred");

            if (null_or_absent(cred)) return true;
            if (!want_object(cred, "kernel.struct.cred", c)) return false;
            if (!keys_of(cred, kCredKeys, "kernel.struct.cred", c)) return false;
            if (!u32_field(cred, "copy_size", "kernel.struct.cred",
                           &out->credential.copy_size, false, c)) {
                return false;
            }
            if (!opt_u32(cred, "uid", "kernel.struct.cred",
                         out->credential.uid_offset, c)) {
                return false;
            }
            if (!opt_u32(cred, "security", "kernel.struct.cred",
                         out->credential.security_offset, c)) {
                return false;
            }

            const gl_json_value *usage = member(cred, "usage");
            if (!null_or_absent(usage)) {
                if (!want_object(usage, "kernel.struct.cred.usage", c)) return false;
                if (!keys_of(usage, kUsageKeys, "kernel.struct.cred.usage", c)) {
                    return false;
                }
                if (!u32_field(usage, "offset", "kernel.struct.cred.usage",
                               &out->credential.usage_offset, false, c)) {
                    return false;
                }
                if (!opt_u8(usage, "size", "kernel.struct.cred.usage",
                            out->credential.usage_size, c)) {
                    return false;
                }
                if (!u32_field(usage, "value", "kernel.struct.cred.usage",
                               &out->credential.usage_value, false, c)) {
                    return false;
                }
            }

            const gl_json_value *caps = member(cred, "caps");
            if (!null_or_absent(caps)) {
                if (!want_object(caps, "kernel.struct.cred.caps", c)) return false;
                if (!keys_of(caps, kCapsKeys, "kernel.struct.cred.caps", c)) {
                    return false;
                }
                if (!u32_field(caps, "offset", "kernel.struct.cred.caps",
                               &out->credential.caps_offset, false, c)) {
                    return false;
                }
                if (!u32_field(caps, "count", "kernel.struct.cred.caps",
                               &out->credential.caps_count, false, c)) {
                    return false;
                }
                unsigned long long mask = 0;
                if (!u64_field(caps, "mask", "kernel.struct.cred.caps", &mask,
                               false, c)) {
                    return false;
                }
                out->credential.caps_value = mask;
            }

            return map_cred_refs(member(cred, "refs"), out, c);
        }

        bool map_waiter(const gl_json_value *s, profile::kernel_offsets *out,
                        ctx &c) {
            const gl_json_value *waiter = member(s, "waiter");

            if (null_or_absent(waiter)) return true;
            if (!want_object(waiter, "kernel.struct.waiter", c)) return false;
            if (!keys_of(waiter, kWaiterKeys, "kernel.struct.waiter", c)) {
                return false;
            }
            const gl_json_value *shape_v = member(waiter, "shape");
            if (!null_or_absent(shape_v)) {
                uint8_t shape = 0;
                if (!word_field(waiter, "shape", "kernel.struct.waiter",
                                kWaiterShapeWords, std::size(kWaiterShapeWords),
                                &shape, true, c)) {
                    return false;
                }
                out->waiter.compact = shape;
                out->misc.compact_waiter = shape;
            }
            if (!opt_u32(waiter, "pi_tree", "kernel.struct.waiter",
                         out->waiter.pi_tree, c)) {
                return false;
            }
            if (!opt_u32(waiter, "task", "kernel.struct.waiter", out->waiter.task,
                         c)) {
                return false;
            }
            if (!opt_u32(waiter, "lock", "kernel.struct.waiter", out->waiter.lock,
                         c)) {
                return false;
            }
            return opt_u32(waiter, "wake_state", "kernel.struct.waiter",
                           out->waiter.wake_state, c);
        }

        bool map_struct(const gl_json_value *kernel, profile::kernel_offsets *out,
                        ctx &c) {
            const gl_json_value *s = member(kernel, "struct");

            if (null_or_absent(s)) return true;
            if (!want_object(s, "kernel.struct", c)) return false;
            if (!keys_of(s, kStructKeys, "kernel.struct", c)) return false;
            if (!map_task(s, out, c)) return false;
            if (!map_cred(s, out, c)) return false;
            if (!map_waiter(s, out, c)) return false;

            const gl_json_value *rt = member(s, "rt_mutex");
            if (!null_or_absent(rt)) {
                if (!want_object(rt, "kernel.struct.rt_mutex", c)) return false;
                if (!keys_of(rt, kRtMutexKeys, "kernel.struct.rt_mutex", c)) {
                    return false;
                }
                if (!opt_u32(rt, "size", "kernel.struct.rt_mutex",
                             out->misc.rt_mutex_size, c)) {
                    return false;
                }
            }

            const gl_json_value *selinux = member(s, "selinux_state");
            if (!null_or_absent(selinux)) {
                if (!want_object(selinux, "kernel.struct.selinux_state", c)) {
                    return false;
                }
                if (!keys_of(selinux, kSelinuxKeys,
                             "kernel.struct.selinux_state", c)) {
                    return false;
                }
                if (!opt_u32(selinux, "enforcing",
                             "kernel.struct.selinux_state", out->selinux.enforcing,
                             c)) {
                    return false;
                }
                if (!opt_u32(selinux, "initialized",
                             "kernel.struct.selinux_state",
                             out->selinux.initialized, c)) {
                    return false;
                }
            }
            return true;
        }

        bool map_heap(const gl_json_value *kernel, profile::kernel_offsets *out,
                      ctx &c) {
            const gl_json_value *heap = member(kernel, "heap");

            if (null_or_absent(heap)) return true;
            if (!want_object(heap, "kernel.heap", c)) return false;
            if (!keys_of(heap, kHeapKeys, "kernel.heap", c)) return false;
            if (!opt_u32(heap, "stride", "kernel.heap", out->misc.mm_struct_sz,
                         c)) {
                return false;
            }
            return opt_u32(heap, "collisions", "kernel.heap",
                           out->misc.kernelsnitch_collisions, c);
        }

        bool map_devices(const gl_json_value *devices, profile::kernel_offsets *out,
                         ctx &c) {
            if (null_or_absent(devices)) return true;
            if (!want_object(devices, "devices", c)) return false;
            /* The app renders the running device's entry, so exactly one. */
            const gl_json_member *first = devices->members;
            if (!first) return true;
            if (first->next) {
                return fail(c,
                            "profile: devices carries more than one entry; the "
                            "render must select the running device");
            }
            const gl_json_value *device = first->value;
            if (null_or_absent(device)) return true;
            if (!want_object(device, "devices.<id>", c)) return false;
            if (!keys_of(device, kDeviceKeys, "devices.<id>", c)) return false;
            const gl_json_value *phys = member(device, "phys");
            if (null_or_absent(phys)) return true;
            if (!want_object(phys, "devices.<id>.phys", c)) return false;
            if (!keys_of(phys, kPhysKeys, "devices.<id>.phys", c)) return false;
            if (!opt_u64(phys, "load", "devices.<id>.phys",
                         out->misc.kernel_phys_load, c)) {
                return false;
            }
            return opt_u64(phys, "offset", "devices.<id>.phys",
                           out->misc.kernel_phys_offset, c);
        }

        bool map_anchor(const gl_json_value *ghostlock,
                        profile::kernel_offsets *out, ctx &c) {
            const gl_json_value *anchor = member(ghostlock, "anchor");

            if (null_or_absent(anchor)) return true;
            if (!want_object(anchor, "ghostlock.anchor", c)) return false;
            if (!keys_of(anchor, kAnchorKeys, "ghostlock.anchor", c)) return false;
            if (!opt_u64(anchor, "image", "ghostlock.anchor",
                         out->geometry.select_lock_anchor_image, c)) {
                return false;
            }
            if (!opt_u64(anchor, "bytes", "ghostlock.anchor",
                         out->geometry.select_lock_anchor_bytes, c)) {
                return false;
            }
            if (!opt_u64(anchor, "stride", "ghostlock.anchor",
                         out->geometry.select_lock_anchor_stride, c)) {
                return false;
            }
            return opt_u64(anchor, "scratch", "ghostlock.anchor",
                           out->w1_scratch.image, c);
        }

        bool map_branch(const gl_json_value *syscalls, uint8_t route,
                        uint8_t carrier, profile::kernel_offsets *out, ctx &c) {
            const char *name = syscall_name(route);
            char path[64];
            snprintf(path, sizeof(path), "ghostlock.syscalls.%s", name);
            const gl_json_value *branch = member(syscalls, name);

            if (null_or_absent(branch)) {
                return fail(c,
                            "profile: ghostlock.syscalls.%s is null but "
                            "ghostlock.syscall selects it",
                            name);
            }
            if (!want_object(branch, path, c)) return false;
            if (!keys_of(branch, kBranchKeys, path, c)) return false;
            const char *carrier_name = carrier == kCarrierCompat ? "compat" : "native";
            const gl_json_value *block = member(branch, carrier_name);
            if (null_or_absent(block)) {
                return fail(c,
                            "profile: %s.%s is null but ghostlock.carrier selects it",
                            path, carrier_name);
            }
            if (!want_object(block, path, c)) return false;

            char block_path[96];
            snprintf(block_path, sizeof(block_path), "%s.%s", path, carrier_name);
            switch (route) {
                case profile::kRouteSelectStack:
                    if (carrier == kCarrierCompat) {
                        uint8_t abi = 0;
                        if (!keys_of(block, kSelectCompatKeys, block_path, c)) {
                            return false;
                        }
                        if (!word_field(block, "futex_abi", block_path,
                                        kFutexAbiWords,
                                        std::size(kFutexAbiWords), &abi, true, c)) {
                            return false;
                        }
                        out->geometry.select_compat_carrier = uint8_t{1};
                        out->geometry.select_compat_futex_abi = abi;
                        if (!opt_u32(block, "waiter_home", block_path,
                                     out->geometry.select_waiter_home, c)) {
                            return false;
                        }
                        return opt_u32(block, "dst_depth", block_path,
                                       out->geometry.select_select_dst_depth, c);
                    }
                    if (!keys_of(block, kSelectNativeKeys, block_path, c)) {
                        return false;
                    }
                    return opt_i32(block, "waiter_shift", block_path,
                                   out->geometry.pselect_waiter_shift, c);
                case profile::kRouteMulticastWaiter:
                    if (carrier == kCarrierCompat) {
                        uint8_t abi = 0;
                        if (!keys_of(block, kMcastCompatKeys, block_path, c)) {
                            return false;
                        }
                        if (!word_field(block, "futex_abi", block_path,
                                        kFutexAbiWords,
                                        std::size(kFutexAbiWords), &abi, true, c)) {
                            return false;
                        }
                        out->geometry.mcast_compat_carrier = uint8_t{1};
                        out->geometry.mcast_compat_futex_abi = abi;
                        if (!opt_i32(block, "waiter_off", block_path,
                                     out->geometry.mcast_compat_waiter_off, c)) {
                            return false;
                        }
                        if (!opt_u32(block, "buffer_size", block_path,
                                     out->geometry.mcast_compat_buffer_size, c)) {
                            return false;
                        }
                    } else {
                        if (!keys_of(block, kMcastNativeKeys, block_path, c)) {
                            return false;
                        }
                        if (!opt_i32(block, "waiter_off", block_path,
                                     out->geometry.mcast_waiter_off, c)) {
                            return false;
                        }
                        if (!opt_u32(block, "buffer_size", block_path,
                                     out->geometry.mcast_buffer_size, c)) {
                            return false;
                        }
                    }
                    /* task/lock are one fact for every route: kernel.struct.waiter */
                    out->geometry.mcast_task_offset = out->waiter.task;
                    out->geometry.mcast_lock_offset = out->waiter.lock;
                    return true;
                case profile::kRouteSigreturn: {
                    if (carrier != kCarrierNative) {
                        return fail(c,
                                    "profile: sigreturn declares no compat carrier");
                    }
                    if (!keys_of(block, kSigNativeKeys, block_path, c)) {
                        return false;
                    }
                    const gl_json_value *off = member(block, "waiter_off");
                    if (null_or_absent(off)) {
                        return fail(c, "profile: %s.waiter_off is required",
                                    block_path);
                    }
                    if (!want_object(off, block_path, c)) return false;
                    char off_path[128];
                    snprintf(off_path, sizeof(off_path), "%s.waiter_off",
                             block_path);
                    if (!keys_of(off, kSigWaiterOffKeys, off_path, c)) {
                        return false;
                    }
                    if (!opt_u64(off, "plain", off_path,
                                 out->geometry.sig_waiter_off_plain, c)) {
                        return false;
                    }
                    return opt_u64(off, "sve", off_path,
                                   out->geometry.sig_waiter_off_sve, c);
                }
                case profile::kRouteTcpZerocopy:
                    if (carrier != kCarrierNative) {
                        return fail(c, "profile: tcp_zerocopy declares no compat carrier");
                    }
                    /* An empty carrier block is the presence marker. */
                    return keys(block, nullptr, 0, block_path, c);
                default:
                    return fail(c, "profile: unknown syscall");
            }
        }

        bool map_execution_block(const gl_json_value *parent, const char *key,
                                 const char *path, const char *const *known,
                                 size_t count, const gl_json_value **out, ctx &c) {
            const gl_json_value *block = member(parent, key);

            if (null_or_absent(block)) {
                return fail(c, "profile: %s is required", path);
            }
            if (!want_object(block, path, c)) return false;
            if (!keys(block, known, count, path, c)) return false;
            *out = block;
            return true;
        }

        bool map_execution(const gl_json_value *ghostlock, uint8_t route,
                           profile::kernel_offsets *out, ctx &c) {
            const gl_json_value *execution = member(ghostlock, "execution");

            if (null_or_absent(execution)) {
                return fail(c,
                            "profile: ghostlock.execution is null; the resolved "
                            "document must carry the tuning");
            }
            if (!want_object(execution, "ghostlock.execution", c)) return false;
            if (!keys_of(execution, kExecKeys, "ghostlock.execution", c)) {
                return false;
            }
            const gl_json_value *block = nullptr;

            if (!map_execution_block(execution, "recommended_cpus",
                                     "ghostlock.execution.recommended_cpus",
                                     kExecCpusKeys,
                                     std::size(kExecCpusKeys), &block, c)) {
                return false;
            }
            if (!u32_field(block, "main", "ghostlock.execution.recommended_cpus",
                           &out->execution.recommended_main_cpu, true, c)) {
                return false;
            }
            if (!u32_field(block, "consumer",
                           "ghostlock.execution.recommended_cpus",
                           &out->execution.recommended_consumer_cpu, true, c)) {
                return false;
            }

            if (!map_execution_block(execution, "heap",
                                     "ghostlock.execution.heap", kExecHeapKeys,
                                     std::size(kExecHeapKeys), &block, c)) {
                return false;
            }
            if (!u32_field(block, "prepare_max_attempts", "ghostlock.execution.heap",
                           &out->execution.heap_prepare_max_attempts, true, c) ||
                !u32_field(block, "prepare_timeout_ms", "ghostlock.execution.heap",
                           &out->execution.heap_prepare_timeout_ms, true, c) ||
                !u32_field(block, "kernelsnitch_timeout_ms",
                           "ghostlock.execution.heap",
                           &out->execution.heap_kernelsnitch_timeout_ms, true, c)) {
                return false;
            }

            if (!map_execution_block(execution, "race",
                                     "ghostlock.execution.race", kExecRaceKeys,
                                     std::size(kExecRaceKeys), &block, c)) {
                return false;
            }
            if (!u32_field(block, "route_wait_ms", "ghostlock.execution.race",
                           &out->execution.race_route_wait_ms, true, c) ||
                !u32_field(block, "route_done_timeout_ms",
                           "ghostlock.execution.race",
                           &out->execution.race_route_done_timeout_ms, true, c) ||
                !u32_field(block, "setup_settle_us", "ghostlock.execution.race",
                           &out->execution.race_setup_settle_us, true, c) ||
                !u32_field(block, "state_poll_interval_us",
                           "ghostlock.execution.race",
                           &out->execution.race_state_poll_interval_us, true, c)) {
                return false;
            }

            if (!map_execution_block(execution, "stages",
                                     "ghostlock.execution.stages",
                                     kExecStagesKeys,
                                     std::size(kExecStagesKeys), &block, c)) {
                return false;
            }
            if (!u32_field(block, "w1_attempts", "ghostlock.execution.stages",
                           &out->execution.w1_attempts, true, c) ||
                !u32_field(block, "w1_settle_us", "ghostlock.execution.stages",
                           &out->execution.w1_settle_us, true, c) ||
                !u32_field(block, "w1_scratch_repair_attempts",
                           "ghostlock.execution.stages",
                           &out->execution.w1_scratch_repair_attempts, true, c) ||
                !u32_field(block, "w2_attempts", "ghostlock.execution.stages",
                           &out->execution.w2_attempts, true, c) ||
                !u32_field(block, "w2_settle_us", "ghostlock.execution.stages",
                           &out->execution.w2_settle_us, true, c) ||
                !u32_field(block, "w3_chain_rounds", "ghostlock.execution.stages",
                           &out->execution.w3_chain_rounds, true, c) ||
                !u32_field(block, "w3_attempts", "ghostlock.execution.stages",
                           &out->execution.w3_attempts, true, c) ||
                !u32_field(block, "w3_settle_us", "ghostlock.execution.stages",
                           &out->execution.w3_settle_us, true, c)) {
                return false;
            }

            if (!map_execution_block(execution, "handoff",
                                     "ghostlock.execution.handoff",
                                     kExecHandoffKeys,
                                     std::size(kExecHandoffKeys), &block, c)) {
                return false;
            }
            if (!u32_field(block, "pre_dispatch_settle_ms",
                           "ghostlock.execution.handoff",
                           &out->execution.handoff_pre_dispatch_settle_ms, true,
                           c) ||
                !u32_field(block, "module_poll_attempts",
                           "ghostlock.execution.handoff",
                           &out->execution.handoff_module_poll_attempts, true,
                           c) ||
                !u32_field(block, "module_poll_interval_ms",
                           "ghostlock.execution.handoff",
                           &out->execution.handoff_module_poll_interval_ms, true,
                           c) ||
                !u32_field(block, "enforce_poll_attempts",
                           "ghostlock.execution.handoff",
                           &out->execution.handoff_enforce_poll_attempts, true,
                           c) ||
                !u32_field(block, "enforce_poll_interval_ms",
                           "ghostlock.execution.handoff",
                           &out->execution.handoff_enforce_poll_interval_ms, true,
                           c)) {
                return false;
            }

            if (!map_execution_block(execution, "consumer",
                                     "ghostlock.execution.consumer",
                                     kExecConsumerKeys,
                                     std::size(kExecConsumerKeys), &block, c)) {
                return false;
            }
            if (!u32_field(block, "max_calls", "ghostlock.execution.consumer",
                           &out->execution.select_consumer_max_calls, true, c) ||
                !u32_field(block, "burst_calls", "ghostlock.execution.consumer",
                           &out->execution.select_consumer_burst_calls, true, c)) {
                return false;
            }

            if (!map_execution_block(execution, "routes",
                                     "ghostlock.execution.routes",
                                     kExecRoutesKeys,
                                     std::size(kExecRoutesKeys), &block, c)) {
                return false;
            }
            const gl_json_value *routes = block;
            if (route == profile::kRouteSelectStack) {
                if (!map_execution_block(routes, "select_stack",
                                         "ghostlock.execution.routes.select_stack",
                                         kExecSelectKeys,
                                         std::size(kExecSelectKeys), &block, c)) {
                    return false;
                }
                if (!u32_field(block, "enter_delay_us",
                               "ghostlock.execution.routes.select_stack",
                               &out->execution.select_enter_delay_us, true, c) ||
                    !u32_field(block, "timeout_us",
                               "ghostlock.execution.routes.select_stack",
                               &out->execution.select_timeout_us, true, c)) {
                    return false;
                }
            } else if (route == profile::kRouteTcpZerocopy) {
                if (!map_execution_block(routes, "tcp_zerocopy",
                                         "ghostlock.execution.routes.tcp_zerocopy",
                                         kExecTcpKeys,
                                         std::size(kExecTcpKeys), &block, c)) {
                    return false;
                }
                if (!u32_field(block, "attempts",
                               "ghostlock.execution.routes.tcp_zerocopy",
                               &out->execution.tcp_attempts, true, c) ||
                    !u32_field(block, "arm_sequence",
                               "ghostlock.execution.routes.tcp_zerocopy",
                               &out->execution.tcp_arm_sequence, true, c) ||
                    !u32_field(block, "post_receive_hold_iterations",
                               "ghostlock.execution.routes.tcp_zerocopy",
                               &out->execution.tcp_post_receive_hold_iterations,
                               true, c)) {
                    return false;
                }
            }
            return true;
        }

        bool map_mode(const gl_json_value *root, profile::kernel_offsets *out,
                      ctx &c) {
            const gl_json_value *mode = member(root, "mode");

            if (null_or_absent(mode)) return true;
            if (mode->type != GL_JSON_STRING) {
                return fail(c, "profile: mode is not a word");
            }
            if (strcmp(mode->string, "ghostlock") == 0) {
                out->meta.pipeline = profile::kPipelineGhostlock;
                return true;
            }
            if (strcmp(mode->string, "dirtyfrag") == 0) {
                out->meta.pipeline = profile::kPipelineDirtyfrag;
                return true;
            }
            if (strcmp(mode->string, "module") == 0) {
                /* The module chain never invokes this binary; a document that
                 * states it is not a native run. */
                return true;
            }
            return fail(c, "profile: mode has an unknown value '%s'", mode->string);
        }

        bool derive_kernel_major(const char *release, uint8_t *out, ctx &c) {
            unsigned value = 0;
            const char *p = release;

            if (*p < '0' || *p > '9') {
                return fail(c,
                            "profile: release '%s' does not start with a major "
                            "version",
                            release);
            }
            while (*p >= '0' && *p <= '9') {
                value = value * 10 + static_cast<unsigned>(*p - '0');
                if (value > 0xff) {
                    return fail(c, "profile: release major version is out of range");
                }
                p++;
            }
            *out = static_cast<uint8_t>(value);
            return true;
        }
    } // namespace

    int32_t parse(std::string_view document, profile::kernel_offsets *out,
                  char *release_buf, size_t release_buf_cap, component_ids *ids,
                  char *err, size_t errlen) {
        ctx c{err, errlen, false};
        struct gl_json_document doc;

        if (err && errlen) err[0] = 0;
        if (!out || !release_buf || release_buf_cap == 0) {
            fail(c, "profile: no destination");
            return -1;
        }
        *out = profile::kernel_offsets{};
        if (gl_json_parse(document.data(), document.size(), &doc, err, errlen) !=
            0) {
            return -1;
        }
        const gl_json_value *root = &doc.root;
        bool ok = false;
        do {
            if (root->type != GL_JSON_OBJECT) {
                fail(c, "profile: the document must be an object");
                break;
            }
            if (!keys_of(root, kTopKeys, "", c)) break;

            const gl_json_value *schema = member(root, "schema_version");
            unsigned long long schema_version = 0;
            if (null_or_absent(schema)) {
                fail(c, "profile: schema_version is required");
                break;
            }
            if (gl_json_u64(schema, &schema_version) != 0 ||
                schema_version != 3) {
                fail(c, "profile: schema_version must be 3 (this build reads the "
                        "v3 layout only)");
                break;
            }

            const gl_json_value *release = member(root, "release");
            if (null_or_absent(release) || release->type != GL_JSON_STRING) {
                fail(c, "profile: release is missing or not a string");
                break;
            }
            const size_t release_len = strlen(release->string);
            if (release_len == 0 || release_len >= release_buf_cap) {
                fail(c, "profile: release is empty or too long");
                break;
            }
            memcpy(release_buf, release->string, release_len + 1);
            out->uname_r = release_buf;

            if (!map_mode(root, out, c)) break;

            const gl_json_value *safe_mode = member(root, "safe_mode");
            if (!null_or_absent(safe_mode)) {
                int value = 0;
                if (gl_json_bool(safe_mode, &value) != 0) {
                    fail(c, "profile: safe_mode is not a boolean");
                    break;
                }
                out->meta.safe_mode = value != 0 ? uint8_t{1} : uint8_t{0};
            }

            if (!map_devices(member(root, "devices"), out, c)) break;

            const gl_json_value *kernel = member(root, "kernel");
            if (null_or_absent(kernel)) {
                fail(c, "profile: kernel is required");
                break;
            }
            if (!want_object(kernel, "kernel", c)) break;
            if (!keys_of(kernel, kKernelKeys, "kernel", c)) break;
            if (!map_symbols(kernel, out, c)) break;
            if (!map_struct(kernel, out, c)) break;
            if (!map_heap(kernel, out, c)) break;

            const gl_json_value *ghostlock = member(root, "ghostlock");
            if (null_or_absent(ghostlock)) {
                fail(c, "profile: ghostlock is null; no native route to run");
                break;
            }
            if (!want_object(ghostlock, "ghostlock", c)) break;
            if (!keys_of(ghostlock, kGhostlockKeys, "ghostlock", c)) break;

            uint8_t route = profile::kRouteAuto;
            if (!word_field(ghostlock, "syscall", "ghostlock", kSyscallWords,
                            std::size(kSyscallWords), &route, true, c)) {
                break;
            }
            if (route == profile::kRouteAuto) {
                fail(c, "profile: ghostlock.syscall is null; no native route "
                        "selected");
                break;
            }
            out->route = route;

            uint8_t carrier = kCarrierNative;
            {
                const gl_json_value *cv = member(ghostlock, "carrier");
                if (!null_or_absent(cv)) {
                    if (!word_field(ghostlock, "carrier", "ghostlock",
                                    kCarrierWords, std::size(kCarrierWords),
                                    &carrier, true, c)) {
                        break;
                    }
                }
            }

            if (!map_anchor(ghostlock, out, c)) break;

            const gl_json_value *syscalls = member(ghostlock, "syscalls");
            if (null_or_absent(syscalls)) {
                fail(c, "profile: ghostlock.syscalls is null; no branch to run");
                break;
            }
            if (!want_object(syscalls, "ghostlock.syscalls", c)) break;
            if (!keys_of(syscalls, kSyscallsKeys, "ghostlock.syscalls", c)) break;
            if (!map_branch(syscalls, route, carrier, out, c)) break;

            const gl_json_value *module = member(ghostlock, "module");
            if (!null_or_absent(module)) {
                if (!want_object(module, "ghostlock.module", c)) break;
                if (!keys_of(module, kModuleKeys, "ghostlock.module", c)) break;
            }

            if (!map_execution(ghostlock, route, out, c)) break;
            if (!derive_kernel_major(release_buf, &out->meta.kernel_major, c)) {
                break;
            }
            ok = true;
        } while (false);

        gl_json_free(&doc);
        if (!ok) return -1;
        if (ids) {
            *ids = component_ids{
                /* frontend/backend: this build's shipped pair */
                uint16_t{1}, uint16_t{1}, static_cast<uint16_t>(out->route),
            };
        }
        return 0;
    }
} // namespace ghostlock::json_profile
