# Kernel Profile Structure (Configuration System)

> 中文: [PROFILE_SCHEMA_ZH.md](PROFILE_SCHEMA_ZH.md)

This document describes the complete structure of the kernel profiles under
`app/src/main/assets/kernel_profiles/`: what each field means, how routes work,
how profiles are validated, and how configuration flows between Kotlin and
native.

> To add support for a new kernel, follow [README.md](README.md). For
> execution-tuning defaults, see [defaults.md](defaults.md).

> **Version names** (do not conflate them):
> - **Profile schema version**: the HOCON profile generation, carried by the
>   `schema_version` field inside each profile (see section 2). It is **`3`** —
>   the only accepted version. The earlier `1`/`2` documents and the
>   unversioned `remote/main`-era JSON reports were retired on 2026-11-22: a
>   document that states `1`, `2`, or no version is refused; regenerate or
>   export it with this build (section 11).
> - **Options-report version**: the extractor's `--options-out` document has
>   its own `schema_version` (currently `2`); it is not a profile and is not
>   accepted as one.
> - **Binary wire version**: the GLK1 transport version after magic
>   `0x0D000721`. The current writer emits wire version `2` (object sections,
>   see section 9); it is the only version, and Kotlin/native are version-bound.
>
> Everything below describes schema v3.

## 0. File format (HOCON)

Built-in profiles, `index.conf`, shared files, and imported offset files are all
parsed as **HOCON**:

- JSON is a subset of HOCON;
- `#` / `//` comments, trailing commas, and `${var}` substitution (optional
  `${?var}`) are supported;
- `include "file.conf"` is supported (relative to the same directory, nestable,
  loop-safe): `AssetConfigLoader` expands includes when reading assets, while
  imports prefer a file selected alongside the profile and otherwise look in the
  bundled shared files, failing loudly when missing so you can reselect;
- parsing happens on the Kotlin side (`HoconSupport`), then the typed binary
  struct crosses to native (section 9);
- the app stores and exports HOCON; `ghostlock-extract --format conf` emits a
  flattened, self-contained profile (credential/KernelSnitch constants inlined,
  no `include` lines) that carries `schema_version = 3` and imports through the
  normal path;
- extractor output is a **candidate source**: it writes every field the image
  actually yields and omits the rest, never borrowing a neighbouring kernel
  family's guesses. A candidate may be incomplete; after import the app's field
  validation fills `invalidPaths` and blocks execution, so a successful
  generation never means the device is supported.

## 1. Data flow

```
assets/kernel_profiles/<release>.conf     built-in profile (HOCON)
assets/kernel_profiles/execution-tuning.conf   general execution tuning preset (all kernels)
assets/kernel_profiles/execution-<route>.conf   per-route execution tuning preset (loaded by the resolver)
assets/kernel_profiles/credential-6x.conf      6.x credential template
assets/kernel_profiles/kernelsnitch-6x.conf    6.x KernelSnitch values
assets/kernel_profiles/<major.minor>-template.conf  reference templates (registered in
                                             index; loadable from the debug page,
                                             never matched automatically)
filesDir/offsets.conf                     parsed/imported offsets (imported, HOCON)
internal overrides (sparse HOCON text)    written by the advanced override page
        │
        ▼  ProfileConfigController.resolve (Kotlin)
   resolved profile (one object, every field merged)
        │
        ├──▶ runtime: typed binary (direct and Shizuku both write it to native's stdin)
        │      native only parses and uses the geometry; it validates nothing
        ├──▶ snapshot: filesDir/<release>.conf (HOCON; the source for exports)
        └──▶ UI: override page / advanced override tree
```

Merge priority (low to high): `execution-tuning` (plus the per-route
`execution-<route>.conf`) → built-in profile + imported offsets → advanced
overrides.

The CPU pair is not a profile input: the native chooses it at run time
(`cpu_select`). The wire's required `recommended_cpus` slots carry the neutral
fallback `0/1`.

## 2. Top-level structure

```hocon
# GhostLock kernel profile (HOCON; JSON stays valid)
release = "6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k"
schema_version = 3
kernel_major = 6
route {
  select_stack { waiter_shift = -2 }
}
fallback { to = "none" }
kernelsnitch { collisions = 4, mm_struct_sz = 1024 }  # stride: bundled value or measured
task_struct { prio = 132, cred = 2080, pi_lock = 2316 }
cred { copy_size = 136, caps_offset = 48, caps_count = 5 }
offset { init_task = 34464384, init_cred = 34538824 }
# execution tuning is provided by the resolver from the preset files; write only differences
```

