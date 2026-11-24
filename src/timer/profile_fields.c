/* SPDX-License-Identifier: MIT
 *
 * The 98260 timer tool's document mapper. See profile_fields.h.
 */
#include "profile_fields.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "profile/json.h"

#define TIMER_MATCH_BITS 21u
#define TIMER_MATCH_MASK ((1ULL << TIMER_MATCH_BITS) - 1)

static int fail(char *err, size_t errlen, const char *fmt, ...)
{
    va_list ap;

    if (err && errlen && !err[0])
    {
        va_start(ap, fmt);
        vsnprintf(err, errlen, fmt, ap);
        va_end(ap);
    }
    return -1;
}

static const struct gl_json_value *get(const struct gl_json_value *obj,
                                       const char *key)
{
    return gl_json_get(obj, key);
}

static int is_null(const struct gl_json_value *v)
{
    return !v || v->type == GL_JSON_NULL;
}

static int req_obj(const struct gl_json_value *parent, const char *key,
                   const char *path, const struct gl_json_value **out,
                   char *err, size_t errlen)
{
    const struct gl_json_value *v = get(parent, key);

    if (is_null(v))
        return fail(err, errlen, "profile: %s is required", path);
    if (v->type != GL_JSON_OBJECT)
        return fail(err, errlen, "profile: %s is not an object", path);
    *out = v;
    return 0;
}

static int req_u64(const struct gl_json_value *obj, const char *key,
                   const char *path, unsigned long long *out, char *err,
                   size_t errlen)
{
    const struct gl_json_value *v = get(obj, key);

    if (is_null(v))
        return fail(err, errlen, "profile: %s.%s is required", path, key);
    if (gl_json_u64(v, out) != 0)
        return fail(err, errlen, "profile: %s.%s is not an integer or a 0x string",
                    path, key);
    return 0;
}

static int opt_u64(const struct gl_json_value *obj, const char *key,
                   const char *path, unsigned long long *out, int *present,
                   char *err, size_t errlen)
{
    const struct gl_json_value *v = get(obj, key);

    *present = 0;
    if (is_null(v))
        return 0;
    if (gl_json_u64(v, out) != 0)
        return fail(err, errlen, "profile: %s.%s is not an integer or a 0x string",
                    path, key);
    *present = 1;
    return 0;
}

static int check_keys(const struct gl_json_value *obj, const char *const *known,
                      size_t count, const char *path, char *err, size_t errlen)
{
    return gl_json_check_keys(obj, known, count, path, err, errlen);
}

static int in_kernel_va(unsigned long long value)
{
    return value >= 0xffff000000000000ULL;
}

