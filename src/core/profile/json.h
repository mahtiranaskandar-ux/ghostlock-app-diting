/* SPDX-License-Identifier: MIT
 *
 * The canonical JSON document reader shared by every backend.
 *
 * The app renders one document (`ProfileDocument.render`) and hands it to
 * whichever backend runs. This unit owns only the syntax: a strict, bounded
 * tree of values with no duplicate keys, integer numbers, quoted strings and
 * `0x...` strings for kernel VAs above 2^63. Each backend maps the tree onto
 * its own structure (`json_profile.cpp` here, `timer/profile_fields.c`).
 *
 * The rules are the timer tool's, unchanged: at most GL_JSON_MAX_VALUES
 * values, GL_JSON_MAX_MEMBERS members and GL_JSON_MAX_DEPTH nesting; strings
 * live in one GL_JSON_ARENA_BYTES arena. A document that breaks a rule is
 * refused with a message, never truncated.
 */
#ifndef GHOSTLOCK_PROFILE_JSON_H
#define GHOSTLOCK_PROFILE_JSON_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GL_JSON_MAX_DOCUMENT (1u << 20)
#define GL_JSON_MAX_VALUES 4096
#define GL_JSON_MAX_MEMBERS 4096
#define GL_JSON_MAX_DEPTH 32
#define GL_JSON_ARENA_BYTES (64u << 10)
#define GL_JSON_MAX_STRING 4096

enum gl_json_type {
    GL_JSON_NULL = 0,
    GL_JSON_BOOL,
    GL_JSON_NUMBER,
    GL_JSON_STRING,
    GL_JSON_OBJECT,
    GL_JSON_ARRAY,
};

struct gl_json_value;
struct gl_json_member;

struct gl_json_member {
    const char *key; /* NULL for an array element */
    struct gl_json_value *value;
    struct gl_json_member *next;
};

struct gl_json_value {
    enum gl_json_type type;
    long long number;
    int boolean;
    const char *string; /* points into the document arena */
    struct gl_json_member *members;
};

/* One parsed document. It owns every value, member and string; free it with
 * gl_json_free(). */
struct gl_json_document {
    struct gl_json_value root;

    /* private to json.c */
    struct gl_json_value *values;
    struct gl_json_member *members;
    char *arena;
    size_t arena_used;
};

/* Parses `text` (canonical JSON: quoted keys, integer numbers, objects,
 * arrays, null, true, false). Returns 0, or -1 with one message in `err`. */
int gl_json_parse(const char *text, size_t len, struct gl_json_document *document,
                  char *err, size_t errlen);

void gl_json_free(struct gl_json_document *document);

/* Member lookup on an object; NULL when the key is absent (or `object` is not
 * an object). */
const struct gl_json_value *gl_json_get(const struct gl_json_value *object,
                                        const char *key);

/* Typed reads. Return 0 on success, -1 when the value is not the type (a
 * quoted "0x..." string is accepted wherever an unsigned integer is asked
 * for). JSON null is never a valid value: callers treat "absent or null" as
 * "no value" by checking the type themselves. */
int gl_json_u64(const struct gl_json_value *value, unsigned long long *out);
int gl_json_i64(const struct gl_json_value *value, long long *out);
int gl_json_bool(const struct gl_json_value *value, int *out);

/* Refuses any member of `object` whose key is not in `known`. `path` prefixes
 * the message (for example "kernel.struct.task"); pass NULL for none. */
int gl_json_check_keys(const struct gl_json_value *object,
                       const char *const *known, size_t count, const char *path,
                       char *err, size_t errlen);

#ifdef __cplusplus
}
#endif

#endif /* GHOSTLOCK_PROFILE_JSON_H */
