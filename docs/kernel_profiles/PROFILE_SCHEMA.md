# Kernel Profile Structure (schema v3)

One document describes one kernel image: the physical pair per device, the kernel
structures and symbols the engines read, the GhostLock engine's syscall
catalogue, and the timer engine's block. The same shape is used everywhere —
the bundled assets, the test-kernels store, the app's resolved document, the
JSON every native backend reads, and the exported profile.

Chinese version: [PROFILE_SCHEMA_ZH.md](PROFILE_SCHEMA_ZH.md).

## 0. File format

HOCON. JSON is valid HOCON, and every tool also emits plain JSON.
Comments (`#`) are ignored.

| value | spelling |
|---|---|
| kernel address (image-relative symbol, anchor, timer site) | quoted hex string: `"0x2a3a590"` |
| struct offset, size, count | decimal number: `2200` |
| flag | `true` / `false` |
| enumeration | quoted word: `"feasible"`, `"compact"`, `"time64"` |
| absent | `null` — never an omitted key |

**Null is a value.** The document always states the complete key set; a key with
no value is `null`. An unknown key inside a block the app owns is an import
error, and a missing key is an error too.

## 1. Data flow

```
boot.img ──ghostlock-extract──> options-<release>.json (store: analysis envelope
                               + the v3 `document`)
                                   │
                    timer-extract ─┘ merge the timer fragment
                                   │
                    ship_golden_profiles.py
                                   ▼
                     app/src/main/assets/kernel_profiles/<release>.conf
                                   │
   external copy (SAF) > builtin pick > bundled asset     (layer resolution)
                                   ▼
             the app's resolved document (+ measured overlay)
                                   ▼
             ProfileTransport.render → one JSON document per run
                                   ▼
             libghostlock (kernel+ghostlock blocks)   libtimer tool (timer block)
```

On-device generation runs the extractor and the timer analyzer against the
phone's own image, composes the same document in memory, and exports it; the
export becomes an external profile when the user imports it.

## 2. Top level

```hocon
schema_version = 3
release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

devices = null            # or devices { <codename> { phys { load = "0x…", offset = "0x…" } } }
kernel = { … }
ghostlock = { … }         # the GhostLock engine; null for a timer-only document
timer = null              # the timer engine; null when the image has no timer block
```

- `release` is the device's `uname -r` string verbatim. The app refuses a
  document whose release differs from the running kernel.
- `schema_version` is fixed at `3`. v1/v2 documents are refused, not upgraded.
- `devices` is `null` or a map of **every** identity the document supports. A
  device the map does not name is refused (fail-closed); there is no neutral
  fallback. The running device's entry supplies
  `phys.load` / `phys.offset` (the physical load and the DRAM base).
- `kernel` is always present. `ghostlock` and `timer` name the engines: at least
  one is non-null.

### 2.1 Engine selection (no pipeline list)

The app derives the ladder from the document's own facts; the document never
carries a chain list.

| fact | ladder |
|---|---|
| `kernel.kdp = true` | `["dirtyfrag"]` |
| `kernel.dirtyfrag = "feasible"` | `["dirtyfrag", "ghostlock"]` |
| `kernel.dirtyfrag = "infeasible"` / `"unknown"`, `ghostlock` non-null | `["ghostlock"]` |
| `ghostlock.module` non-null | `["module"]` |
| `ghostlock = null`, `timer` non-null | `["timer"]` |

The timer engine is selected separately (it is a shell tool delivered over
Shizuku, not a native route): the app offers it when `timer` is present and its
fake group is complete (`Timer98260.fakeSupported`).

## 3. `kernel`

```hocon
kernel {
  image_base = "0xffffffc008000000"
  symbols { … }
  struct { task {…}, cred {…}, waiter {…}, rt_mutex {…}, selinux_state {…} }
  heap { stride = 960, collisions = 8 }
  dirtyfrag = "feasible"
  kdp = false
}
```

