#include "host_attack_script.hpp"
#include "support/decls.hpp"

/* Host stubs for the side-effecting support primitives the attack data flow
 * calls. No allocation, no socket, no kernel page: a fake base is handed back
 * and the route stub ignores it. */
namespace ghostlock::support {
    void log_sync(void) {}

    void log_startup_context(void) {}

    void init_p0_profile(void) {}

    void disable_rseq_for_thread(void) {}

    uintptr_t prepare_good_kernel_page(const ghostlock::memory::WriteRequest &request) {
        (void) request;
        return host::script().page_base;
    }

    void discard_prebuilt_page(void) {}

    int32_t quarantine_reclaim_sockets(void) { return 1; }

    void release_quarantined_reclaim_sockets(void) {}

    void log_environment(const char *tag) { (void) tag; }

    /* The host harness runs the page-based path as if it were not selected:
     * attack_write stays on the scripted route. */
    bool page_free_run(void) { return false; }

    const char *page_free_reason(void) { return "host"; }

    int32_t prepare_page_free_context(const ghostlock::memory::WriteRequest &request) {
        (void) request;
        return 1;
    }
} // namespace ghostlock::support
