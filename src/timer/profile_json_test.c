/* SPDX-License-Identifier: MIT
 *
 * Host tests for the canonical JSON profile reader.
 *
 * Build & run (host cc; no Android headers):
 *   cc -O2 -Wall -Wextra -Wno-unused-function -o profile_json_test \
 *      profile_json_test.c profile_json.c && ./profile_json_test
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "profile_json.h"

static int failures;

static void check(int condition, const char *what)
{
    if (!condition)
    {
        fprintf(stderr, "FAIL %s\n", what);
        failures++;
    }
}

#define CHECK(cond) check((cond), #cond)

/* The diting 5.10.236 document as the app would render it: the native
 * kernel/native blocks are present and must be ignored. */
static const char DOC_DITING_REAL[] =
    "{\n"
    "  \"release\": \"5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284\",\n"
    "  \"schema_version\": 2,\n"
    "  \"kernel_major\": 5,\n"
    "  \"kernel\": { \"offset\": { \"tasks\": 0 }, \"kdp\": 0 },\n"
    "  \"native\": { \"route\": { \"select_stack\": { \"waiter_shift\": -2 } } },\n"
    "  \"timer_98260\": {\n"
    "    \"version\": 1,\n"
    "    \"timer_site_lower_21bit\": 507136,\n"
    "    \"timer_object_size\": 264,\n"
    "    \"cred_request_size\": 168,\n"
    "    \"cred_alloc_size\": 192,\n"
    "    \"cred_uid_offset\": 4,\n"
    "    \"page_alloc_gfp_offset\": 20,\n"
    "    \"page_alloc_gfp_size\": 4\n"
    "  }\n"
    "}\n";

/* A 6.1.138 real,fake document with the 64-bit fields as hex strings. */
static const char DOC_61138_FAKE[] =
    "{\n"
    "  \"release\": \"6.1.138-android14-11-g44bda9e8f6e9-ab13792638\",\n"
    "  \"timer_98260\": {\n"
    "    \"version\": 1,\n"
    "    \"timer_site_lower_21bit\": 1645304,\n"
    "    \"timer_object_size\": 264,\n"
    "    \"cred_request_size\": 176,\n"
    "    \"cred_alloc_size\": 192,\n"
    "    \"cred_uid_offset\": 4,\n"
    "    \"static_timer_site\": \"0xffffffc008191af8\",\n"
    "    \"task_alloc_site_lower_21bit\": 702080,\n"
    "    \"task_alloc_size\": 4800,\n"
    "    \"task_cred_offset\": \"0x838\",\n"
    "    \"selinux_state\": \"0xffffffc00a2293d0\",\n"
    "    \"panic_on_warn\": \"0xffffffc009fdae08\",\n"
    "    \"root_user\": \"0xffffffc00a000740\",\n"
    "    \"init_user_ns\": \"0xffffffc00a0004b0\",\n"
    "    \"init_ucounts\": \"0xffffffc00a001fc8\",\n"
    "    \"init_groups\": \"0xffffffc00a001a60\",\n"
    "    \"cred_usage_size\": 4,\n"
    "    \"cred_caps_offset\": 40,\n"
    "    \"cred_caps_count\": 5,\n"
    "    \"cred_security_offset\": 120,\n"
    "    \"cred_user_offset\": 128,\n"
    "    \"cred_user_ns_offset\": 136,\n"
    "    \"cred_ucounts_offset\": 144,\n"
    "    \"cred_group_info_offset\": 152,\n"
    "    \"security_sid_offset\": 4,\n"
    "    \"timer_node_offset\": 120,\n"
    "    \"page_alloc_gfp_offset\": 20,\n"
    "    \"page_alloc_gfp_size\": 4\n"
    "  }\n"
    "}\n";

static int parses(const char *text, struct timer_profile *out)
{
    char err[256];

    memset(out, 0, sizeof(*out));
    return timer_profile_parse(text, strlen(text), out, err, sizeof(err));
}

static void expect_error(const char *text, const char *needle, const char *what)
{
    struct timer_profile out;
    char err[256] = { 0 };

    if (timer_profile_parse(text, strlen(text), &out, err, sizeof(err)) == 0)
    {
        fprintf(stderr, "FAIL %s: parsed but should not\n", what);
        failures++;
        return;
    }
    if (needle && !strstr(err, needle))
    {
        fprintf(stderr, "FAIL %s: error %s does not mention %s\n", what, err,
                needle);
        failures++;
    }
}