| Field | Type | Meaning |
|---|---|---|
| `release` | string | Must match the device's `uname -r` exactly (case and suffix); template files never participate in matching |
| `schema_version` | int | Must be `3`; any other value (or an absent field) is refused. The phys fields may be a per-device map (preferred), an all-device scalar, or `null` (native default) |
| `kernel_major` | int | `5` or `6`; used for address resolution and sanity checks, no longer selects the route |
| `pipeline` | list | Optional ordered chain list: `ghostlock`, `dirtyfrag`, `module`; absent = `["ghostlock"]`. See *Pipeline selection* below |
| `route` | object | Explicit route parent with one or more branches; the app's picker displays the viable ones; see section 3 |
| `routes` | list | Legacy and ignored: the app has no route order and displays the viable routes alphabetically; see section 3 |
| `fallback` | object | Fallback declaration (`to` plus an optional `route` branch); see section 3 |
| geometry | object/int | Grouped by namespace: `task_struct` / `cred` / `offset` / `kernelsnitch`. Omit unused route-specific fields entirely — don't write `0` or placeholders. `null` only appears in templates and in-progress edits |
| `execution` | object | Advisory tuning. General values come from `execution-tuning.conf`, per-route values from `execution-<route>.conf`; the resolver loads both as presets, device profiles don't `include` them. Write only differences |
| `timer_98260` | object | Optional timer-route capability block (section 4.9); absent = a native-only profile |
| `module` | object | The module chain's pair manifest (section 4.10); required with, and only with, a `module` entry in `pipeline` |

### Pipeline selection (`pipeline`)

`pipeline` is optional. Absent means the legacy default `["ghostlock"]`. It is an ordered list of
chain names; the resolver walks it before any stage and runs the first viable entry. Values:
`ghostlock`, `dirtyfrag`, `module`.

`module` is the bundled-only chain (DirtyFrag without W1, a per-kernel module as the SELinux
switch, Magisk as the userspace manager). It is never derived: it is honoured only for a bundled
profile that ships together with a hand-built module for the same release, declared by the
`module` block (section 4.10), and an imported profile declaring it is refused. The entry and the
block imply each other: one without the other is refused.

The requirements are pipeline-scoped. `release`, `schema_version` and `kernel_major` are always
required. The `route`, `task_struct`, `cred` and `offset` groups are required only when the list
contains a W1-based entry (`ghostlock` or `dirtyfrag`, including the legacy default); a
`["module"]` document may omit them. A `route` that is present is still validated as in section 3.
For a KDP image (`kdp = 1`) the order must be the strict `["dirtyfrag"]` or `["module"]`.

## 3. Route mechanism

The route is no longer inferred from the kernel version or from which fields
exist: the profile declares it explicitly. `route` is a parent with **one or
more branches** — a one-branch profile is the single-route case, and multiple
branches form the superset the app's route picker displays
(`docs/analysis/route-superset-plan.md`):

```hocon
route { select_stack { waiter_shift = -2 } }
# or the superset:
route {
  select_stack { waiter_shift = -2 }
  tcp_zerocopy { compact_waiter = 1 }
}
routes = ["select_stack", "tcp_zerocopy", "timer_98260"]
```

- **Viability is a parse result.** The app validates each branch (its required
  fields and the static guards: the waiter shape, anchor alignment, page-free
  facts) and the optional `timer_98260` block (the complete fake group). Only
  the viable entries are listed in the picker; a branch that fails is not a
  candidate, and it does not invalidate the profile's other branches.
- **No route order.** The app displays the viable routes **alphabetically**; a
  legacy `routes` list is accepted and validated but ignored. The choice is
  remembered per exact `uname -r` (a remembered route that is no longer viable
  is simply cleared); with no remembered choice the alphabetically first viable
  route is the default. The native routes need a `route.<name>` branch; the
  timer route is the `timer_98260` block and has no branch.
- **Carriers are a list inside the branch.** A route's selectable carriers are a
  list key, like `pipeline`, and the block carries the union of their geometry
  (`docs/analysis/route-choice-matrix-plan.md`):
  ```hocon
  route {
    select_stack {
      carriers = [ "native", "compat" ]
      waiter_shift = 1
      waiter_home = 488
      select_dst_depth = 496
      compat_futex_abi = 1
    }
  }
  ```
  A branch without `carriers` keeps the legacy rule: `compat_carrier = 1`
  selects the AArch32 carrier, its absence the native one. The app renders one
  carrier box when the list has more than one entry, remembers the choice per
  release and route, and injects the wire's `compat_carrier` from it; the
  native wire schema is unchanged.
- **DirtyFrag is a run choice, not a document key.** The profile's ordered
  `pipeline` list is the capability; the home shows an on/off box only when
  the list names both `dirtyfrag` and `ghostlock`, and "off" trims the order to
  GhostLock for that run.
