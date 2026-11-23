/* SPDX-License-Identifier: MIT
 *
 * Canonical JSON profile document reader for the timer tool.  See
 * profile_json.h for the contract.
 */
#include "profile_json.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_DEPTH 32
#define MAX_VALUES 4096
#define MAX_ITEMS 4096
#define MAX_STRING 4096

enum jtype
{
    J_NULL,
    J_BOOL,
    J_NUM,
    J_STR,
    J_OBJ,
    J_ARR
};

struct jval;

struct jitem
{
    const char *key; /* NULL for array elements */
    struct jval *val;
    struct jitem *next;
};

struct jval
{
    enum jtype type;
    long long num;
    int boolean;
    const char *str;
    struct jitem *items;
};

#define ARENA_BYTES (64u << 10)

struct jp
{
    const char *start;
    const char *p;
    const char *end;
    int depth;
    size_t nvals;
    size_t nitems;
    struct jval *vals;
    struct jitem *items;
    char *arena;
    size_t arena_used;
    char *err;
    size_t errlen;
    int failed;
};

static void set_err(struct jp *j, const char *fmt, ...)
{
    va_list ap;

    if (j->failed)
        return;
    j->failed = 1;
    /* offset first, so every message says where */
    {
        size_t off = (size_t)(j->p - j->start);
        int used = snprintf(j->err, j->errlen, "profile: byte %zu: ", off);

        if (used < 0 || (size_t)used >= j->errlen)
            return;
        va_start(ap, fmt);
        vsnprintf(j->err + used, j->errlen - (size_t)used, fmt, ap);
        va_end(ap);
    }
}

static char *arena_need(struct jp *j, size_t n)
{
    char *out;

    if (j->arena_used + n > ARENA_BYTES)
    {
        set_err(j, "document strings exceed %u bytes", ARENA_BYTES);
        return NULL;
    }
    out = j->arena + j->arena_used;
    j->arena_used += n;
    return out;
}

static int is_digit(char c)
{
    return c >= '0' && c <= '9';
}

static void skip_ws(struct jp *j)
{
    while (j->p < j->end &&
           (*j->p == ' ' || *j->p == '\t' || *j->p == '\n' || *j->p == '\r'))
        j->p++;
}

static struct jval *new_val(struct jp *j)
{
    struct jval *v;

    if (j->nvals >= MAX_VALUES)
    {
        set_err(j, "document has too many values");
        return NULL;
    }
    v = &j->vals[j->nvals++];
    memset(v, 0, sizeof(*v));
    return v;
}

static struct jitem *new_item(struct jp *j)
{
    struct jitem *item;

    if (j->nitems >= MAX_ITEMS)
    {
        set_err(j, "document has too many members");
        return NULL;
    }
    item = &j->items[j->nitems++];
    memset(item, 0, sizeof(*item));
    return item;
}

