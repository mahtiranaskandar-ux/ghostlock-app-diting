/* Host test for the shared JSON tree and the ghostlock document mapper. The
 * document below is the v3 shape the app renders; the refusals are the ones
 * the parser and the mapper must produce before any attack code runs. */
#include "profile/json_profile.h"

#include "profile/pipeline_check.h"

#include <cstdio>
#include <cstring>

#include <string>

namespace {
    const char *kDocument = R"JSON({
  "schema_version": 3,
  "release": "6.1.138-android14-11-g44bda9e8f6e9-ab13792638",
  "mode": "ghostlock",
  "safe_mode": true,
  "devices": {
    "duchamp": { "phys": { "load": "0x40000000", "offset": "0x40000000" } }
  },
  "kernel": {
    "image_base": "0xffffffc008000000",
    "symbols": {
      "init_task": "0x1ff0000",
      "init_cred": "0x2002000",
      "root_task_group": "0x21d5000",
      "empty_zero_page": "0x21cf000",
      "selinux_state": "0x222a000",
      "selinux_blob_sizes": "0x15b5000",
      "security_hook_heads": "0x15b3000",
      "nf_logger": "0x1fe2000",
      "loggers_0_1": "0x1fe1000",
      "boot_id": "0x22cc000",
      "panic_on_warn": "0x1fd0000",
      "root_user": "0x2001000",
      "init_user_ns": "0x2000000",
      "init_ucounts": "0x2003000",
      "init_groups": "0x2002000"
    },
    "struct": {
      "task": {
        "prio": 132, "normal_prio": 140, "sched_task_group": 840,
        "pi_lock": 2340, "pi_waiters": 2360, "pi_top_task": 2376,
        "pi_blocked_on": 2384, "pid": 1584, "tgid": 1588,
        "atomic_flags": 1520, "real_cred": 2096, "cred": 2104,
        "comm": 2120, "tasks": 1360, "seccomp": 2304
      },
      "cred": {
        "copy_size": 128,
        "usage": { "offset": null, "size": 4, "value": 1 },
        "uid": 4, "security": 120, "security_sid": 4,
        "caps": { "offset": 40, "count": 5, "mask": "0xffffffffffffffff" },
        "refs": {
          "user": { "offset": 128, "image": "0xffffffc0a0000000" },
          "user_ns": { "offset": 136, "image": null },
          "ucounts": { "offset": 144, "image": null },
          "group_info": { "offset": 152, "image": "0xffffffc0a0000100" }
        }
      },
      "waiter": { "shape": "compact", "pi_tree": 24, "task": 48, "lock": 56, "wake_state": 64 },
      "rt_mutex": { "size": 32 },
      "selinux_state": { "enforcing": 0, "initialized": 2 }
    },
    "heap": { "stride": 1024, "collisions": 4 },
    "dirtyfrag": "infeasible",
    "kdp": false
  },
  "ghostlock": {
    "syscall": "select_stack",
    "carrier": null,
    "anchor": { "image": "0x22260e8", "bytes": 4096, "stride": 32, "scratch": "0x2226100" },
    "syscalls": {
      "select_stack": {
        "native": { "waiter_shift": 1 },
        "compat": { "waiter_home": 488, "dst_depth": 496, "futex_abi": "time64" }
      },
      "sigreturn": {
        "native": { "waiter_off": { "plain": 152, "sve": 168 } },
        "compat": null
      },
      "multicast_waiter": {
        "native": { "waiter_off": 96, "buffer_size": 264 },
        "compat": { "waiter_off": 72, "buffer_size": 260, "futex_abi": "time64" }
      },
      "tcp_zerocopy": { "native": {}, "compat": null }
    },
    "module": null,
    "execution": {
      "recommended_cpus": { "main": 4, "consumer": 5 },
      "heap": {
        "prepare_max_attempts": 4, "prepare_timeout_ms": 240000,
        "kernelsnitch_timeout_ms": 60000
      },
      "race": {
        "route_wait_ms": 1000, "route_done_timeout_ms": 300000,
        "setup_settle_us": 50000, "state_poll_interval_us": 1000
      },
      "stages": {
        "w1_attempts": 15, "w1_settle_us": 100000,
        "w1_scratch_repair_attempts": 3, "w2_attempts": 15,
        "w2_settle_us": 100000, "w3_chain_rounds": 3, "w3_attempts": 6,
        "w3_settle_us": 50000
      },
      "handoff": {
        "pre_dispatch_settle_ms": 2000, "module_poll_attempts": 30,
        "module_poll_interval_ms": 100, "enforce_poll_attempts": 200,
        "enforce_poll_interval_ms": 100
      },
      "consumer": { "max_calls": 128, "burst_calls": 8 },
      "routes": {
        "select_stack": { "enter_delay_us": 5000, "timeout_us": 30000 },
        "tcp_zerocopy": {
          "attempts": 3, "arm_sequence": 1, "post_receive_hold_iterations": 100
        }
      }
    }
  },
  "timer": null
}
)JSON";

    bool replace_all(std::string *text, const std::string &from,
                     const std::string &to) {
        const size_t at = text->find(from);

        if (at == std::string::npos) return false;
        text->replace(at, from.size(), to);
        return true;
    }

    int parse(const std::string &document, ghostlock::profile::kernel_offsets *out,
              char *release, size_t release_cap,
              ghostlock::json_profile::component_ids *ids = nullptr) {
        char err[256] = {};

        return ghostlock::json_profile::parse(document, out, release, release_cap,
                                             ids, err, sizeof(err));
    }

    bool expect_refusal(const std::string &document, const char *label) {
        ghostlock::profile::kernel_offsets out = {};
        char release[128] = {};
        char err[256] = {};
        const int rc = ghostlock::json_profile::parse(
            document, &out, release, sizeof(release), nullptr, err, sizeof(err));

        if (rc == 0 || err[0] == 0) {
            std::fprintf(stderr, "refusal '%s' was not refused with a message\n",
                         label);
            return false;
        }
        return true;
    }
} // namespace

