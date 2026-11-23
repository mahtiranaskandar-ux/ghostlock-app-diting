/* SPDX-License-Identifier: MIT
 *
 * Canonical JSON profile document reader for the timer tool.
 *
 * The app renders the merged, validated profile as a canonical JSON document
 * (a HOCON subset: quoted keys/strings, numbers, objects/arrays, no includes,
 * comments or substitutions).  The timer tool reads only `release` and the
 * `timer_98260` block; the `kernel` and `native` blocks belong to the other
 * backends and are ignored.
 *
 * The parser is deliberately small and strict: bounded size and depth, no
 * duplicate keys, integers only, unknown keys inside the block refused, and
 * 64-bit values accepted either as numbers or as "0x..." strings (the app's
 * renderer uses the string form because HOCON/JSON numbers cannot carry a
 * kernel VA above 2^63).
 */
#ifndef TIMER_PROFILE_JSON_H
#define TIMER_PROFILE_JSON_H

#include <stddef.h>

#include "kbuilds.h"

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

#endif /* TIMER_PROFILE_JSON_H */
