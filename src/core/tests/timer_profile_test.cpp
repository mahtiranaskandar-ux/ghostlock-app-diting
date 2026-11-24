/* Host test for the 98260 timer tool's document mapper. The document carries
 * the facts of the real 6.1.138 asset, so the computed VAs are the ones the
 * device profile states. */
#include "profile_fields.h"

#include <cstdio>
#include <cstring>

#include <string>

namespace {
    const char *kDocument = R"JSON({
  "schema_version": 3,
  "release": "6.1.138-android14-11-g44bda9e8f6e9-ab13792638",
  "kernel": {
    "image_base": "0xffffffc008000000",
    "symbols": {
      "init_task": null,
      "init_cred": null,
      "root_task_group": null,
      "empty_zero_page": null,
      "selinux_state": "0x22293d0",
      "selinux_blob_sizes": null,
      "security_hook_heads": null,
      "nf_logger": null,
      "loggers_0_1": null,
      "boot_id": null,
      "panic_on_warn": "0x1fdae08",
      "root_user": "0x2000740",
      "init_user_ns": "0x20004b0",
      "init_ucounts": "0x2001fc8",
      "init_groups": "0x2001a60"
    },
    "struct": {
      "task": {
        "prio": null, "normal_prio": null, "sched_task_group": null,
        "pi_lock": null, "pi_waiters": null, "pi_top_task": null,
        "pi_blocked_on": null, "pid": null, "tgid": null,
        "atomic_flags": null, "real_cred": null, "cred": 2104,
        "comm": null, "tasks": null, "seccomp": null
      },
      "cred": {
        "copy_size": 128,
        "usage": { "offset": null, "size": 4, "value": 1 },
        "uid": 4, "security": 120, "security_sid": 4,
        "caps": { "offset": 40, "count": 5, "mask": "0xffffffffffffffff" },
        "refs": {
          "user": { "offset": 128, "image": null },
          "user_ns": { "offset": 136, "image": null },
          "ucounts": { "offset": 144, "image": null },
          "group_info": { "offset": 152, "image": null }
        }
      },
      "waiter": null,
      "rt_mutex": null,
      "selinux_state": null
    },
    "heap": null,
    "dirtyfrag": null,
    "kdp": false
  },
  "ghostlock": null,
  "timer": {
    "trigger": "killable",
    "match_bits": 21,
    "sites": {
      "timer": { "offset": "0x191af8" },
      "task_alloc": { "low_bits": "0xab680" }
    },
    "object_size": 264,
    "task_alloc_size": 4800,
    "node_offset": 120,
    "cred": { "request_size": 176, "alloc_size": 192 },
    "trace": { "gfp": { "offset": 24, "size": 8 } }
  }
}
)JSON";

    bool replace_all(std::string *text, const std::string &from,
                     const std::string &to) {
        const size_t at = text->find(from);

        if (at == std::string::npos) return false;
        text->replace(at, from.size(), to);
        return true;
    }

    int parse(const std::string &document, struct timer_profile *out) {
        char err[256] = {};

        return timer_profile_parse(document.data(), document.size(), out, err,
                                   sizeof(err));
    }
} // namespace