static const char *parse_string(struct jp *j, size_t *out_len)
{
    const char *scan;
    size_t raw = 0;
    char *buf;

    if (j->p >= j->end || *j->p != '"')
    {
        set_err(j, "expected a string");
        return NULL;
    }
    j->p++;
    /* first pass: find the closing quote and bound the length */
    scan = j->p;
    while (scan < j->end && *scan != '"')
    {
        if ((unsigned char)*scan < 0x20)
        {
            set_err(j, "control character in a string");
            return NULL;
        }
        if (*scan == '\\')
        {
            scan++;
            if (scan >= j->end)
            {
                set_err(j, "truncated escape");
                return NULL;
            }
            if (*scan == 'u')
            {
                if (scan + 4 >= j->end)
                {
                    set_err(j, "truncated \\u escape");
                    return NULL;
                }
                scan += 4;
            }
        }
        scan++;
        raw++;
        if (raw > MAX_STRING)
        {
            set_err(j, "string exceeds %d bytes", MAX_STRING);
            return NULL;
        }
    }
    if (scan >= j->end)
    {
        set_err(j, "unterminated string");
        return NULL;
    }
    buf = arena_need(j, raw + 1);
    if (!buf)
        return NULL;
    /* second pass: decode */
    {
        size_t n = 0;

        while (j->p < scan)
        {
            unsigned char c = (unsigned char)*j->p++;

            if (c != '\\')
            {
                buf[n++] = (char)c;
                continue;
            }
            c = (unsigned char)*j->p++;
            switch (c)
            {
            case '"':
                buf[n++] = '"';
                break;
            case '\\':
                buf[n++] = '\\';
                break;
            case '/':
                buf[n++] = '/';
                break;
            case 'b':
                buf[n++] = '\b';
                break;
            case 'f':
                buf[n++] = '\f';
                break;
            case 'n':
                buf[n++] = '\n';
                break;
            case 'r':
                buf[n++] = '\r';
                break;
            case 't':
                buf[n++] = '\t';
                break;
            case 'u':
            {
                unsigned code = 0;
                int i;

                for (i = 0; i < 4; i++)
                {
                    char h = *j->p++;
                    unsigned digit;

                    if (h >= '0' && h <= '9')
                        digit = (unsigned)(h - '0');
                    else if (h >= 'a' && h <= 'f')
                        digit = (unsigned)(h - 'a' + 10);
                    else if (h >= 'A' && h <= 'F')
                        digit = (unsigned)(h - 'A' + 10);
                    else
                    {
                        set_err(j, "bad \\u escape");
                        return NULL;
                    }
                    code = (code << 4) | digit;
                }
                /* the canonical document is ASCII; anything wider is kept as
                 * a placeholder rather than rejected */
                buf[n++] = code < 0x80 ? (char)code : '?';
                break;
            }
            default:
                set_err(j, "bad escape");
                return NULL;
            }
        }
        buf[n] = 0;
        *out_len = n;
    }
    j->p = scan + 1;
    return buf;
}

static int parse_number(struct jp *j, long long *out)
{
    unsigned long long value = 0;
    int negative = 0;
    int digits = 0;

    if (j->p < j->end && *j->p == '-')
    {
        negative = 1;
        j->p++;
    }
    if (j->p >= j->end || !is_digit(*j->p))
    {
        set_err(j, "expected a number");
        return -1;
    }
    while (j->p < j->end && is_digit(*j->p))
    {
        unsigned digit = (unsigned)(*j->p - '0');

        if (value > (0xffffffffffffffffULL - digit) / 10)
        {
            set_err(j, "number exceeds 64 bits");
            return -1;
        }
        value = value * 10 + digit;
        digits++;
        j->p++;
        if (digits > 20)
        {
            set_err(j, "number exceeds 64 bits");
            return -1;
        }
    }
    if (j->p < j->end && (*j->p == '.' || *j->p == 'e' || *j->p == 'E'))
    {
        set_err(j, "only integer numbers are accepted");
        return -1;
    }
    if (negative)
    {
        if (value > (unsigned long long)0x8000000000000000ULL)
        {
            set_err(j, "number underflows 64 bits");
            return -1;
        }
        *out = (long long)(0 - value);
    }
    else
    {
        if (value > (unsigned long long)0x7fffffffffffffffULL)
        {
            set_err(j, "number exceeds the signed 64-bit range");
            return -1;
        }
        *out = (long long)value;
    }
    return 0;
}

static int parse_value(struct jp *j, struct jval *v);

static int parse_object(struct jp *j, struct jval *v)
{
    struct jitem **tail;

    v->type = J_OBJ;
    v->items = NULL;
    tail = &v->items;
    j->p++; /* '{' */
    j->depth++;
    if (j->depth > MAX_DEPTH)
    {
        set_err(j, "nesting exceeds %d", MAX_DEPTH);
        return -1;
    }
    skip_ws(j);
    if (j->p < j->end && *j->p == '}')
    {
        j->p++;
        j->depth--;
        return 0;
    }
    for (;;)
    {
        char *key;
        size_t key_len;
        struct jval *item_val;
        struct jitem *item;
        struct jitem *scan;

        skip_ws(j);
        key = (char *)parse_string(j, &key_len);
        if (!key)
            return -1;
        skip_ws(j);
        if (j->p >= j->end || *j->p != ':')
        {
            set_err(j, "expected ':' after a key");
            return -1;
        }
        j->p++;
        item_val = new_val(j);
        if (!item_val || parse_value(j, item_val) < 0)
            return -1;
        for (scan = v->items; scan; scan = scan->next)
        {
            if (!strcmp(scan->key, key))
            {
                set_err(j, "duplicate key");
                return -1;
            }
        }
        item = new_item(j);
        if (!item)
            return -1;
        item->key = key;
        item->val = item_val;
        *tail = item;
        tail = &item->next;
        skip_ws(j);
        if (j->p < j->end && *j->p == ',')
        {
            j->p++;
            continue;
        }
        if (j->p < j->end && *j->p == '}')
        {
            j->p++;
            j->depth--;
            return 0;
        }
        set_err(j, "expected ',' or '}'");
        return -1;
    }
}