static void test_real_document(void)
{
    struct timer_profile p;

    CHECK(parses(DOC_DITING_REAL, &p) == 0);
    CHECK(!strcmp(p.release, "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"));
    CHECK(p.version == 1);
    CHECK(!p.has_fake);
    CHECK(p.kb.timer_site_low21 == 507136);
    CHECK(p.kb.timer_alloc == 264);
    CHECK(p.kb.cred_req == 168);
    CHECK(p.kb.cred_alloc == 192);
    CHECK(p.kb.cred_uid_off == 4);
    CHECK(p.kb.release == p.release);
    CHECK(!p.has_cred);
    CHECK(p.timer_node_off == 0);
}

static void test_fake_document(void)
{
    struct timer_profile p;

    CHECK(parses(DOC_61138_FAKE, &p) == 0);
    CHECK(p.has_fake);
    CHECK(p.kb.static_timer_site == 0xffffffc008191af8ULL);
    CHECK(p.kb.copy_process_low21 == 702080);
    CHECK(p.kb.task_alloc_bytes == 4800);
    CHECK(p.kb.off_task_cred == 0x838);
    CHECK(p.kb.selinux_state == 0xffffffc00a2293d0ULL);
    CHECK(p.kb.init_ucounts == 0xffffffc00a001fc8ULL);
    CHECK(p.has_cred);
    CHECK(p.cred.usage_size == 4);
    CHECK(p.cred.caps_offset == 40);
    CHECK(p.cred.caps_count == 5);
    CHECK(p.cred.security_offset == 120);
    CHECK(p.cred.user_offset == 128);
    CHECK(p.cred.user_ns_offset == 136);
    CHECK(p.cred.ucounts_offset == 144);
    CHECK(p.cred.group_info_offset == 152);
    CHECK(p.cred.sid_offset == 4);
    CHECK(p.timer_node_off == 120);
}

static void test_refusals(void)
{
    /* unknown key inside the block */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":1,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4,\"surprise\":1}}";
        expect_error(doc, "surprise", "unknown block key");
    }
    /* missing block */
    {
        static const char doc[] = "{\"release\":\"r\"}";
        expect_error(doc, "timer_98260", "missing block");
    }
    /* fake without the cred overlay keys */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":0,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
            "\"static_timer_site\":\"0xffffffc000000000\",\"task_alloc_site_lower_21bit\":1,"
            "\"task_alloc_size\":4800,\"task_cred_offset\":2048,"
            "\"selinux_state\":\"0xffffffc000000001\","
            "\"panic_on_warn\":\"0xffffffc000000002\","
            "\"root_user\":\"0xffffffc000000003\","
            "\"init_user_ns\":\"0xffffffc000000004\","
            "\"init_groups\":\"0xffffffc000000005\"}}";
        expect_error(doc, "cred_caps_offset", "fake without the cred overlay");
    }
    /* any fake key selects the fake set: a lone data symbol is refused for the
     * missing locating constants */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":0,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
            "\"selinux_state\":\"0xffffffc000000001\"}}";
        expect_error(doc, "static_timer_site", "fake key presence");
    }
    /* the old nested cred object is not a known key */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":0,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
            "\"static_timer_site\":\"0xffffffc000000000\",\"task_alloc_site_lower_21bit\":1,"
            "\"task_alloc_size\":4800,\"task_cred_offset\":2048,"
            "\"cred\":{}}}";
        expect_error(doc, "not a known key", "nested cred object");
    }
    /* the static site's low 21 bits must agree with the masked site */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":1,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
            "\"static_timer_site\":\"0xffffffc000000008\"}}";
        expect_error(doc, "low 21 bits", "static site low21");
    }
    /* the cred offset must fall inside the task object */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":0,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
            "\"static_timer_site\":\"0xffffffc000000000\",\"task_alloc_site_lower_21bit\":1,"
            "\"task_alloc_size\":4800,\"task_cred_offset\":8192}}";
        expect_error(doc, "task object", "cred offset bound");
    }
    /* the real five are required even with the fake group */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":1,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192}}";
        expect_error(doc, "cred_uid_offset", "missing real field");
    }
    /* 21-bit bound */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":2097152,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4}}";
        expect_error(doc, "21 bits", "site bound");
    }
    /* duplicate key */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"release\":\"r2\",\"timer_98260\":{}}";
        expect_error(doc, "duplicate", "duplicate key");
    }
    /* trailing data */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{}} garbage";
        expect_error(doc, "trailing", "trailing data");
    }
    /* fractions are not integers */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1.5}}";
        expect_error(doc, "integer", "fraction");
    }
    /* a hex string in a 64-bit field is a string, not a number, and the
     * site fields refuse plain hex strings without 0x */
    {
        static const char doc[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":\"x1\",\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4}}";
        expect_error(doc, "not an integer", "bad hex string");
    }
    /* depth bomb */
    {
        static char deep[2048];
        int i;
        char *at = deep;

        strcpy(deep, "{\"a\":");
        at = deep + strlen(deep);
        for (i = 0; i < 40; i++)
        {
            *at++ = '[';
        }
        for (i = 0; i < 40; i++)
        {
            *at++ = ']';
        }
        *at++ = '}';
        *at = 0;
        expect_error(deep, "nesting", "depth bound");
    }
}

