/* SPDX-License-Identifier: MIT
 *
 * The 98260 timer tool's document mapper: the app-rendered canonical JSON to
 * the tool's `timer_profile`. The tree parser is the shared
 * `core/profile/json.{c,h}`; this unit owns only the timer engine's keys
 * (`timer`, plus the `kernel` facts the fake-cred overlay shares with the
 * ghostlock engine).
 */
#ifndef TIMER_PROFILE_FIELDS_H
#define TIMER_PROFILE_FIELDS_H

#include <stddef.h>

#include "kbuilds.h"

#ifdef __cplusplus
extern "C" {
#endif

/* The fake-cred overlay, in bytes from the start of the cred object. */
struct cred_layout
{
    unsigned usage_size;
    unsigned caps_offset;
    unsigned caps_count;
    unsigned security_offset;
    unsigned user_offset;
    unsigned user_ns_offset; /* 0 = absent */
    unsigned ucounts_offset; /* 0 = absent */
    unsigned group_info_offset;
    unsigned sid_offset; /* inside the SELinux blob; 0 = absent */
};

/* Where the mm_page_alloc raw record carries gfp_flags: 0x14/4 on 5.10,
 * 0x18/8 on 6.x.  The tool's frag/slab discriminator reads this field. */
struct trace_layout
{
    unsigned page_gfp_offset;
    unsigned page_gfp_size;
};

struct timer_profile
{
    char release[128];
    int version;
    int has_fake; /* the block carries the fake group (> 0) */
    struct kbuild kb;
    struct cred_layout cred;
    int has_cred;
    struct trace_layout trace;
    int has_trace;
    unsigned timer_node_off; /* 0 = absent */
    unsigned trigger;        /* 1 = killable, 2 = core_state; 0 = missing */
};

#define TIMER_PROFILE_MAX_BYTES (1u << 20)

/* Parse a canonical JSON profile document.  Returns 0 on success; -1 on error
 * with a message in err. */
int timer_profile_parse(const char *text, size_t len, struct timer_profile *out,
                        char *err, size_t errlen);

/* Read a document from a path ("-" = stdin) and parse it. */
int timer_profile_load(const char *path, struct timer_profile *out, char *err,
                       size_t errlen);

#ifdef __cplusplus
}
#endif

#endif /* TIMER_PROFILE_FIELDS_H */