int32_t main(void) {
    struct timer_profile profile;
    std::string document(kDocument);

    if (parse(document, &profile) != 0) {
        std::fputs("the base timer document did not decode\n", stderr);
        return 1;
    }
    if (std::strcmp(profile.release,
                    "6.1.138-android14-11-g44bda9e8f6e9-ab13792638") != 0 ||
        profile.kb.release != profile.release) {
        std::fputs("release did not carry over\n", stderr);
        return 1;
    }
    if (profile.trigger != 1 || !profile.has_fake || !profile.has_cred ||
        !profile.has_trace) {
        std::fputs("presence flags wrong\n", stderr);
        return 1;
    }
    if (profile.kb.static_timer_site != 0xffffffc008191af8ULL ||
        profile.kb.timer_site_low21 != 0x191af8ULL ||
        profile.kb.copy_process_low21 != 0xab680ULL) {
        std::fputs("timer sites wrong\n", stderr);
        return 1;
    }
    if (profile.kb.selinux_state != 0xffffffc00a2293d0ULL ||
        profile.kb.panic_on_warn != 0xffffffc009fdae08ULL ||
        profile.kb.root_user != 0xffffffc00a000740ULL ||
        profile.kb.init_user_ns != 0xffffffc00a0004b0ULL ||
        profile.kb.init_ucounts != 0xffffffc00a001fc8ULL ||
        profile.kb.init_groups != 0xffffffc00a001a60ULL) {
        std::fputs("symbol VAs wrong\n", stderr);
        return 1;
    }
    if (profile.kb.off_task_cred != 2104 || profile.kb.cred_req != 176 ||
        profile.kb.cred_alloc != 192 || profile.kb.timer_alloc != 264 ||
        profile.kb.task_alloc_bytes != 4800 || profile.kb.cred_uid_off != 4) {
        std::fputs("object sizes/offsets wrong\n", stderr);
        return 1;
    }
    if (profile.cred.usage_size != 4 || profile.cred.caps_offset != 40 ||
        profile.cred.caps_count != 5 || profile.cred.security_offset != 120 ||
        profile.cred.user_offset != 128 ||
        profile.cred.user_ns_offset != 136 ||
        profile.cred.ucounts_offset != 144 ||
        profile.cred.group_info_offset != 152 || profile.cred.sid_offset != 4) {
        std::fputs("cred overlay wrong\n", stderr);
        return 1;
    }
    if (profile.trace.page_gfp_offset != 24 || profile.trace.page_gfp_size != 8 ||
        profile.timer_node_off != 120) {
        std::fputs("trace/node layout wrong\n", stderr);
        return 1;
    }

    /* The real-only shape: no image_base and no symbols, so no fake group,
     * but the real path's facts stay. */
    {
        std::string simple(kDocument);
        const size_t begin = simple.find("\"symbols\"");
        const size_t end = simple.find("\"struct\"");
        if (begin == std::string::npos || end == std::string::npos) {
            std::fputs("symbols not found\n", stderr);
            return 1;
        }
        simple.replace(begin, end - begin, "\"symbols\": null, ");
        if (!replace_all(&simple, "\"image_base\": \"0xffffffc008000000\"",
                         "\"image_base\": null")) {
            std::fputs("image_base mutation did not apply\n", stderr);
            return 1;
        }
        struct timer_profile other;
        if (parse(simple, &other) != 0) {
            std::fputs("the real-only document was refused\n", stderr);
            return 1;
        }
        if (other.has_fake || other.kb.static_timer_site != 0 ||
            other.kb.selinux_state != 0 || !other.has_cred) {
            std::fputs("real-only decode wrong\n", stderr);
            return 1;
        }
    }

    /* Refusals: a null timer block, a wrong match width, an unknown key. */
    {
        std::string no_timer(kDocument);
        std::string bits(kDocument);
        std::string unknown(kDocument);

        /* The timer block is the document's last member: replace its whole
         * object with null, leaving the root's braces intact. */
        const size_t key = no_timer.find("\"timer\": {");
        const size_t root_close = no_timer.rfind('}');
        const size_t timer_close = root_close == std::string::npos
                                       ? std::string::npos
                                       : no_timer.rfind('}', root_close - 1);
        if (key == std::string::npos || timer_close == std::string::npos ||
            timer_close < key) {
            std::fputs("the timer block was not found\n", stderr);
            return 1;
        }
        no_timer.replace(key, timer_close - key + 1, "\"timer\": null");
        if (!replace_all(&bits, "\"match_bits\": 21", "\"match_bits\": 20") ||
            !replace_all(&unknown, "\"object_size\": 264",
                         "\"object_size\": 264, \"bogus\": 1")) {
            std::fputs("mutation did not apply\n", stderr);
            return 1;
        }
        struct timer_profile other;
        char err[256] = {};

        if (timer_profile_parse(no_timer.data(), no_timer.size(), &other, err,
                                sizeof(err)) == 0 ||
            err[0] == 0) {
            std::fputs("a null timer block was accepted\n", stderr);
            return 1;
        }
        err[0] = 0;
        if (timer_profile_parse(bits.data(), bits.size(), &other, err,
                                sizeof(err)) == 0 ||
            err[0] == 0) {
            std::fputs("a 20-bit match width was accepted\n", stderr);
            return 1;
        }
        err[0] = 0;
        if (timer_profile_parse(unknown.data(), unknown.size(), &other, err,
                                sizeof(err)) == 0 ||
            err[0] == 0) {
            std::fputs("an unknown timer key was accepted\n", stderr);
            return 1;
        }
    }

    std::puts("timer profile test passed");
    return 0;
}