int timer_profile_parse(const char *text, size_t len, struct timer_profile *out,
                        char *err, size_t errlen)
{
    static const char *const top_keys[] = {
        "schema_version", "release", "mode", "safe_mode",
        "devices",        "kernel",  "ghostlock", "timer",
    };
    static const char *const timer_keys[] = {
        "trigger", "match_bits", "sites", "object_size", "task_alloc_size",
        "node_offset", "cred", "trace",
    };
    static const char *const sites_keys[] = {"timer", "task_alloc"};
    static const char *const site_keys[] = {"offset"};
    static const char *const task_alloc_keys[] = {"low_bits"};
    static const char *const timer_cred_keys[] = {"request_size", "alloc_size"};
    static const char *const trace_keys[] = {"gfp"};
    static const char *const gfp_keys[] = {"offset", "size"};
    static const char *const kernel_keys[] = {
        "image_base", "symbols", "struct", "heap", "dirtyfrag", "kdp",
    };
    static const char *const symbol_keys[] = {
        "init_task", "init_cred", "root_task_group", "empty_zero_page",
        "selinux_state", "selinux_blob_sizes", "security_hook_heads",
        "nf_logger", "loggers_0_1", "boot_id", "panic_on_warn", "root_user",
        "init_user_ns", "init_ucounts", "init_groups",
    };
    static const char *const struct_keys[] = {
        "task", "cred", "waiter", "rt_mutex", "selinux_state",
    };
    static const char *const task_keys[] = {
        "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters",
        "pi_top_task", "pi_blocked_on", "pid", "tgid", "atomic_flags",
        "real_cred", "cred", "comm", "tasks", "seccomp",
    };
    static const char *const cred_keys[] = {
        "copy_size", "usage", "uid", "security", "security_sid", "caps",
        "refs",
    };
    static const char *const usage_keys[] = {"offset", "size", "value"};
    static const char *const caps_keys[] = {"offset", "count", "mask"};
    static const char *const refs_keys[] = {
        "user", "user_ns", "ucounts", "group_info",
    };
    static const char *const ref_keys[] = {"offset", "image"};

    struct gl_json_document doc;
    const struct gl_json_value *root;
    const struct gl_json_value *timer = NULL;
    const struct gl_json_value *kernel = NULL;
    const struct gl_json_value *sites = NULL;
    const struct gl_json_value *site = NULL;
    const struct gl_json_value *cred = NULL;
    const struct gl_json_value *trace = NULL;
    const struct gl_json_value *gfp = NULL;
    const struct gl_json_value *symbols = NULL;
    const struct gl_json_value *fields = NULL;
    unsigned long long image_base = 0;
    unsigned long long timer_offset = 0;
    unsigned long long task_low = 0;
    unsigned long long value = 0;
    unsigned int uid_offset = 0;
    int have_image_base = 0;
    int present = 0;
    int fake = 1;
    int rc = -1;

    if (err && errlen)
        err[0] = 0;
    if (len > TIMER_PROFILE_MAX_BYTES)
        return fail(err, errlen, "profile: document exceeds %u bytes",
                    TIMER_PROFILE_MAX_BYTES);
    if (gl_json_parse(text, len, &doc, err, errlen) != 0)
        return -1;
    root = &doc.root;
    if (root->type != GL_JSON_OBJECT)
    {
        fail(err, errlen, "profile: the document must be an object");
        goto done;
    }
    memset(out, 0, sizeof(*out));

    if (check_keys(root, top_keys, sizeof(top_keys) / sizeof(top_keys[0]), "",
                   err, errlen) != 0)
        goto done;
    {
        const struct gl_json_value *schema = get(root, "schema_version");

        if (is_null(schema) || gl_json_u64(schema, &value) != 0 || value != 3)
        {
            fail(err, errlen,
                 "profile: schema_version must be 3 (this build reads the v3 "
                 "layout only)");
            goto done;
        }
    }

    /* --- release -------------------------------------------------------- */
    {
        const struct gl_json_value *release = get(root, "release");

        if (is_null(release) || release->type != GL_JSON_STRING)
        {
            fail(err, errlen, "profile: release is missing or not a string");
            goto done;
        }
        if (release->string[0] == 0 ||
            strlen(release->string) >= sizeof(out->release))
        {
            fail(err, errlen, "profile: release is empty or too long");
            goto done;
        }
        snprintf(out->release, sizeof(out->release), "%s", release->string);
        out->kb.release = out->release;
        out->version = 3;
    }

    /* --- the timer block ------------------------------------------------ */
    if (req_obj(root, "timer", "timer", &timer, err, errlen) != 0)
        goto done;
    if (check_keys(timer, timer_keys, sizeof(timer_keys) / sizeof(timer_keys[0]),
                   "timer", err, errlen) != 0)
        goto done;

    {
        const struct gl_json_value *trigger = get(timer, "trigger");

        if (is_null(trigger) || trigger->type != GL_JSON_STRING)
        {
            fail(err, errlen, "profile: timer.trigger is missing or not a word");
            goto done;
        }
        if (!strcmp(trigger->string, "killable"))
            out->trigger = 1;
        else if (!strcmp(trigger->string, "core_state"))
            out->trigger = 2;
        else
        {
            fail(err, errlen,
                 "profile: timer.trigger must be killable or core_state");
            goto done;
        }
    }
    if (req_u64(timer, "match_bits", "timer", &value, err, errlen) != 0)
        goto done;
    if (value != TIMER_MATCH_BITS)
    {
        fail(err, errlen, "profile: timer.match_bits must be %u",
             TIMER_MATCH_BITS);
        goto done;
    }

    if (req_obj(timer, "sites", "timer.sites", &sites, err, errlen) != 0)
        goto done;
    if (check_keys(sites, sites_keys, sizeof(sites_keys) / sizeof(sites_keys[0]),
                   "timer.sites", err, errlen) != 0)
        goto done;

    if (req_obj(sites, "timer", "timer.sites.timer", &site, err, errlen) != 0)
        goto done;
    if (check_keys(site, site_keys, sizeof(site_keys) / sizeof(site_keys[0]),
                   "timer.sites.timer", err, errlen) != 0)
        goto done;
    if (req_u64(site, "offset", "timer.sites.timer", &timer_offset, err,
                errlen) != 0)
        goto done;

    if (req_obj(sites, "task_alloc", "timer.sites.task_alloc", &site, err,
                errlen) != 0)
        goto done;
    if (check_keys(site, task_alloc_keys,
                   sizeof(task_alloc_keys) / sizeof(task_alloc_keys[0]),
                   "timer.sites.task_alloc", err, errlen) != 0)
        goto done;
    if (req_u64(site, "low_bits", "timer.sites.task_alloc", &task_low, err,
                errlen) != 0)
        goto done;
    if (task_low > TIMER_MATCH_MASK)
    {
        fail(err, errlen, "profile: timer.sites.task_alloc.low_bits exceeds %u bits",
             TIMER_MATCH_BITS);
        goto done;
    }
    out->kb.timer_site_low21 = timer_offset & TIMER_MATCH_MASK;
    out->kb.copy_process_low21 = task_low;

    if (req_u64(timer, "object_size", "timer", &value, err, errlen) != 0)
        goto done;
    if (value < 16 || value > 4096)
    {
        fail(err, errlen, "profile: timer.object_size is out of range");
        goto done;
    }
    out->kb.timer_alloc = value;

    if (req_u64(timer, "task_alloc_size", "timer", &value, err, errlen) != 0)
        goto done;
    if (value < 512 || value > 16384)
    {
        fail(err, errlen, "profile: timer.task_alloc_size is out of range");
        goto done;
    }
    out->kb.task_alloc_bytes = value;

    if (opt_u64(timer, "node_offset", "timer", &value, &present, err, errlen) != 0)
        goto done;
    if (present)
    {
        if (value < 0x40 || value > 0x400)
        {
            fail(err, errlen, "profile: timer.node_offset is out of range");
            goto done;
        }
        out->timer_node_off = (unsigned)value;
    }

    if (req_obj(timer, "cred", "timer.cred", &cred, err, errlen) != 0)
        goto done;
    if (check_keys(cred, timer_cred_keys,
                   sizeof(timer_cred_keys) / sizeof(timer_cred_keys[0]),
                   "timer.cred", err, errlen) != 0)
        goto done;
    if (req_u64(cred, "request_size", "timer.cred", &out->kb.cred_req, err,
                errlen) != 0)
        goto done;
    if (out->kb.cred_req < 16 || out->kb.cred_req > 1024)
    {
        fail(err, errlen, "profile: timer.cred.request_size is out of range");
        goto done;
    }
    if (req_u64(cred, "alloc_size", "timer.cred", &out->kb.cred_alloc, err,
                errlen) != 0)
        goto done;
    if (out->kb.cred_alloc < out->kb.cred_req || out->kb.cred_alloc > 1024)
    {
        fail(err, errlen, "profile: timer.cred.alloc_size is out of range");
        goto done;
    }

    if (req_obj(timer, "trace", "timer.trace", &trace, err, errlen) != 0)
        goto done;
    if (check_keys(trace, trace_keys, sizeof(trace_keys) / sizeof(trace_keys[0]),
                   "timer.trace", err, errlen) != 0)
        goto done;
    if (req_obj(trace, "gfp", "timer.trace.gfp", &gfp, err, errlen) != 0)
        goto done;
    if (check_keys(gfp, gfp_keys, sizeof(gfp_keys) / sizeof(gfp_keys[0]),
                   "timer.trace.gfp", err, errlen) != 0)
        goto done;
    if (req_u64(gfp, "offset", "timer.trace.gfp", &value, err, errlen) != 0)
        goto done;
    if (value < 8 || value > 64)
    {
        fail(err, errlen, "profile: timer.trace.gfp.offset is out of range");
        goto done;
    }
    out->trace.page_gfp_offset = (unsigned)value;
    if (req_u64(gfp, "size", "timer.trace.gfp", &value, err, errlen) != 0)
        goto done;
    if (value != 4 && value != 8)
    {
        fail(err, errlen, "profile: timer.trace.gfp.size must be 4 or 8");
        goto done;
    }
    out->trace.page_gfp_size = (unsigned)value;
    out->has_trace = 1;

    /* --- the shared kernel facts ---------------------------------------- */
    if (req_obj(root, "kernel", "kernel", &kernel, err, errlen) != 0)
        goto done;
    if (check_keys(kernel, kernel_keys,
                   sizeof(kernel_keys) / sizeof(kernel_keys[0]), "kernel", err,
                   errlen) != 0)
        goto done;
    if (opt_u64(kernel, "image_base", "kernel", &image_base, &have_image_base,
                err, errlen) != 0)
        goto done;

    if (have_image_base)
    {
        unsigned long long va = image_base + timer_offset;

        if (!in_kernel_va(va))
        {
            fail(err, errlen,
                 "profile: kernel.image_base + timer.sites.timer.offset is not a "
                 "kernel address");
            goto done;
        }
        out->kb.static_timer_site = va;
    }
    else
    {
        fake = 0;
    }

    if (!is_null(get(kernel, "symbols")))
    {
        if (req_obj(kernel, "symbols", "kernel.symbols", &symbols, err, errlen) != 0)
            goto done;
        if (check_keys(symbols, symbol_keys,
                       sizeof(symbol_keys) / sizeof(symbol_keys[0]),
                       "kernel.symbols", err, errlen) != 0)
            goto done;
    }

    if (!is_null(get(kernel, "struct")))
    {
        if (req_obj(kernel, "struct", "kernel.struct", &fields, err, errlen) != 0)
            goto done;
        if (check_keys(fields, struct_keys,
                       sizeof(struct_keys) / sizeof(struct_keys[0]),
                       "kernel.struct", err, errlen) != 0)
            goto done;
    }

    {
        const struct gl_json_value *task = NULL;
        const struct gl_json_value *kcred = NULL;

        if (!is_null(fields) && !is_null(get(fields, "task")))
        {
            if (req_obj(fields, "task", "kernel.struct.task", &task, err,
                        errlen) != 0)
                goto done;
            if (check_keys(task, task_keys,
                           sizeof(task_keys) / sizeof(task_keys[0]),
                           "kernel.struct.task", err, errlen) != 0)
                goto done;
        }
        if (task)
        {
            if (opt_u64(task, "cred", "kernel.struct.task", &value, &present,
                        err, errlen) != 0)
                goto done;
            if (present)
            {
                if (value < 0x400 || value > 0x2000 ||
                    value + 8 > out->kb.task_alloc_bytes)
                {
                    fail(err, errlen,
                         "profile: kernel.struct.task.cred falls outside the task "
                         "object");
                    goto done;
                }
                out->kb.off_task_cred = value;
            }
            else
            {
                fake = 0;
            }
        }
        else
        {
            fake = 0;
        }

        if (!is_null(fields) && !is_null(get(fields, "cred")))
        {
            if (req_obj(fields, "cred", "kernel.struct.cred", &kcred, err,
                        errlen) != 0)
                goto done;
            if (check_keys(kcred, cred_keys,
                           sizeof(cred_keys) / sizeof(cred_keys[0]),
                           "kernel.struct.cred", err, errlen) != 0)
                goto done;
        }

        /* The timer's own cred target read (the real path needs it). */
        if (!kcred ||
            req_u64(kcred, "uid", "kernel.struct.cred", &value, err, errlen) != 0)
        {
            fail(err, errlen, "profile: kernel.struct.cred.uid is required");
            goto done;
        }
        if (value + 4 > out->kb.cred_req)
        {
            fail(err, errlen,
                 "profile: kernel.struct.cred.uid falls outside the cred object");
            goto done;
        }
        uid_offset = (unsigned int)value;
        out->kb.cred_uid_off = uid_offset;

        /* The fake-cred overlay. */
        if (kcred)
        {
            const struct gl_json_value *usage = NULL;
            const struct gl_json_value *caps = NULL;
            const struct gl_json_value *refs = NULL;

            if (!is_null(get(kcred, "usage")))
            {
                if (req_obj(kcred, "usage", "kernel.struct.cred.usage", &usage,
                            err, errlen) != 0)
                    goto done;
                if (check_keys(usage, usage_keys,
                               sizeof(usage_keys) / sizeof(usage_keys[0]),
                               "kernel.struct.cred.usage", err, errlen) != 0)
                    goto done;
            }
            if (!is_null(get(kcred, "caps")))
            {
                if (req_obj(kcred, "caps", "kernel.struct.cred.caps", &caps, err,
                            errlen) != 0)
                    goto done;
                if (check_keys(caps, caps_keys,
                               sizeof(caps_keys) / sizeof(caps_keys[0]),
                               "kernel.struct.cred.caps", err, errlen) != 0)
                    goto done;
            }
            if (!is_null(get(kcred, "refs")))
            {
                if (req_obj(kcred, "refs", "kernel.struct.cred.refs", &refs, err,
                            errlen) != 0)
                    goto done;
                if (check_keys(refs, refs_keys,
                               sizeof(refs_keys) / sizeof(refs_keys[0]),
                               "kernel.struct.cred.refs", err, errlen) != 0)
                    goto done;
            }

            if (usage)
            {
                if (opt_u64(usage, "size", "kernel.struct.cred.usage", &value,
                            &present, err, errlen) != 0)
                    goto done;
                if (present)
                {
                    if (value != 4 && value != 8)
                    {
                        fail(err, errlen,
                             "profile: kernel.struct.cred.usage.size must be 4 or "
                             "8");
                        goto done;
                    }
                    out->cred.usage_size = (unsigned)value;
                }
            }
            if (caps)
            {
                if (req_u64(caps, "offset", "kernel.struct.cred.caps", &value,
                            err, errlen) != 0)
                    goto done;
                out->cred.caps_offset = (unsigned)value;
                if (req_u64(caps, "count", "kernel.struct.cred.caps", &value,
                            err, errlen) != 0)
                    goto done;
                if (value < 1 || value > 8)
                {
                    fail(err, errlen,
                         "profile: kernel.struct.cred.caps.count is out of range");
                    goto done;
                }
                out->cred.caps_count = (unsigned)value;
            }
            if (req_u64(kcred, "security", "kernel.struct.cred", &value, err,
                        errlen) != 0)
                goto done;
            out->cred.security_offset = (unsigned)value;
            if (req_u64(kcred, "security_sid", "kernel.struct.cred", &value, err,
                        errlen) != 0)
                goto done;
            if (value + 4 > 0x100)
            {
                fail(err, errlen,
                     "profile: kernel.struct.cred.security_sid is out of range");
                goto done;
            }
            out->cred.sid_offset = (unsigned)value;

            if (refs)
            {
                struct {
                    const char *key;
                    unsigned *slot;
                } const ref_table[] = {
                    {"user", &out->cred.user_offset},
                    {"user_ns", &out->cred.user_ns_offset},
                    {"ucounts", &out->cred.ucounts_offset},
                    {"group_info", &out->cred.group_info_offset},
                };
                size_t i;

                for (i = 0; i < sizeof(ref_table) / sizeof(ref_table[0]); i++)
                {
                    const struct gl_json_value *ref = get(refs, ref_table[i].key);

                    if (is_null(ref))
                        continue;
                    if (ref->type != GL_JSON_OBJECT)
                    {
                        fail(err, errlen,
                             "profile: kernel.struct.cred.refs.%s is not an object",
                             ref_table[i].key);
                        goto done;
                    }
                    if (check_keys(ref, ref_keys,
                                   sizeof(ref_keys) / sizeof(ref_keys[0]),
                                   "kernel.struct.cred.refs.<name>", err,
                                   errlen) != 0)
                        goto done;
                    if (req_u64(ref, "offset", "kernel.struct.cred.refs.<name>",
                                &value, err, errlen) != 0)
                        goto done;
                    if (value + 8 > out->kb.cred_req)
                    {
                        fail(err, errlen,
                             "profile: kernel.struct.cred.refs.%s.offset falls "
                             "outside the cred object",
                             ref_table[i].key);
                        goto done;
                    }
                    *ref_table[i].slot = (unsigned)value;
                }
            }
            if (!usage || !caps || !refs || out->cred.sid_offset == 0)
                fake = 0;
            out->has_cred = usage && caps && refs;
            if (out->has_cred)
            {
                if (out->cred.usage_size == 0)
                {
                    fail(err, errlen,
                         "profile: kernel.struct.cred.usage.size is required with "
                         "the fake constants");
                    goto done;
                }
                if (out->cred.caps_offset + out->cred.caps_count * 8u >
                        out->kb.cred_req ||
                    out->cred.security_offset + 8u > out->kb.cred_req ||
                    out->cred.user_offset + 8u > out->kb.cred_req ||
                    out->cred.group_info_offset + 8u > out->kb.cred_req)
                {
                    fail(err, errlen,
                         "profile: the cred overlay falls outside the cred object");
                    goto done;
                }
                if (out->cred.user_ns_offset &&
                    out->cred.user_ns_offset + 8u > out->kb.cred_req)
                {
                    fail(err, errlen,
                         "profile: kernel.struct.cred.refs.user_ns.offset is out "
                         "of range");
                    goto done;
                }
                if (out->cred.ucounts_offset &&
                    out->cred.ucounts_offset + 8u > out->kb.cred_req)
                {
                    fail(err, errlen,
                         "profile: kernel.struct.cred.refs.ucounts.offset is out "
                         "of range");
                    goto done;
                }
            }
        }
    }

    /* The fake path's symbol kit: image_base + image-relative offsets. */
    if (fake && symbols)
    {
        struct {
            const char *key;
            unsigned long long *slot;
            int required;
        } const symbol_table[] = {
            {"selinux_state", &out->kb.selinux_state, 1},
            {"panic_on_warn", &out->kb.panic_on_warn, 1},
            {"root_user", &out->kb.root_user, 1},
            {"init_user_ns", &out->kb.init_user_ns, 1},
            {"init_ucounts", &out->kb.init_ucounts, 0},
            {"init_groups", &out->kb.init_groups, 1},
        };
        size_t i;

        for (i = 0; i < sizeof(symbol_table) / sizeof(symbol_table[0]); i++)
        {
            if (opt_u64(symbols, symbol_table[i].key, "kernel.symbols", &value,
                        &present, err, errlen) != 0)
                goto done;
            if (!present)
            {
                if (symbol_table[i].required)
                    fake = 0;
                continue;
            }
            value += image_base;
            if (!in_kernel_va(value))
            {
                fail(err, errlen,
                     "profile: kernel.symbols.%s is not a kernel address",
                     symbol_table[i].key);
                goto done;
            }
            *symbol_table[i].slot = value;
        }
        if (out->cred.ucounts_offset && out->kb.init_ucounts == 0)
        {
            fail(err, errlen,
                 "profile: kernel.symbols.init_ucounts is required when the cred "
                 "has ucounts");
            goto done;
        }
    }

    out->has_fake = fake;
    rc = 0;

done:
    gl_json_free(&doc);
    return rc;
}

int timer_profile_load(const char *path, struct timer_profile *out, char *err,
                       size_t errlen)
{
    FILE *f;
    char *buf;
    size_t len = 0;
    int rv;

    if (!path || !*path)
        return fail(err, errlen, "profile: empty path");
    f = !strcmp(path, "-") ? stdin : fopen(path, "rb");
    if (!f)
        return fail(err, errlen, "profile: cannot open %s", path);
    buf = malloc(TIMER_PROFILE_MAX_BYTES + 1);
    if (!buf)
    {
        if (f != stdin)
            fclose(f);
        return fail(err, errlen, "profile: out of memory");
    }
    while (len <= TIMER_PROFILE_MAX_BYTES)
    {
        size_t n = fread(buf + len, 1, TIMER_PROFILE_MAX_BYTES + 1 - len, f);

        if (n == 0)
            break;
        len += n;
    }
    if (f != stdin)
        fclose(f);
    if (len > TIMER_PROFILE_MAX_BYTES)
    {
        free(buf);
        return fail(err, errlen, "profile: document exceeds %u bytes",
                    TIMER_PROFILE_MAX_BYTES);
    }
    rv = timer_profile_parse(buf, len, out, err, errlen);
    free(buf);
    return rv;
}