- **The runtime document is still one route.** The app renders the chosen
  branch into the native wire (and the timer block into the timer document);
  the native's `fallback` stays as the primary route's own internal fallback.
- `fallback` declares a fallback: `"to"` is `"none"` or a route name. When you
  declare a target, also provide the `"route"` branch holding the fields the
  fallback needs:
  ```hocon
  fallback {
    to = "select_stack"
    route { select_stack { waiter_shift = 1 } }
  }
  ```
  The current implementation supports falling back from `tcp_zerocopy` to
  `select_stack`; write `"to": "none"` to disable it.
- Native's `RouteKind` enum maps one-to-one to the Kotlin values
  (`profile.h` / `ProfileConfig.Routes`).

### Required-field matrix

| Field group | tcp_zerocopy | select_stack | multicast_waiter |
|---|:---:|:---:|:---:|
| `offset.init_task` / `offset.init_cred` / `offset.root_task_group` / `offset.selinux_enforcing` | required | required | required |
| `task_struct.prio` / `task_struct.pi_lock` / `task_struct.pi_waiters` / `task_struct.pi_blocked_on` / `task_struct.cred` / `task_struct.seccomp` | required | required | required |
| `kernel_major` ∈ {5,6}, `cred.copy_size`, `cred.caps_count`, and the credential-template bounds | required | required | required |
| `route.tcp_zerocopy.compact_waiter` / `route.multicast_waiter.compact_waiter` | required | | required |
| `route.select_stack.waiter_shift` | | required (0 is valid) | |
| `route.multicast_waiter.waiter_off` (>0), `route.multicast_waiter.buffer_size`, `route.multicast_waiter.task_offset`, `route.multicast_waiter.lock_offset`, `offset.empty_zero_page`, `cred.ref_count` (>0) | | | required |

A branch that lists `carriers` requires each listed carrier's fields: the
native entry needs `waiter_shift` (select) and the compat entry needs
`waiter_home` + `select_dst_depth` (select) or `compat_waiter_off` +
`compat_buffer_size` + `compat_futex_abi` (multicast). `compact_waiter` stays
required for tcp and multicast. A legacy branch without `carriers` is checked
as before.

Credential-template bounds (general): `cred.usage_offset + 4 ≤ cred.copy_size`;
`cred.caps_offset + cred.caps_count × 8 ≤ cred.copy_size`; `cred.ref_count ≤ 4`;
each `cred.refN_image` must be non-zero and
`cred.refN_offset + 8 ≤ cred.copy_size`. `multicast_waiter` additionally requires
`cred.copy_size ≥ 0xa0` and
`route.multicast_waiter.waiter_off + route.multicast_waiter.lock_offset + 8 ≤ route.multicast_waiter.buffer_size`.

## 4. Geometry field groups

### 4.0 Kernel objects and field mapping

Configuration field names are the kernel struct member names (offsets inside the
struct):

```
task_struct
├── prio             → task_struct.prio
├── normal_prio      → task_struct.normal_prio
├── sched_task_group → task_struct.sched_task_group
├── pi_lock          → task_struct.pi_lock
├── pi_waiters       → task_struct.pi_waiters
├── pi_top_task      → task_struct.pi_top_task
├── pi_blocked_on    → task_struct.pi_blocked_on
├── pid / tgid       → task_struct.pid / task_struct.tgid
├── atomic_flags     → task_struct.atomic_flags
├── real_cred        → task_struct.real_cred
├── cred             → task_struct.cred
├── comm / tasks     → task_struct.comm / task_struct.tasks
└── seccomp          → task_struct.seccomp
```

```
cred
├── usage            → cred.usage_offset / cred.usage_value
├── cap_*            → cred.caps_offset / cred.caps_count / cred.caps_value
└── reference repairs → cred.ref_count + cred.refN_offset / cred.refN_image
```

```
forged multicast object (multicast_waiter route)
├── waiter start     → route.multicast_waiter.waiter_off
└── task / lock      → route.multicast_waiter.task_offset / lock_offset
```

The `offset` namespace holds kernel-image symbol offsets (`init_task`,
`init_cred`, `empty_zero_page`, …); `offset.slide_*` are the KASLR slide anchors.
Together with `kernel_phys_load` they turn symbol addresses into runtime
addresses.

### 4.1 Task structure offsets (`task_struct`)