static void test_oversized_document(void)
{
    char *doc = malloc(TIMER_PROFILE_MAX_BYTES + 16);
    struct timer_profile p;
    char err[128];

    static const char head[] = "{\"release\":\"r\",\"timer_98260\":{}}";

    memset(doc, ' ', TIMER_PROFILE_MAX_BYTES + 16);
    memcpy(doc, head, sizeof(head) - 1);
    CHECK(timer_profile_parse(doc, TIMER_PROFILE_MAX_BYTES + 14, &p, err,
                              sizeof(err)) < 0);
    CHECK(strstr(err, "exceeds") != NULL);
    free(doc);
}

static void test_trace_layout(void)
{
    struct timer_profile p;
    static const char doc[] =
        "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
        "\"timer_site_lower_21bit\":1,\"timer_object_size\":264,\"cred_request_size\":168,"
        "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
        "\"page_alloc_gfp_offset\":20,\"page_alloc_gfp_size\":4}}";

    CHECK(parses(doc, &p) == 0);
    CHECK(p.has_trace);
    CHECK(p.trace.page_gfp_offset == 20);
    CHECK(p.trace.page_gfp_size == 4);

    /* the 6.x layout is equally valid */
    {
        static const char doc6[] =
            "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
            "\"timer_site_lower_21bit\":1,\"timer_object_size\":264,\"cred_request_size\":168,"
            "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
            "\"page_alloc_gfp_offset\":24,\"page_alloc_gfp_size\":8}}";

        CHECK(parses(doc6, &p) == 0);
        CHECK(p.has_trace && p.trace.page_gfp_offset == 24 &&
              p.trace.page_gfp_size == 8);
    }

    /* partial or malformed layouts are refused */
    expect_error(
        "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
        "\"timer_site_lower_21bit\":1,\"timer_object_size\":264,\"cred_request_size\":168,"
        "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
        "\"page_alloc_gfp_offset\":20}}",
        "gfp_size", "missing gfp size");
    expect_error(
        "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
        "\"timer_site_lower_21bit\":1,\"timer_object_size\":264,\"cred_request_size\":168,"
        "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
        "\"page_alloc_gfp_offset\":20,\"page_alloc_gfp_size\":2}}",
        "4 or 8", "bad gfp size");
    expect_error(
        "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
        "\"timer_site_lower_21bit\":1,\"timer_object_size\":264,\"cred_request_size\":168,"
        "\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
        "\"page_alloc_gfp_offset\":20,\"page_alloc_gfp_size\":4,\"extra\":1}}",
        "extra", "unknown block key");
}

static void test_hex_round_trip(void)
{
    /* a value that does not fit a signed 64-bit number must be a string */
    unsigned long long value = 0xffffffc008191af8ULL;
    char doc[1024];

    snprintf(doc, sizeof(doc),
             "{\"release\":\"r\",\"timer_98260\":{\"version\":1,"
             "\"timer_site_lower_21bit\":1645304,\"timer_object_size\":264,"
             "\"cred_request_size\":176,\"cred_alloc_size\":192,\"cred_uid_offset\":4,"
             "\"static_timer_site\":\"0x%llx\",\"task_alloc_site_lower_21bit\":1,"
             "\"task_alloc_size\":4800,\"task_cred_offset\":\"0x838\","
             "\"selinux_state\":\"0xffffffc000000001\","
             "\"panic_on_warn\":\"0xffffffc000000002\","
             "\"root_user\":\"0xffffffc000000003\","
             "\"init_user_ns\":\"0xffffffc000000004\","
             "\"init_groups\":\"0xffffffc000000005\","
             "\"page_alloc_gfp_offset\":20,\"page_alloc_gfp_size\":4,"
             "\"cred_caps_offset\":40,\"cred_security_offset\":120,"
             "\"cred_user_offset\":128,\"cred_group_info_offset\":152,"
             "\"security_sid_offset\":4}}",
             value);
    {
        struct timer_profile p;

        CHECK(parses(doc, &p) == 0);
        CHECK(p.kb.static_timer_site == value);
        CHECK(p.has_fake);
    }
}

int main(void)
{
    test_real_document();
    test_fake_document();
    test_refusals();
    test_oversized_document();
    test_hex_round_trip();
    test_trace_layout();
    if (failures)
    {
        fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    printf("profile_json_test: all checks passed\n");
    return 0;
}
