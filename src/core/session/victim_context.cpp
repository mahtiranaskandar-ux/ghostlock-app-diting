#include "session/victim_context.hpp"

#include <signal.h>
#include <sys/mman.h>
#include <sys/wait.h>

#include <cerrno>

namespace ghostlock::session::victim {
    VictimContext::~VictimContext() noexcept {
        release_spin_page();
    }

    void VictimContext::release_spin_page() noexcept {
        if (spin == nullptr) return;
        (void) munmap(spin, sizeof(SpinPage));
        spin = nullptr;
    }

    void VictimContext::retire_child() noexcept {
        const pid_t child = release_child();
        if (child <= 0) return;
        if (kill(child, SIGKILL) != 0 && errno != ESRCH) return;
        while (waitpid(child, nullptr, 0) < 0) {
            if (errno != EINTR) return;
        }
    }
} // namespace ghostlock::session::victim