| Field | Meaning |
|---|---|
| `task_struct.prio` / `task_struct.normal_prio` | Task priority / normal priority (PI boost check) |
| `task_struct.sched_task_group` | `sched_task_group` offset |
| `task_struct.pi_lock` / `task_struct.pi_waiters` / `task_struct.pi_top_task` / `task_struct.pi_blocked_on` | `pi_lock`, `pi_waiters`, `pi_top_task`, `pi_blocked_on` |
| `task_struct.pid` / `task_struct.tgid` | PID / TGID |
| `task_struct.atomic_flags` | `atomic_flags` (used to decide whether state was cleaned up) |
| `task_struct.real_cred` / `task_struct.cred` | real cred / cred pointers |
| `task_struct.comm` / `task_struct.tasks` / `task_struct.seccomp` | `comm`, task list, `seccomp` |

### 4.2 Credential template (`cred`)

| Field | Meaning |
|---|---|
| `cred.copy_size` | Total bytes copied from the credential struct |
| `cred.usage_offset` / `cred.usage_value` | Refcount field offset / target value |
| `cred.caps_offset` / `cred.caps_count` / `cred.caps_value` | Capability set offset / count / fill value |
| `cred.ref_count` | Number of reference fields to repair (≤4) |
| `cred.refN_offset` / `cred.refN_image` (N=0..3) | Offset of each reference field / image value to restore |

### 4.3 Kernel symbols and slide anchors (`offset`)

| Field | Meaning |
|---|---|
| `offset.init_task` / `offset.init_cred` | `init_task` / `init_cred` offsets relative to the kernel image base |
| `offset.root_task_group` | `root_task_group` offset |
| `offset.selinux_enforcing` | `selinux_state` symbol offset; the `selinux.enforcing` block adds the field byte inside the struct |
| `offset.selinux_blob_sizes` / `offset.security_hook_heads` | SELinux / security-hook offsets |
| `offset.slide_nfulnl_logger` / `offset.slide_boot_id` / `offset.slide_loggers_0_1` | KASLR slide anchors |
| `kernel_phys_load` | Kernel physical load address (0 falls back to the SoC formula) |
| `kernel_phys_offset` | DRAM base / linear-map `PHYS_OFFSET` used for image→direct-map translation (default: compiled `P0_PHYS_OFFSET = 0x80000000`). Set it for devices whose DRAM base differs (e.g. MTK `0x40000000`); `boot.img` does not carry it — on MediaTek the extractor reads it from the `vendor_boot.img` embedded DTB's `/memory` node (or `--dtbo`), and on a rooted device `/proc/iomem` supplies it |

`recommend_shizuku` was retired from the authored schema on 2026-11-23. It is
still accepted and ignored in existing schema-v3 documents; Shizuku is opt-in
through the app switch.

### 4.4 select_stack / tcp route fields

| Field | Meaning |
|---|---|
| `route.select_stack.waiter_shift` | Relative shift of the select-route waiter on the stack (0 is valid); under a fallback declaration this is `fallback.route.select_stack.waiter_shift` |
| `route.select_stack.lock_anchor_image` | Optional. Image-relative offset of a zero, writable, reference-free kernel region used as the walk's fake `rt_mutex` instead of the reclaimed payload page, so `rt_mutex_enqueue`'s descent exits on its first test regardless of what that page holds. Absent => the reclaimed page is used (previous behaviour). Device-specific; pick and verify per device (see `docs/analysis/payload-page-option2-plan.md`) |
| `route.tcp_zerocopy.compact_waiter` | Compact-waiter layout flag for the tcp route; the multicast branch needs it too (`route.multicast_waiter.compact_waiter`) |

### 4.5 multicast_waiter route fields (`route.multicast_waiter`)

| Field | Meaning |
|---|---|
| `route.multicast_waiter.waiter_off` | Offset of the waiter in the multicast buffer (must be > 0) |
| `route.multicast_waiter.buffer_size` | Forged buffer size |
| `route.multicast_waiter.task_offset` / `route.multicast_waiter.lock_offset` | Task / lock field offsets in the buffer |
| `route.multicast_waiter.lock_anchor_image` | Optional. Image-relative offset of a zero, writable, reference-free kernel region used as the walk's fake `rt_mutex` instead of the reclaimed payload page, so the walk's descent exits on its first test regardless of what that page holds. Absent => the reclaimed page is used (previous behaviour). The anchor's `lock` word then comes from this region, not from the page. Device- and build-specific; a wrong value is a kernel crash |
| `route.multicast_waiter.lock_anchor_bytes` / `route.multicast_waiter.lock_anchor_stride` | Optional. Length of the verified-safe part of that region and the slot stride within it (a slot grid of `bytes / stride` slots). The anchored route requires **all three** keys: there is no inherited default, and the diting grid (4096 / 32) must not be assumed for another kernel. Native refuses a `bytes` that is not a non-zero multiple of `stride`, or a `stride` below the fake `rt_mutex`'s own size. They are stated in this section rather than borrowed from `route.select_stack` because a document never merges another route's section |
| `offset.empty_zero_page` | `empty_zero_page` offset |