static int parse_array(struct jp *j, struct jval *v)
{
    struct jitem **tail;

    v->type = J_ARR;
    v->items = NULL;
    tail = &v->items;
    j->p++; /* '[' */
    j->depth++;
    if (j->depth > MAX_DEPTH)
    {
        set_err(j, "nesting exceeds %d", MAX_DEPTH);
        return -1;
    }
    skip_ws(j);
    if (j->p < j->end && *j->p == ']')
    {
        j->p++;
        j->depth--;
        return 0;
    }
    for (;;)
    {
        struct jval *item_val = new_val(j);
        struct jitem *item;

        if (!item_val || parse_value(j, item_val) < 0)
            return -1;
        item = new_item(j);
        if (!item)
            return -1;
        item->key = NULL;
        item->val = item_val;
        *tail = item;
        tail = &item->next;
        skip_ws(j);
        if (j->p < j->end && *j->p == ',')
        {
            j->p++;
            continue;
        }
        if (j->p < j->end && *j->p == ']')
        {
            j->p++;
            j->depth--;
            return 0;
        }
        set_err(j, "expected ',' or ']'");
        return -1;
    }
}

static int match_literal(struct jp *j, const char *text)
{
    size_t len = strlen(text);

    if ((size_t)(j->end - j->p) < len || memcmp(j->p, text, len))
        return -1;
    j->p += len;
    return 0;
}

static int parse_value(struct jp *j, struct jval *v)
{
    skip_ws(j);
    if (j->p >= j->end)
    {
        set_err(j, "unexpected end of document");
        return -1;
    }
    switch (*j->p)
    {
    case '{':
        return parse_object(j, v);
    case '[':
        return parse_array(j, v);
    case '"':
    {
        size_t len = 0;
        const char *str = parse_string(j, &len);

        if (!str)
            return -1;
        v->type = J_STR;
        v->str = str;
        return 0;
    }
    case 't':
        if (match_literal(j, "true") < 0)
            break;
        v->type = J_BOOL;
        v->boolean = 1;
        return 0;
    case 'f':
        if (match_literal(j, "false") < 0)
            break;
        v->type = J_BOOL;
        v->boolean = 0;
        return 0;
    case 'n':
        if (match_literal(j, "null") < 0)
            break;
        v->type = J_NULL;
        return 0;
    default:
        if (*j->p == '-' || is_digit(*j->p))
        {
            if (parse_number(j, &v->num) < 0)
                return -1;
            v->type = J_NUM;
            return 0;
        }
        break;
    }
    set_err(j, "unexpected character '%c'", *j->p);
    return -1;
}

static const struct jval *obj_get(const struct jval *object, const char *key)
{
    const struct jitem *item;

    for (item = object->items; item; item = item->next)
        if (!strcmp(item->key, key))
            return item->val;
    return NULL;
}

/* A field value: a non-negative integer, or an "0x..." string. */
static int value_u64(const struct jval *v, unsigned long long *out)
{
    if (v->type == J_NUM)
    {
        if (v->num < 0)
            return -1;
        *out = (unsigned long long)v->num;
        return 0;
    }
    if (v->type == J_STR)
    {
        const char *s = v->str;
        unsigned long long value = 0;
        int digits = 0;

        if (s[0] != '0' || (s[1] != 'x' && s[1] != 'X'))
            return -1;
        s += 2;
        if (!*s)
            return -1;
        while (*s)
        {
            unsigned digit;

            if (*s >= '0' && *s <= '9')
                digit = (unsigned)(*s - '0');
            else if (*s >= 'a' && *s <= 'f')
                digit = (unsigned)(*s - 'a' + 10);
            else if (*s >= 'A' && *s <= 'F')
                digit = (unsigned)(*s - 'A' + 10);
            else
                return -1;
            if (value > (0xffffffffffffffffULL >> 4))
                return -1;
            value = (value << 4) | digit;
            digits++;
            s++;
            if (digits > 16)
                return -1;
        }
        *out = value;
        return 0;
    }
    return -1;
}