int32_t main(void) {
    using namespace ghostlock;
    profile::kernel_offsets decoded = {};
    char release[128] = {};
    json_profile::component_ids ids = {};
    std::string document(kDocument);

    if (parse(document, &decoded, release, sizeof(release), &ids) != 0) {
        std::fprintf(stderr, "the base document did not decode\n");
        return 1;
    }
    if (std::strcmp(release, "6.1.138-android14-11-g44bda9e8f6e9-ab13792638") != 0 ||
        decoded.uname_r != release) {
        std::fprintf(stderr, "release did not carry over\n");
        return 1;
    }
    if (decoded.route != profile::kRouteSelectStack ||
        decoded.meta.kernel_major != 6 ||
        decoded.meta.safe_mode != 1 ||
        decoded.meta.pipeline != profile::kPipelineGhostlock ||
        ids.middleware != profile::kRouteSelectStack) {
        std::fprintf(stderr, "route/mode/major decode wrong\n");
        return 1;
    }
    if (decoded.task.seccomp != 2304 || decoded.task.cred != 2104 ||
        decoded.credential.copy_size != 128 ||
        decoded.credential.caps_offset != 40 ||
        decoded.credential.caps_count != 5 ||
        decoded.credential.caps_value != 0xffffffffffffffffULL ||
        decoded.credential.uid_offset != 4 ||
        decoded.credential.security_offset != 120 ||
        decoded.credential.usage_size != 4 ||
        decoded.credential.usage_value != 1 ||
        /* The image-bearing references are compacted into slots 0..n-1;
         * the named-but-unwritten ones keep no slot. */
        decoded.credential.ref0_offset != 128 ||
        decoded.credential.ref0_image != 0xffffffc0a0000000ULL ||
        decoded.credential.ref1_offset != 152 ||
        decoded.credential.ref1_image != 0xffffffc0a0000100ULL ||
        decoded.credential.ref2_offset != 0 ||
        decoded.credential.ref3_offset != 0 ||
        decoded.credential.ref_count != 2) {
        std::fprintf(stderr, "cred/task decode wrong\n");
        return 1;
    }
    if (!decoded.misc.compact_waiter || *decoded.misc.compact_waiter != 1 ||
        !decoded.waiter.compact || decoded.waiter.lock != 56) {
        std::fprintf(stderr, "waiter decode wrong\n");
        return 1;
    }
    if (decoded.offsets.init_task != 0x1ff0000 ||
        decoded.offsets.selinux_enforcing != 0x222a000 ||
        decoded.offsets.slide_nfulnl_logger != 0x1fe2000 ||
        decoded.offsets.slide_loggers_0_1 != 0x1fe1000 ||
        decoded.offsets.slide_boot_id != 0x22cc000) {
        std::fprintf(stderr, "symbol decode wrong\n");
        return 1;
    }
    if (!decoded.misc.mm_struct_sz || *decoded.misc.mm_struct_sz != 1024 ||
        !decoded.misc.kernelsnitch_collisions ||
        *decoded.misc.kernelsnitch_collisions != 4 ||
        !decoded.misc.rt_mutex_size || *decoded.misc.rt_mutex_size != 32 ||
        !decoded.selinux.enforcing || *decoded.selinux.enforcing != 0 ||
        !decoded.selinux.initialized || *decoded.selinux.initialized != 2) {
        std::fprintf(stderr, "heap/rt_mutex/selinux decode wrong\n");
        return 1;
    }
    if (!decoded.misc.kernel_phys_load ||
        *decoded.misc.kernel_phys_load != 0x40000000ULL ||
        !decoded.misc.kernel_phys_offset ||
        *decoded.misc.kernel_phys_offset != 0x40000000ULL) {
        std::fprintf(stderr, "device phys decode wrong\n");
        return 1;
    }
    if (!decoded.geometry.select_lock_anchor_image ||
        *decoded.geometry.select_lock_anchor_image != 0x22260e8ULL ||
        !decoded.geometry.select_lock_anchor_bytes ||
        *decoded.geometry.select_lock_anchor_bytes != 4096 ||
        !decoded.w1_scratch.image ||
        *decoded.w1_scratch.image != 0x2226100ULL) {
        std::fprintf(stderr, "anchor/scratch decode wrong\n");
        return 1;
    }
    if (!decoded.geometry.pselect_waiter_shift ||
        *decoded.geometry.pselect_waiter_shift != 1 ||
        !decoded.execution.select_enter_delay_us ||
        decoded.execution.select_enter_delay_us != 5000 ||
        decoded.execution.select_timeout_us != 30000 ||
        decoded.execution.recommended_main_cpu != 4 ||
        decoded.execution.recommended_consumer_cpu != 5 ||
        decoded.execution.w2_attempts != 15 ||
        decoded.execution.handoff_enforce_poll_interval_ms != 100 ||
        decoded.execution.select_consumer_max_calls != 128) {
        std::fprintf(stderr, "geometry/execution decode wrong\n");
        return 1;
    }

    /* The ladder's other chain. */
    {
        std::string dirty(kDocument);
        if (!replace_all(&dirty, "\"mode\": \"ghostlock\"",
                         "\"mode\": \"dirtyfrag\"")) {
            return 1;
        }
        profile::kernel_offsets other = {};
        char other_release[128] = {};
        if (parse(dirty, &other, other_release, sizeof(other_release)) != 0 ||
            other.meta.pipeline != profile::kPipelineDirtyfrag) {
            std::fprintf(stderr, "mode dirtyfrag did not map\n");
            return 1;
        }
    }

    /* The multicast compat carrier: its own geometry, and the waiter's
     * task/lock offsets deduplicated into the route. */
    {
        std::string multicast(kDocument);
        if (!replace_all(&multicast, "\"syscall\": \"select_stack\"",
                         "\"syscall\": \"multicast_waiter\"") ||
            !replace_all(&multicast, "\"carrier\": null",
                         "\"carrier\": \"compat\"")) {
            return 1;
        }
        profile::kernel_offsets other = {};
        char other_release[128] = {};
        if (parse(multicast, &other, other_release, sizeof(other_release)) != 0) {
            std::fprintf(stderr, "multicast compat did not decode\n");
            return 1;
        }
        if (other.route != profile::kRouteMulticastWaiter ||
            !other.geometry.mcast_compat_carrier ||
            *other.geometry.mcast_compat_carrier != 1 ||
            !other.geometry.mcast_compat_waiter_off ||
            *other.geometry.mcast_compat_waiter_off != 72 ||
            !other.geometry.mcast_compat_buffer_size ||
            *other.geometry.mcast_compat_buffer_size != 260 ||
            !other.geometry.mcast_compat_futex_abi ||
            *other.geometry.mcast_compat_futex_abi != 1 ||
            !other.geometry.mcast_task_offset ||
            *other.geometry.mcast_task_offset != 48 ||
            !other.geometry.mcast_lock_offset ||
            *other.geometry.mcast_lock_offset != 56 ||
            other.geometry.mcast_compat_waiter_off ==
                other.geometry.mcast_waiter_off) {
            std::fprintf(stderr, "multicast compat decode wrong\n");
            return 1;
        }
    }

    /* Sigreturn. */
    {
        std::string sig(kDocument);
        if (!replace_all(&sig, "\"syscall\": \"select_stack\"",
                         "\"syscall\": \"sigreturn\"")) {
            return 1;
        }
        profile::kernel_offsets other = {};
        char other_release[128] = {};
        if (parse(sig, &other, other_release, sizeof(other_release)) != 0 ||
            other.route != profile::kRouteSigreturn ||
            !other.geometry.sig_waiter_off_plain ||
            *other.geometry.sig_waiter_off_plain != 152 ||
            !other.geometry.sig_waiter_off_sve ||
            *other.geometry.sig_waiter_off_sve != 168) {
            std::fprintf(stderr, "sigreturn decode wrong\n");
            return 1;
        }
    }

    /* TCP's empty native carrier is a presence marker, not an error. */
    {
        std::string tcp(kDocument);
        if (!replace_all(&tcp, "\"syscall\": \"select_stack\"",
                         "\"syscall\": \"tcp_zerocopy\"")) {
            return 1;
        }
        profile::kernel_offsets other = {};
        char other_release[128] = {};
        if (parse(tcp, &other, other_release, sizeof(other_release)) != 0 ||
            other.route != profile::kRouteTcpZerocopy ||
            other.execution.tcp_attempts != 3 ||
            other.execution.tcp_post_receive_hold_iterations != 100) {
            std::fprintf(stderr, "tcp decode wrong\n");
            return 1;
        }
    }

    /* Refusals: the old wire bytes, malformed text, a duplicate key, an
     * unknown key, the wrong schema, a null syscall, a compat sigreturn, a
     * release with no major, an unknown mode, and two device entries. */
    {
        std::string old_wire("\x21\x07\x00\x0d\x02\x00", 6);
        std::string truncated(kDocument, 200);
        std::string duplicate(kDocument);
        std::string unknown(kDocument);
        std::string schema(kDocument);
        std::string no_syscall(kDocument);
        std::string sig_compat(kDocument);
        std::string bad_release(kDocument);
        std::string bad_mode(kDocument);
        std::string two_devices(kDocument);

        if (!replace_all(&duplicate, "\"release\": \"",
                         "\"release\": \"dup\", \"release\": \"") ||
            !replace_all(&unknown, "\"heap\": {", "\"bogus\": 1, \"heap\": {") ||
            !replace_all(&schema, "\"schema_version\": 3",
                         "\"schema_version\": 2") ||
            !replace_all(&no_syscall, "\"syscall\": \"select_stack\"",
                         "\"syscall\": null") ||
            !replace_all(&sig_compat, "\"syscall\": \"select_stack\"",
                         "\"syscall\": \"sigreturn\"") ||
            !replace_all(&sig_compat, "\"carrier\": null",
                         "\"carrier\": \"compat\"") ||
            !replace_all(&bad_release,
                         "\"release\": \"6.1.138-android14-11-g44bda9e8f6e9-ab13792638\"",
                         "\"release\": \"android14\"") ||
            !replace_all(&bad_mode, "\"mode\": \"ghostlock\"",
                         "\"mode\": \"surprise\"") ||
            !replace_all(&two_devices, "\"duchamp\": { \"phys\"",
                         "\"other\": { \"phys\": { \"load\": null, \"offset\": null } }, \"duchamp\": { \"phys\"")) {
            std::fprintf(stderr, "a mutation did not apply\n");
            return 1;
        }

        if (!expect_refusal(old_wire, "old wire") ||
            !expect_refusal(truncated, "truncated") ||
            !expect_refusal(duplicate, "duplicate key") ||
            !expect_refusal(unknown, "unknown key") ||
            !expect_refusal(schema, "schema 2") ||
            !expect_refusal(no_syscall, "null syscall") ||
            !expect_refusal(sig_compat, "sigreturn compat") ||
            !expect_refusal(bad_release, "release without major") ||
            !expect_refusal(bad_mode, "unknown mode") ||
            !expect_refusal(two_devices, "two devices")) {
            return 1;
        }
    }

    std::puts("json profile test passed");
    return 0;
}