### 3.1 `image_base` and `symbols`

`image_base` is the image's link-time base (`_text`). Every symbol is an
image-relative offset, quoted hex; the native adds the runtime KASLR slide.

The fifteen keys are fixed:

| key | meaning | source |
|---|---|---|
| `init_task`, `init_cred`, `root_task_group`, `empty_zero_page` | kernel objects | main extractor |
| `selinux_state`, `selinux_blob_sizes`, `security_hook_heads` | SELinux state and policy | main extractor |
| `nf_logger`, `loggers_0_1` | the netfilter logger anchors (slide checks) | main extractor |
| `boot_id` | the boot-id word | main extractor |
| `panic_on_warn`, `root_user`, `init_user_ns`, `init_ucounts`, `init_groups` | the timer engine's symbol kit | timer analyzer |

`init_ucounts` is `null` on a kernel without ucounts (5.x).

### 3.2 `struct.task` (15 offsets)

`prio`, `normal_prio`, `sched_task_group`, `pi_lock`, `pi_waiters`,
`pi_top_task`, `pi_blocked_on`, `pid`, `tgid`, `atomic_flags`, `real_cred`,
`cred`, `comm`, `tasks`, `seccomp` — offsets inside `task_struct`, decimal.

### 3.3 `struct.cred`

```hocon
struct.cred {
  copy_size = 168
  usage { offset = 0, size = 4, value = 256 }
  uid = 4
  security = 120
  security_sid = 4
  caps { offset = 40, count = 5, mask = "0x1ffffffffff" }
  refs {
    user       { offset = 128, image = "0xffffffc0827af5e0" }
    user_ns    { offset = 136, image = "0xffffffc0827af678" }
    ucounts    { offset = null, image = null }
    group_info { offset = 144, image = "0xffffffc0827b0b08" }
  }
}
```

- `copy_size` is the number of bytes the W2 copy moves.
- `usage` is the refcount field; `value` is the value the repair writes.
- `caps.offset`/`count` is the five `kernel_cap_t` words the repair writes;
  `mask` is the value.
- `refs` are **named by layout member, not by position**. A 5.x kernel has three
  members (`user`, `user_ns`, `group_info`); `ucounts` is then `null`/`null`.
  `image` is the kernel VA the member must point at after the copy.
- The timer analyzer supplies `security_sid`, the reference offsets and any
  block its BTF states; a value it does not state stays `null` (no opinion).

### 3.4 `struct.waiter`

```hocon
struct.waiter {
  shape = "compact"    # or "node" (rt_waiter_node, 6.6+)
  pi_tree = 24
  task = 48
  lock = 56
  wake_state = 64
}
```

`shape` is the waiter layout the stamp encoder uses. A native route that stamps
the 13-word node layout on an image whose waiter is compact (or the reverse)
stamps the wrong strip; the resolver refuses `multicast_waiter`/`sigreturn` on a
`node` image.

### 3.5 The remaining kernel keys

- `struct.rt_mutex.size` — the anchor stride's lower bound.
- `struct.selinux_state.enforcing` / `.initialized` — the `selinux_state` field
  values W1 writes.
- `heap.stride` — the measured `mm_struct` slab stride. It is never derived
  from the image: a generated profile writes the probe's value in, a bundled
  profile ships the store's measured value, a hand-filled template states it.
  A chain that needs W1 (ghostlock or dirtyfrag) must carry a value; a null is
  refused at import, run and export. The one exception is the generation
  flow's in-memory probe frame: it is the measurement request itself, so it
  may omit the value (that document is never exported or run as is). Only a
  root `/proc/slabinfo` measurement may override the value at run time (the
  measured overlay).
- `heap.collisions` — the KernelSnitch collision count (8 on 5.x, 4 on 6.x where
  the layout is verified, else `null`).