static int block_field(struct jp *j, const struct jval *block,
                       const char *key, unsigned long long *out, int required,
                       int *present)
{
    const struct jval *v = obj_get(block, key);

    if (!v)
    {
        if (present)
            *present = 0;
        if (required)
        {
            set_err(j, "timer_98260.%s is required", key);
            return -1;
        }
        return 0;
    }
    if (present)
        *present = 1;
    if (value_u64(v, out) < 0)
    {
        set_err(j, "timer_98260.%s is not an integer", key);
        return -1;
    }
    return 0;
}

static int in_kernel_va(unsigned long long value)
{
    return value >= 0xffff000000000000ULL;
}

static int check_block_keys(struct jp *j, const struct jval *block)
{
    static const char *const known[] = {
        "version",         "trigger",              "timer_site_lower_21bit", "timer_object_size",
        "cred_request_size",        "cred_alloc_size",      "cred_uid_offset",
        "static_timer_site", "task_alloc_site_lower_21bit", "task_alloc_size",
        "task_cred_offset",   "selinux_state",   "panic_on_warn",
        "root_user",       "init_user_ns",    "init_ucounts",
        "init_groups",     "cred_usage_size", "cred_caps_offset",
        "cred_caps_count", "cred_security_offset", "cred_user_offset",
        "cred_user_ns_offset", "cred_ucounts_offset", "cred_group_info_offset",
        "security_sid_offset",
        "timer_node_offset",
        "page_alloc_gfp_offset", "page_alloc_gfp_size",
    };
    const struct jitem *item;

    for (item = block->items; item; item = item->next)
    {
        size_t i;
        int found = 0;

        for (i = 0; i < sizeof(known) / sizeof(known[0]); i++)
            if (!strcmp(item->key, known[i]))
            {
                found = 1;
                break;
            }
        if (!found)
        {
            j->p = j->end; /* the position is unknown here */
            set_err(j, "timer_98260.%s is not a known key", item->key);
            return -1;
        }
    }
    return 0;
}

static int cred_flat(struct jp *j, const struct jval *block, const char *key,
                     unsigned *out, int required, int *present)
{
    unsigned long long value = 0;
    int local = 1;

    if (!present)
        present = &local;
    if (block_field(j, block, key, &value, required, present) < 0)
        return -1;
    if (!*present)
        return 0;
    if (value > 0xffffffffULL)
    {
        set_err(j, "timer_98260.%s is out of range", key);
        return -1;
    }
    *out = (unsigned)value;
    return 0;
}

static int map_cred(struct jp *j, const struct jval *block,
                    struct cred_layout *out)
{
    int present;

    out->usage_size = 4;
    out->caps_count = 5;
    out->user_ns_offset = 0;
    out->ucounts_offset = 0;
    out->sid_offset = 0;
    if (cred_flat(j, block, "cred_usage_size", &out->usage_size, 0,
                  &present) < 0 ||
        cred_flat(j, block, "cred_caps_offset", &out->caps_offset, 1,
                  NULL) < 0 ||
        cred_flat(j, block, "cred_caps_count", &out->caps_count, 0,
                  &present) < 0 ||
        cred_flat(j, block, "cred_security_offset", &out->security_offset, 1,
                  NULL) < 0 ||
        cred_flat(j, block, "cred_user_offset", &out->user_offset, 1,
                  NULL) < 0 ||
        cred_flat(j, block, "cred_group_info_offset", &out->group_info_offset,
                  1, NULL) < 0 ||
        cred_flat(j, block, "cred_user_ns_offset", &out->user_ns_offset, 0,
                  &present) < 0 ||
        cred_flat(j, block, "cred_ucounts_offset", &out->ucounts_offset, 0,
                  &present) < 0 ||
        cred_flat(j, block, "security_sid_offset", &out->sid_offset, 0,
                  &present) < 0)
        return -1;
    return 0;
}

