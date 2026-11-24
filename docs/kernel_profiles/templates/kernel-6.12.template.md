# 6.12 profile template

`kernel-6.12.template.conf` is the hand-fill fallback for an image that cannot
be extracted. The authoritative reference is
[PROFILE_SCHEMA.md](../PROFILE_SCHEMA.md); this page lists what to fill.

**Null is a value.** Every key of the fixed shape is present; a key with no
value is `null`, and an unknown key in an app-owned block is an import error.
The extractor generates the document from the image and records each fact's
source; prefer it over hand-filling.

Family notes: waiter `node`；cred 四个引用；caps 48/5；KDP 机型的 `kdp` 写 `true`，执行梯只能是 DirtyFrag。

## What to fill

### `kernel`

| path | fill |
|---|---|
| `image_base` | the image link base (`_text`) |
| `symbols.*` | 15 keys; image-relative quoted hex; `init_ucounts` is `null` without ucounts |
| `struct.task.*` | 15 `task_struct` field offsets (decimal) |
| `struct.cred.copy_size` / `usage.*` / `uid` / `security` / `security_sid` | the W2 credential copy |
| `struct.cred.caps.offset/count/mask` | the five `kernel_cap_t` words |
| `struct.cred.refs.<name>.offset/image` | named member offsets and the VA each must point at |
| `struct.waiter.shape/pi_tree/task/lock/wake_state` | the waiter layout the stamp encoder uses |
| `struct.rt_mutex.size` | anchor stride lower bound |
| `struct.selinux_state.enforcing/initialized` | the field values W1 writes |
| `heap.stride` | the measured slab stride; required when the chain needs W1 (the generation flow writes it into the document) |
| `heap.collisions` | KernelSnitch count (8 on 5.x, 4 on verified 6.x) |
| `dirtyfrag` | `"feasible"` / `"infeasible"` / `"unknown"` |
| `kdp` | `true` on an RKP/KDP image (DirtyFrag only) |

### `ghostlock`

| path | fill |
|---|---|
| `syscall` | `select_stack` / `sigreturn` / `multicast_waiter` / `tcp_zerocopy` |
| `carrier` | `null` (native 64-bit) or `"compat"` (32-bit) |
| `anchor.image/bytes/stride/scratch` | the anchor triple and W1 scratch; `anchor = null` for a page plan |
| `syscalls.<name>.native` / `.compat` | the carrier blocks; an infeasible syscall is `null` |
| `module.asset/sha256/vendor_files` | the module chain (exactly one of `syscalls`/`module`) |
| `execution` | optional tuning; the app fills its presets |

### `timer`

| path | fill |
|---|---|
| `trigger` | `"killable"` or `"core_state"` |
| `match_bits` | 21 |
| `sites.timer.offset` / `sites.task_alloc.low_bits` | image-relative sites |
| `object_size` / `task_alloc_size` / `node_offset` | slab and node constants |
| `cred.request_size` / `alloc_size` | the fake-cred allocation |
| `trace.gfp.offset/size` | the page-alloc trace field |

## Check
Run the app's import; a rejection names the field path, the current value and
the rule. The native refuses a profile whose selected syscall/carrier is absent
or whose anchor does not contain the W1 scratch window.