- `dirtyfrag` — the static DirtyFrag verdict: `"feasible"`, `"infeasible"`, or
  `"unknown"` (never measured).
- `kdp` — the Samsung RKP/KDP fact; `true` forces the DirtyFrag-only ladder.

## 4. `ghostlock`

```hocon
ghostlock {
  syscall = "select_stack"          # the selected syscall
  carrier = null                    # null = native 64-bit; "compat" = 32-bit
  anchor { image = "0x2a3a590", bytes = 4096, stride = 32, scratch = "0x2a3a5c0" }
  syscalls {
    select_stack { native { waiter_shift = -2 }, compat = null }
    sigreturn = null
    multicast_waiter { … }
    tcp_zerocopy { native {}, compat = null }
  }
  module = null
  execution = null
}
```

### 4.1 `syscall` and `carrier`

`syscall` names the branch a run uses; `carrier` picks the ABI within it. The
selected pair must exist in `syscalls`, and the selected carrier's block must be
present.

### 4.2 `anchor`

`image` (image-relative), `bytes`, `stride` describe the zeroed anchor region;
`scratch` is the W1 scratch slot inside it (or another region). A page-based
plan states `anchor = null`. The app checks that the collateral write window
stays inside the anchor.

### 4.3 `syscalls` — the four fixed branches

Every branch key is always present; an infeasible syscall is `null`. Each branch
has two carrier slots, `native` and `compat`; the block present is a carrier the
image can run.

| branch | native block | compat block |
|---|---|---|
| `select_stack` | `waiter_shift` | `waiter_home`, `dst_depth`, `futex_abi` |
| `sigreturn` | `waiter_off { plain, sve }` | `null` |
| `multicast_waiter` | `waiter_off`, `buffer_size` | `waiter_off`, `buffer_size`, `futex_abi` |
| `tcp_zerocopy` | `{}` | `null` |

`futex_abi` is `"time32"` or `"time64"`.

### 4.4 `module`

The module chain (a kernel module delivery) replaces the syscall catalogue:

```hocon
ghostlock.module = {
  asset = "modules/device.ko"
  sha256 = "<64 hex>"
  vendor_files = ["/vendor/lib64/libx.so"]
}
```

Exactly one of `syscalls` and `module` is non-null.

### 4.5 `execution`

Optional tuning. The app merges its presets under this block and fills every
`null`; the document may override any key. See [defaults.md](defaults.md) for
the full key list.

## 5. `timer` (CVE-2026-98260)

```hocon
timer {
  trigger = "killable"        # or "core_state"
  match_bits = 21
  sites {
    timer      { offset = "0x191af8" }
    task_alloc { low_bits = "0xab680" }
  }
  object_size = 264
  task_alloc_size = 4800
  node_offset = 120
  cred { request_size = 176, alloc_size = 192 }
  trace { gfp { offset = 24, size = 8 } }
}
```

`timer = null` means the image has no timer block (the engine is unavailable).
The block's own key set is fixed; a value the analyzer could not derive is
`null`. The engine reads its shared facts (`kernel.symbols`, `kernel.struct.cred`,
`kernel.image_base`) from the same document; a missing symbol or reference makes
the engine unavailable rather than partially runnable.

## 6. Validation and feedback

`ProfileResolver` validates the merged document before any run:

- the fixed key sets, with `null` accepted and a missing key refused;
- the selected syscall/carrier and every declared carrier block's completeness;
- the fixed ladder's premises (`kdp` ⇒ DirtyFrag only; a KDP profile cannot
  select GhostLock);
- the anchor containment and the waiter shape vs. the syscall;
- a module-only document may state `kernel` as nulls;
- a timer-only document is the full shape with `ghostlock = null`.

An import that fails is rejected **as a whole**: the field path, the current
value and the rule are reported, and the previous selection stays in force.

## 7. Load layers and storage

1. **Bundled asset** (`app/src/main/assets/kernel_profiles/<release>.conf`) —
   ships with the app; authoritative unless overridden.