### 4.6 KernelSnitch values (`kernelsnitch`)

Shared by every route (KernelSnitch drives the `mm_struct` leak search), not
affected by route choice:

| Field | Meaning |
|---|---|
| `kernelsnitch.collisions` | Number of futex collisions needed |
| `kernelsnitch.mm_struct_sz` | SLUB object stride of `mm_struct`. Bundled profiles carry their kernel family's measured value (5.10: 960; 5.15/6.1: 1024; 6.6/6.12: 1280, QEMU-confirmed); a generated profile writes it only from a measurement (the generation picker or the root slabinfo read), never a heuristic. Omitted = the compiled default `0x500`. It sizes the spray (`ORDER3_SIZE / stride` objects per slab); the leak itself stays stride-agnostic. |

### 4.7 SELinux state (`selinux`)

Field offsets inside `selinux_state`, consumed by W1 and by the page-free scratch admissibility.
These are not image offsets, so they do not live under `offset.*`.

| Field | Meaning |
|---|---|
| `selinux.enforcing` | Byte offset of the `enforcing` field inside `selinux_state`; `0` on 5.10/6.x, `1` on the 5.4 family (whose unconditional `disabled` byte shifts it). W1's value byte 0 lands here. |
| `selinux.initialized` | The byte of the W1 value that lands on the initialized field (`initialized_struct − enforcing_struct`). The page-free write value must leave this byte non-zero; the extractor places the scratch accordingly. Release fallback: `2` below 6.6, `1` from 6.6. |