static int block_has_any(const struct jval *block, const char *const *keys,
                         size_t count)
{
    size_t i;

    for (i = 0; i < count; i++)
        if (obj_get(block, keys[i]))
            return 1;
    return 0;
}

int timer_profile_parse(const char *text, size_t len, struct timer_profile *out,
                        char *err, size_t errlen)
{
    struct jp j;
    struct jval root;
    const struct jval *release;
    const struct jval *block;
    const struct jval *version;
    struct kbuild *kb = &out->kb;
    unsigned long long value;
    size_t release_len = 0;
    int present;

    if (err && errlen)
        err[0] = 0;
    if (len > TIMER_PROFILE_MAX_BYTES)
    {
        snprintf(err, errlen, "profile: document exceeds %u bytes",
                 TIMER_PROFILE_MAX_BYTES);
        return -1;
    }
    memset(&j, 0, sizeof(j));
    j.start = text;
    j.p = text;
    j.end = text + len;
    j.err = err;
    j.errlen = errlen;
    memset(&root, 0, sizeof(root));
    j.vals = calloc(MAX_VALUES, sizeof(*j.vals));
    j.items = calloc(MAX_ITEMS, sizeof(*j.items));
    j.arena = malloc(ARENA_BYTES);
    if (!j.vals || !j.items || !j.arena)
    {
        set_err(&j, "out of memory");
        goto fail;
    }
    if (parse_value(&j, &root) < 0)
        goto fail;
    skip_ws(&j);
    if (j.p != j.end)
    {
        set_err(&j, "trailing data after the document");
        goto fail;
    }
    if (root.type != J_OBJ)
    {
        set_err(&j, "the document must be an object");
        goto fail;
    }
    release = obj_get(&root, "release");
    if (!release || release->type != J_STR)
    {
        set_err(&j, "release is missing or not a string");
        goto fail;
    }
    release_len = strlen(release->str);
    if (release_len == 0 || release_len >= sizeof(out->release))
    {
        set_err(&j, "release is empty or too long");
        goto fail;
    }
    block = obj_get(&root, "timer_98260");
    if (!block || block->type != J_OBJ)
    {
        set_err(&j, "the timer_98260 block is missing");
        goto fail;
    }
    if (check_block_keys(&j, block) < 0)
        goto fail;

    memset(out, 0, sizeof(*out));
    memcpy(out->release, release->str, release_len + 1);
    out->kb.release = out->release;

    version = obj_get(block, "version");
    if (!version || version->type != J_NUM || version->num != 1)
    {
        set_err(&j, "timer_98260.version must be 1");
        goto fail;
    }
    out->version = 1;
    {
        static const char *const fake_keys[] = {
            "static_timer_site", "task_alloc_site_lower_21bit",
            "task_alloc_size", "task_cred_offset", "selinux_state",
            "panic_on_warn", "root_user", "init_user_ns", "init_ucounts",
            "init_groups", "cred_usage_size", "cred_caps_offset",
            "cred_caps_count", "cred_security_offset", "cred_user_offset",
            "cred_user_ns_offset", "cred_ucounts_offset",
            "cred_group_info_offset", "security_sid_offset",
        };

        out->has_fake =
            block_has_any(block, fake_keys,
                          sizeof(fake_keys) / sizeof(fake_keys[0]));
    }
    /* --- the exec trigger (per-kernel fact, selected by the tool) --------- */
    {
        const struct jval *tv = obj_get(block, "trigger");

        if (!tv)
        {
            /*
             * A fake block runs the trigger, so it must say which one; a
             * legacy real-only document predates the key and keeps the
             * 6.1-era signal default.
             */
            if (out->has_fake)
            {
                set_err(&j, "timer_98260.trigger is required with the fake constants");
                goto fail;
            }
            out->trigger = 1;
        }
        else if (tv->type != J_STR)
        {
            set_err(&j, "timer_98260.trigger is not a string");
            goto fail;
        }
        else if (!strcmp(tv->str, "killable"))
            out->trigger = 1;
        else if (!strcmp(tv->str, "core_state"))
            out->trigger = 2;
        else
        {
            set_err(&j, "timer_98260.trigger must be killable or core_state");
            goto fail;
        }
    }
    /* --- real path ------------------------------------------------------- */
    if (block_field(&j, block, "timer_site_lower_21bit", &value, 1, NULL) < 0)
        goto fail;
    if (value > 0x1fffffULL)
    {
        set_err(&j, "timer_98260.timer_site_low21 exceeds 21 bits");
        goto fail;
    }
    kb->timer_site_low21 = value;

    if (block_field(&j, block, "timer_object_size", &value, 1, NULL) < 0)
        goto fail;
    if (value < 16 || value > 4096)
    {
        set_err(&j, "timer_98260.timer_object_size is out of range");
        goto fail;
    }
    kb->timer_alloc = value;

    if (block_field(&j, block, "cred_request_size", &value, 1, NULL) < 0)
        goto fail;
    if (value < 16 || value > 1024)
    {
        set_err(&j, "timer_98260.cred_object_size is out of range");
        goto fail;
    }
    kb->cred_req = value;

    if (block_field(&j, block, "cred_alloc_size", &value, 1, NULL) < 0)
        goto fail;
    if (value < kb->cred_req || value > 1024)
    {
        set_err(&j, "timer_98260.cred_alloc_size is out of range");
        goto fail;
    }
    kb->cred_alloc = value;

    if (block_field(&j, block, "cred_uid_offset", &value, 1, NULL) < 0)
        goto fail;
    if (value + 4 > kb->cred_req)
    {
        set_err(&j, "timer_98260.cred_uid_offset falls outside the cred object");
        goto fail;
    }
    kb->cred_uid_off = (unsigned)value;

    /* --- the fake path --------------------------------------------------- */
    if (out->has_fake)
    {
        if (block_field(&j, block, "static_timer_site", &value, 1, NULL) < 0)
            goto fail;
        if (!in_kernel_va(value))
        {
            set_err(&j, "timer_98260.static_timer_site is not a kernel address");
            goto fail;
        }
        if ((value & 0x1fffffULL) != kb->timer_site_low21)
        {
            set_err(&j, "timer_98260.static_timer_site low 21 bits disagree with timer_site_lower_21bit");
            goto fail;
        }
        kb->static_timer_site = value;

        if (block_field(&j, block, "task_alloc_site_lower_21bit", &value, 1, NULL) < 0)
            goto fail;
        if (value > 0x1fffffULL)
        {
            set_err(&j, "timer_98260.task_alloc_site_lower_21bit exceeds 21 bits");
            goto fail;
        }
        kb->copy_process_low21 = value;

        if (block_field(&j, block, "task_alloc_size", &value, 1, NULL) < 0)
            goto fail;
        if (value < 512 || value > 16384)
        {
            set_err(&j, "timer_98260.task_alloc_size is out of range");
            goto fail;
        }
        kb->task_alloc_bytes = value;

        if (block_field(&j, block, "task_cred_offset", &value, 1, NULL) < 0)
            goto fail;
        if (value < 0x400 || value > 0x2000)
        {
            set_err(&j, "timer_98260.task_cred_offset is out of range");
            goto fail;
        }
        if (value + 8 > kb->task_alloc_bytes)
        {
            set_err(&j, "timer_98260.task_cred_offset falls outside the task object");
            goto fail;
        }
        kb->off_task_cred = value;

        {
            static const struct
            {
                const char *name;
                size_t offset;
            } symbols[] = {
                {"selinux_state", offsetof(struct kbuild, selinux_state)},
                {"panic_on_warn", offsetof(struct kbuild, panic_on_warn)},
                {"root_user", offsetof(struct kbuild, root_user)},
                {"init_user_ns", offsetof(struct kbuild, init_user_ns)},
                {"init_ucounts", offsetof(struct kbuild, init_ucounts)},
                {"init_groups", offsetof(struct kbuild, init_groups)},
            };
            size_t i;

            for (i = 0; i < sizeof(symbols) / sizeof(symbols[0]); i++)
            {
                unsigned long long *slot =
                    (unsigned long long *)((char *)kb + symbols[i].offset);
                /* init_ucounts exists only where the cred has ucounts */
                int required = strcmp(symbols[i].name, "init_ucounts") != 0;

                if (block_field(&j, block, symbols[i].name, &value, required,
                                &present) < 0)
                    goto fail;
                if (!present)
                    continue;
                if (!in_kernel_va(value))
                {
                    set_err(&j, "timer_98260.%s is not a kernel address",
                            symbols[i].name);
                    goto fail;
                }
                *slot = value;
            }
        }

        if (map_cred(&j, block, &out->cred) < 0)
            goto fail;
        out->has_cred = 1;
        if (out->cred.sid_offset == 0)
        {
            set_err(&j, "timer_98260.security_sid_offset is required with the fake constants");
            goto fail;
        }
        if (out->cred.usage_size != 4 && out->cred.usage_size != 8)
        {
            set_err(&j, "timer_98260.cred_usage_size must be 4 or 8");
            goto fail;
        }
        if (out->cred.caps_count < 1 || out->cred.caps_count > 8)
        {
            set_err(&j, "timer_98260.cred_caps_count is out of range");
            goto fail;
        }
        if (out->cred.caps_offset + out->cred.caps_count * 8u > kb->cred_req ||
            out->cred.security_offset + 8u > kb->cred_req ||
            out->cred.user_offset + 8u > kb->cred_req ||
            out->cred.group_info_offset + 8u > kb->cred_req)
        {
            set_err(&j, "timer_98260.cred overlay falls outside the cred object");
            goto fail;
        }
        if (out->cred.user_ns_offset &&
            out->cred.user_ns_offset + 8u > kb->cred_req)
        {
            set_err(&j, "timer_98260.cred_user_ns_offset is out of range");
            goto fail;
        }
        if (out->cred.ucounts_offset &&
            out->cred.ucounts_offset + 8u > kb->cred_req)
        {
            set_err(&j, "timer_98260.cred_ucounts_offset is out of range");
            goto fail;
        }
        if (out->cred.sid_offset + 4u > 0x100)
        {
            set_err(&j, "timer_98260.security_sid_offset is out of range");
            goto fail;
        }
        if (out->cred.ucounts_offset && kb->init_ucounts == 0)
        {
            set_err(&j, "timer_98260.init_ucounts is required when the cred has ucounts");
            goto fail;
        }
    }

    if (block_field(&j, block, "page_alloc_gfp_offset", &value, 1, NULL) < 0)
        goto fail;
    if (value < 8 || value > 64)
    {
        set_err(&j, "timer_98260.page_alloc_gfp_offset is out of range");
        goto fail;
    }
    out->trace.page_gfp_offset = (unsigned)value;
    if (block_field(&j, block, "page_alloc_gfp_size", &value, 1, NULL) < 0)
        goto fail;
    if (value != 4 && value != 8)
    {
        set_err(&j, "timer_98260.page_alloc_gfp_size must be 4 or 8");
        goto fail;
    }
    out->trace.page_gfp_size = (unsigned)value;
    out->has_trace = 1;

    if (block_field(&j, block, "timer_node_offset", &value, 0, &present) < 0)
        goto fail;
    if (present)
    {
        if (value < 0x40 || value > 0x400)
        {
            set_err(&j, "timer_98260.timer_node_offset is out of range");
            goto fail;
        }
        out->timer_node_off = (unsigned)value;
    }

    free(j.vals);
    free(j.items);
    free(j.arena);
    return 0;

fail:
    free(j.vals);
    free(j.items);
    free(j.arena);
    return -1;
}

int timer_profile_load(const char *path, struct timer_profile *out, char *err,
                       size_t errlen)
{
    FILE *f;
    char *buf;
    size_t len = 0;
    int rv;

    if (!path || !*path)
    {
        snprintf(err, errlen, "profile: empty path");
        return -1;
    }
    f = !strcmp(path, "-") ? stdin : fopen(path, "rb");
    if (!f)
    {
        snprintf(err, errlen, "profile: cannot open %s", path);
        return -1;
    }
    buf = malloc(TIMER_PROFILE_MAX_BYTES + 1);
    if (!buf)
    {
        if (f != stdin)
            fclose(f);
        snprintf(err, errlen, "profile: out of memory");
        return -1;
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
        snprintf(err, errlen, "profile: document exceeds %u bytes",
                 TIMER_PROFILE_MAX_BYTES);
        return -1;
    }
    rv = timer_profile_parse(buf, len, out, err, errlen);
    free(buf);
    return rv;
}