2. **Builtin pick** — another bundled release, chosen by hand.
3. **External copy** — a document imported through SAF. It is copied into app
   data at import and becomes the selection; the imported copy survives the
   source document disappearing. A selection applies to its own `release`; a
   document that does not name the running release is a content miss, not a
   fallback.

The **measured overlay** (the `mm` stride, the DirtyFrag probe result) is not a
layer: it is a per-kernel fact store merged into the resolved document at run
time.

## 8. The store and the tools

- `external/test-kernels/<release>/options-<release>.json` — the extractor's
  analysis envelope (schema 3): the cleaned `document` (every device pair in
  `devices.<codename>.phys`), the image-level `routes`, `pipelines`,
  `pipeline_facts`, `pipeline_order` and `image_base`, and `device_analysis[]`
  (per codename: the pair's `phys_source`, the anchor `candidates` and the
  route `plans`; the pair is not repeated). `timer_analysis` keeps the timer
  analyzer's evidence and the fragment it merged.
- `tools/extract_rs` — `ghostlock-extract` (the document builder).
- `tools/timer_extract` — `timer-extract` (the timer fragment).
- `tools/sweep_timer_store.py` — merges the timer fragment into every store
  document.
- `tools/ship_golden_profiles.py` — renders each bundled asset from the store
  document (its device pairs + the measured stride + the header) and keeps
  `index.conf` in step. `--check` compares facts; `--apply` / `--refresh-all`
  rewrite generated assets only.

A store entry is regenerated deterministically from the image:
`ghostlock-extract <Image> --format conf --anchor --device <id> --options-out
<options.json>` (the `generate_options` corpus test does this for the whole
store), then the timer sweep.

## 9. Templates

`templates/kernel-{5.x,6.1,6.6,6.12}.template.conf` are the hand-fill fallbacks
for an image that cannot be extracted. They carry every fixed key with `null`
and the family's structural defaults. Prefer the extractor: it derives every
value from the image and labels each fact's source.

## 10. Earlier formats (retired)

The pre-release schemas v1/v2 and their key names are gone; a v1/v2 document is
refused. For reading old notes:

| retired | now |
|---|---|
| `route.<name>.*`, `route.tcp_zerocopy.compact_waiter` | `ghostlock.syscalls.<name>.<carrier>.*`; the waiter shape is `kernel.struct.waiter.shape` |
| `route.<name>.lock_anchor_image/bytes/stride` | `ghostlock.anchor.image/bytes/stride` |
| `w1_scratch.image` | `ghostlock.anchor.scratch` |
| `fallback.to`, `fallback.route.*` | deleted (no route fallback) |
| `recommend_shizuku` | deleted (manual Shizuku only) |
| `pipeline = [...]` | derived from `kernel.dirtyfrag` / `kernel.kdp` / `ghostlock.module` / `timer` |
| `kernelsnitch.collisions` | `kernel.heap.collisions` |
| `kernelsnitch.mm_struct_sz` | `kernel.heap.stride` |
| `task_struct.*` | `kernel.struct.task.*` |
| `cred.caps_offset` | `kernel.struct.cred.caps.offset` |
| `cred.ref0_offset` … `ref3_*` | `kernel.struct.cred.refs.{user,user_ns,ucounts,group_info}.*` |
| `offset.*` | `kernel.symbols.*` (image-relative hex) |
| `selinux.enforcing/initialized` | `kernel.struct.selinux_state.*` |
| `waiter_layout.*` | `kernel.struct.waiter.*` |
| `kernel_phys_load/offset` (scalar or `{device = x}`) | `devices.<codename>.phys.load/offset` |
| `timer_98260 { … }` | `timer { … }` |

The GLK1 binary wire is also retired: every backend reads the one JSON
document, and the native's readers own disjoint blocks (`kernel`+`ghostlock`,
or `kernel`+`timer`).