The extractor emits the block from BTF on 5.15/6.x and from the measured release table on
5.10 and 5.4 (the table's config premises ride the fact header as provenance, not a gate); it
fails closed when neither witness exists. A profile without the block uses the release-derived
fallback (5.4: enforcing `1`, initialized `2`). The resolver validates `enforcing` and
`initialized` as byte offsets in `0..7`.

### 4.8 W1 scratch and page-free mode (`w1_scratch`)

`w1_scratch` names an image-relative kernel region whose direct-map alias supplies W1's write
value (`alias(image) + 0x100`): byte 0 lands on `selinux.enforcing` (the write targets the field,
not the struct base) and must read 0, and value byte `selinux.initialized` must be non-zero. The extractor derives the placement with the
lock-anchor scan: an in-anchor window when one exists (the native reserves the lock slots it
overlaps), otherwise a second safe region. The profile carries only the image offset; a wrong
value is a kernel crash.

| Field | Meaning |
|---|---|
| `w1_scratch.image` | Image-relative offset of the zero, writable, reference-free region used for W1's value. Absent => the reclaimed-page path |

A run is page-free when the route policy is page-free capable (`select_stack`, `sigreturn`,
`multicast_waiter`; `tcp_zerocopy` is excluded because its fake lock is a crafted page object),
the profile has `w1_scratch.image`, and the profile has the route's `lock_anchor_*` trio. The
flag is a per-run constant and the store's first lines say which: `page_free=1
reason=policy+scratch+anchor`, or `page_free=0 reason=<first missing conjunct>`
(`route-not-capable` / `no-scratch` / `no-anchor`).

On the page-free path the native skips `prepare_good_kernel_page()`: no spray, no KernelSnitch
leak, no reclaim sockets, no page-acceptance gate, no `[spray]` lines, `base=0`. Each write
states its value source (`w1_value_source=scratch` for W1/W1p, `init_cred` for W2, `target-8` for
leaf writes). The page-based credential copy/fill and the fops sentinel remain on the page path; a
profile without either anchor behaves exactly as before. Both keys are optional and do not change
`schema_version` (always `3`); a missing key means the page path.

### 4.9 The timer route's capability block (`timer_98260`)

An optional top-level block for the Shizuku-delivered timer tool (CVE-2026-98260). It is a
Kotlin-side sibling of the native route, not a `route` branch: the native exploit never runs the
timer tool, and the native wire never carries this block (the same profile with and without it
produces a byte-identical native document). A profile without the block is native-only and the
timer route is unavailable.

A profile generated on the device may carry the block too: the parse path runs the timer analyzer
against the same image and merges the emitted block only when the app's parser accepts it
(`docs/analysis/timer-block-generation-plan.md`). A real-only block is legal and leaves the timer
route unavailable (a way-2 run needs the fake group).

```hocon
timer_98260 {
  version = 1
  timer_site_lower_21bit = 507136
  timer_object_size = 264
  cred_request_size = 168
  cred_alloc_size = 192
  cred_uid_offset = 4
  timer_node_offset = 120
  page_alloc_gfp_offset = 20
  page_alloc_gfp_size = 4
}
```

| Field | Type | Meaning |
|---|---|---|
| `version` | int | Always `1`; the block evolves without touching `schema_version` |
| `timer_site_lower_21bit` | int | `do_timer_create`'s `kmem_cache_alloc(posix_timers_cache)` return site, masked to 21 bits as the trace scanner compares it |
| `timer_object_size`, `cred_request_size`, `cred_alloc_size` | int | SLUB sizes: `posix_timers_cache` object size, `cred_jar` request, `cred_jar` allocation |
| `cred_uid_offset` | int | `cred.uid` offset |
| `timer_node_offset` | int | `k_itimer.it.cpu.node` offset (optional; default `0x78`) |
| `static_timer_site`, `task_alloc_site_lower_21bit`, `task_alloc_size`, `task_cred_offset`, six data symbols | fake only | The fake-path constants; the data symbols are kernel VAs and **must be quoted hex strings** (`"0xffffffc00a2293d0"`), because a HOCON/JSON number cannot carry them |
| `cred_usage_size`, `cred_caps_offset`, `cred_caps_count`, `cred_security_offset`, `cred_user_offset`, `cred_user_ns_offset`, `cred_ucounts_offset`, `cred_group_info_offset` | fake only | The fake cred's layout: where the fabricated object carries its fields. `cred_ucounts_offset` exists only where the cred has `ucounts` (5.10 omits it, and `init_ucounts` with it) |
| `security_sid_offset` | fake only | The `sid` offset **inside the LSM blob** the fake cred's `security` pointer names — not a cred offset |
| `page_alloc_gfp_offset`, `page_alloc_gfp_size` | int, int | Where the raw `mm_page_alloc` record carries `gfp_flags`: `0x14`/`4` on 5.10, `0x18`/`8` on 6.x. The reclaim's frag/slab classifier reads it; a wrong layout silently disables the discriminator |

Rules: unknown keys inside the block are an error; the real-path five are always required. The
block carries no mode key: the fake ending is declared structurally, so **any** fake key makes the
whole fake set (including the flat cred keys) required, and a block without them supports the real
ending only. `0` is
written only when it is the real value (a 5.10 cred has no `ucounts`, so
`init_ucounts`/`cred_ucounts_offset` are omitted there); every `cred_*_offset` must fall inside
`cred_request_size` (with the caps run fitting) and the fake path's `task_cred_offset` inside
`task_alloc_size`; `static_timer_site`'s
low 21 bits must equal `timer_site_lower_21bit` (the same image site — a disagreement would derive
a bogus KASLR slide); `timer_site_lower_21bit` and
`task_alloc_site_lower_21bit` are 21-bit; `timer_object_size`/`cred_request_size`/`cred_alloc_size` must agree with the
measured `/proc/slabinfo` lines when those are present.

The app renders the merged, validated profile as the canonical backend document
(`ProfileDocument`): top-level `release`/`schema_version`/`kernel_major`, a `kernel` block (the
shared geometry), a `native` block (route/fallback/pipeline/execution) and this block. The timer
tool reads only `release` and `timer_98260` and re-checks its own invariants; the native keeps its
binary wire in this version. 64-bit values are `"0x..."` strings in the document.

### 4.10 The module chain's pair manifest (`module`)

An optional top-level block for the module chain (DirtyFrag without W1, a per-kernel module as the
SELinux switch, Magisk as the userspace manager). It is a Kotlin-side block: the native exploit
never reads it and the native wire never carries it (the same profile with and without the block
produces a byte-identical native document). It is the only declaration of the bundle-only pair — a
bundled profile and the hand-built `.ko` it names, validated together. There is no separate asset
index.

The block and the `pipeline` entry imply each other: `pipeline = [ "module" ]` requires the block,
and a block without the entry is refused as inert.

```hocon
module {
  version = 1
  asset = "modules/5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284.ko"
  asset_sha256 = "…"                       # 64 hex characters
  vendor_files = [                         # optional; absent = the built-in probe list
    "/vendor/lib64/libbinderdebug.so",
    "/vendor/lib64/libstagefrighthw.so",
    "/vendor/lib64/libstagefright_aidl_bufferpool2.so",
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `version` | int | Always `1`; the block evolves without touching `schema_version` |
| `asset` | string | The bundled artifact, relative to `assets/`: `modules/<name>.ko`; no absolute path, `..` segment or empty segment |
| `asset_sha256` | string | 64 hex characters; the app refuses an artifact that does not hash to it |
| `vendor_files` | list | Optional ordered `vendor_file` targets; absent = the app's built-in list. The first that exists, passes a bridge read and holds the module is used. Each is an absolute `/vendor/…` path shorter than the stub's 64-byte target field (63 bytes plus NUL); an explicitly empty list is an error |

Rules: unknown keys are an error; `version`, `asset` and `asset_sha256` are required; a `module`
entry without the block and a block without the entry are both refused.

The image's module ABI (vermagic, `sizeof(struct module)`, the `module_layout` CRC) is deliberately
not a profile key: the build gate pins the exact Image config and the loader enforces the ABI (a
wrong magic half or CRC is a clean `ENOEXEC`), while `asset_sha256` binds the exact artifact. The
one non-clean loader outcome — an undersized `struct module` — is prevented at authoring time by
building from the Image's exact config; the app cannot read the running kernel's ABI (no BTF on
5.10, no config access), so a profile-side copy could not be checked against it.

The app resolves the pair before any delivery: it opens `assets/<asset>` and verifies the hash,
then picks a `vendor_files` target by probing. The block is rendered in the canonical backend
document (`ProfileDocument`) so a round-trip preserves it; an imported profile still cannot
authorize the chain (bundle-only).

## 5. execution tuning (advisory)

Every `execution` value is advisory. The app merges them into the profile and
passes the result to native; see [defaults.md](defaults.md) for semantics and
defaults. The common groups come from `execution-tuning.conf` and the per-route
groups from `execution-<route>.conf`; the app resolver (and the Gradle exporter)
load these presets directly, so device profiles no longer `include` them:

- `recommended_cpus`: the wire's CPU-pair fallback. It is not authored here and
  not shown in any editor; the app writes `0/1`, and the native chooses the
  pair at run time.
- `heap`: KernelSnitch search attempt counts and timeouts
- `race`: route race wait / settle / poll intervals
- `stages`: W1/W2/W3 attempt counts and settle times
- `routes.tcp_zerocopy` / `routes.select_stack` / `routes.multicast_waiter`:
  per-route retry and wait parameters, in `execution-tcp-zerocopy.conf` /
  `execution-select-stack.conf` / `execution-multicast-waiter.conf`. When
  composing the native document, Kotlin fills in missing route groups with the
  defaults, so native always receives a complete `routes` object
- `handoff`: root handoff and KernelSU load polling

## 6. Validation and feedback

Validation happens in Kotlin (`AndroidProfileConfigController.validateProfileFields`):

1. It checks the common fields and the selected route against the matrix in
   section 3. Missing (`null`) or zero required fields and out-of-range
   combinations (credential / multicast bounds) are recorded in
   `ProfileConfig.invalidPaths`.
2. On the override pages, invalid entries are shown with a red label (unfilled
   counts as invalid); overridden and valid entries are yellow.
3. `fallback.to` must be `"none"` or a valid route name. When a fallback is
   declared, the target branch's required fields are validated as well (for
   example, a `select_stack` fallback needs
   `fallback.route.select_stack.waiter_shift`; 0 is valid).
4. The home-screen **Run** button is disabled while `invalidPaths` is non-empty;
   tapping it asks you to fix the red entries. Even if you bypass that,
   `runExploit` blocks before starting native and writes to the log.
5. Native no longer validates geometry; it only parses the v2 binary and
   executes the component selection and fields it was given.

## 7. Load layers and storage locations

| Layer | Source | Location | Written by |
|---|---|---|---|
| shared | shared values; the resolver/exporter load the tuning presets, device profiles `include` the core ones | `execution-tuning.conf` / `execution-<route>.conf` / `credential-6x.conf` / `kernelsnitch-6x.conf` | shipped with the app |
| builtin | exact `uname -r` match; no match means unsupported | `assets/kernel_profiles/*.conf` | shipped with the app |
| imported | parsed/imported offsets (same release entry) | `filesDir/offsets.conf` | Parse OTA / import config |
| general override | `execution.*` | the release entry in `filesDir/offsets.conf` | override page "general parameter override / reset" |
| route override | `route` / `fallback.to` | the release entry in `filesDir/offsets.conf` | advanced override page "route / fallback" |
| advanced override | any numeric path (sparse HOCON text) | internal `debug_profile_overrides` | advanced override (auto-saved) |
| snapshot | the fully merged HOCON | `filesDir/<release>.conf` | after any override is saved, before export |
| manual built-in source | another real release (dangerous) | internal `debug_builtin_release` | parameter page "load another built-in (dangerous)"; overrides stay bound to the device release |

Export: writes the merged snapshot for that release (one HOCON file, includes
already merged, tuning trimmed to the selected routes) into a folder you pick
via SAF.

## 8. Template profiles (`*-template`)

- One per kernel family: `5.15-template` / `6.1-template` / `6.6-template` /
  `6.12-template`, registered in `index.conf`, strictly for **development and
  debugging reference**.
- Every geometry/offset field is `null` (unfilled) and `route` / `fallback`
  show the full branch structure; each field has a Chinese comment you can copy
  from.
- The built-in picker's "templates (reference, unfilled)" section can load them
  to inspect the field structure. Templates **never participate in device
  matching and never auto-fall-back**: a device with no exact match and no
  imported offsets is treated as unsupported.
- A template carries only core fields (plus the core `include`s); execution
  tuning is provided by the resolver from `execution-*.conf`, so copying a
  template needs no tuning `include`. Synchronized copies live in
  `docs/kernel_profiles/templates/`; the originals under assets can be viewed
  with adb.

## 9. Native transport and parsing

Runtime configuration crosses as a **typed binary struct** (v2, object sections),
no longer JSON text. v2 is the only version: Kotlin and native are version-bound
and there is no legacy decode.

- Kotlin serializes it from `NativeProfileDocument` via `toBinary()`:
  a 16-byte little-endian header
  `u32 magic(0x0D000721) + u16 version(2) + u16 frontend + u16 backend + u16 middleware + u16 release_len + u16 reserved`,
  then the `release` text, then `u16 section_count`, then per section
  `u8 name_len + name + u32 entry_count`, then per entry `u8 key_len + key + u64 value`.
  Presence is carried by key occurrence (an omitted field differs from a provided 0),
  values are raw u64 bit patterns (signed values use two's complement) and are never
  clamped, only the active route's `route.*` section is written or accepted, unknown
  sections/keys are ignored and duplicate keys are last-wins. `middleware` carries the
  route. The authoritative section/key tables are `profile/binary.cpp` (`kSections`);
  the Kotlin object sections in `NativeProfile.kt` must follow them exactly.
- Transport path: direct and Shizuku both hand the profile to native on
  **stdin** (`--ghostlock-app-call`); nothing is written to `active-profile.bin`
  and `--profile` no longer exists.
- Native has exactly one decode path: `profile/entry.cpp` hands the stdin (or
  file) bytes to `profile/binary.cpp::parse`; it detects no format other than
  the magic and has no JSON fallback.
- Internal storage and "export config" are HOCON, schema v3 (human-readable).
  An older document is refused, never converted (section 11).
- Runtime route and capability decisions (`TargetProfile::route()`,
  `TargetProfile::supports()`, `route_capability`) are all based on the decoded
  route.

## 10. Checklist for changing configuration

1. Every profile carries every field of its active route plus the shared
   geometry, and each such field must be present. A value the image or device
   cannot supply is written as an explicit `null` (never `0` as a placeholder,
   unless 0 is the real value). Fields of routes other than the active one (and
   its declared fallback) are omitted. `ghostlock-extract --format conf` emits
   this complete skeleton; `null` keeps the field visible and editable in the
   app instead of silently absent.
2. `route` has exactly one branch, and that branch must carry the route's
   required fields. When `fallback.to` names a target, fill the
   `fallback.route` branch the same way.
   Pull shared core values in with `include` instead of copying:
   `credential-6x.conf` (6.x credential template) and `kernelsnitch-6x.conf`
   (6.x collisions). Execution tuning (`execution-tuning.conf` /
   `execution-<route>.conf`) is loaded by the resolver as a preset; don't
   `include` it in a device profile.
3. Change `execution` only with device measurements; otherwise keep the
   defaults.
4. Verify locally: `make native-host-tests` (profile decode/validation vectors)
   and `./gradlew :app:assembleDebug`.
5. When renaming or regrouping fields, update `FieldLabels.kt` +
   `values*/strings.xml` and, when relevant, `docs/kernel_profiles/defaults*.md`.

## 11. Earlier schema versions (retired 2026-11-22)

Schema **v3 is the only accepted profile format**. The following are gone, not
converted:

- **v1 / v2 profiles** (`schema_version = 1` or `2`): refused on import and on
  load with a `schema_version` error. The app writes every document as v3.
- **Unversioned `remote/main`-era JSON reports** (`symbols` / `struct_fields`
  flat offsets): no longer converted. `ghostlock-extract --format json` still
  emits that report shape for external tools, but it is not a profile.
- The legacy converter, the automatic schema upgrade of imported copies, and
  the v0→v1→v2 migration tests are removed with it.

To move an older profile forward, regenerate it from its image with this build
(`ghostlock-extract --format conf`) or export it from an app build that still
reads it; the result carries `schema_version = 3`.

