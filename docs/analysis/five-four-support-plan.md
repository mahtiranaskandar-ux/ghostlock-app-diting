# 5.4 support plan — SELinux W1 layout, page-free, and the KernelSnitch page path

**Status 2026-11-20: S1–S6 implemented and gated; S8 implemented, host-gated and
device-gated (the diting page-based control chose order 3 on 9/9 leaks with the compiled
geometry); S7 (the venus reporter run) remains; no 5.4 device run yet.** The operator approved the plan on
2026-11-20. Gates run: extractor `cargo test` (all targets), the venus/star renders (venus
in-anchor `0x32F7200`, star second region `0x335A000`), native zero-warning build,
`lint-tidy`, `native-host-tests` (including the scan-grid phase tests and the S8
`slab_order_test`), profile-core +
app unit tests, `assembleDebug` (versionCode 764), ship `--check` 61/0/0/12 (the store's
current exempt count; the 5.4 images are not bundled), HoconCheck on both updated profiles,
a parent-commit `cmp_disasm` A/B (every target's instruction count identical; the FAIL is
the known missing-`multicast_*` control plus two layout-shift annotations), and the QEMU
W1 layout gate passing on both 5.4 Images (venus/star; evidence
`external/port/five-four-w1-gate-2026-11-20/`).

**Targets.** The two local non-diting 5.4 candidates:

| kernel | device | image | profile |
|---|---|---|---|
| `5.4.274-qgki-g76ac0b265f44` | Xiaomi Mi 11 (`venus`, SM8350) | `external/rom/venus-OS2.0.6.0.UKBCNXM/` | `external/port/venus-5.4/profile-5.4.274-qgki-g76ac0b265f44.conf` |
| `5.4.233-qgki-gbb70cde46897` | Xiaomi Mi 11 Pro (`star`, SM8350) | `external/rom/star-V14.0.13.0.TKACNXM/` | `external/port/star-5.4/profile-5.4.233-qgki-gbb70cde46897.conf` |

Neither kernel is bundled; both profiles are hand-completed candidates. The venus is the
only reachable device (the reporter's, via the `5.4.274-qgki-g76ac0b265f44` report), so the
pre-device gate is QEMU and the real gate is that report's next run.

## 0. What this plan fixes

1. **The W1 SELinux write targets the wrong byte on 5.4.** The write target is the
   `selinux_state` symbol + 0, and on 5.4 `enforcing` is at **+1** (`disabled` occupies
   byte 0 even with `CONFIG_SECURITY_SELINUX_DISABLE` off). The stored value's byte 1 is
   never zero (the page value is `page+0x100` → `0x01`; the venus scratch would be `0x73`),
   so W1 cannot disable SELinux on either 5.4 image — page or page-free. Fix: target =
   symbol + the profile's `selinux.enforcing` field offset (a key the profile already
   carries but the native never reads), and `selinux.initialized` is the byte of the
   *value* that lands on `initialized` (initialized − enforcing).
2. **The extractor has no 5.4 selinux layout template**, so it emits no `selinux` block and
   no `w1_scratch`; the venus falls back to the page path and dies in KernelSnitch. Fix: a
   measured 5.4 template and the picker's value-relative semantics. The venus then becomes
   page-free with `w1_scratch = anchor + 0xF8` (`0x32F7200`), and never runs KernelSnitch.
3. **The star's in-anchor scratch refuses, but the second-region lane works.** Its
   `dump_skip.zeroes` base gives an even value byte 2 for every admissible offset in the
   4 KiB region; the lane the 6.1.138 POCO precedent built (`options.rs`'s auto chain,
   `--w1-scratch-symbol`) finds `serial8250_ports.llvm.* + 0x50` (image `0x335A000`) and
   the conditioned `s3c24xx_serial_ports + 0xA0` (image `0x2ED1700`). The extractor must
   resolve the LTO clone name (S1), and the 8250 premise stays a device fact with its
   kprobe gate.
4. **KernelSnitch.** The venus page-free route removes the leak from that device's path.
   For the page path this plan carries (a) a conditioned second scan phase over
   `[48 GiB, the model end)`, including the cached-bank tail, and (b) the slab-order rule:
   the order is a runtime function of the boot's possible-CPU count (1 vCPU: order 1/8
   objects; the phone's 8 vCPUs: order 3/34), and the app cannot read it — S8 derives it at
   runtime (rule prior + leak vote, no cache), not as a profile key.
5. **The 5.4 row joins the trusted release fallback.** The native already derives the
   selinux layout from the release when a profile has no `selinux` block (initialized 2
   below 6.6, 1 from 6.6). 5.4 joins that table as a measured row — `enforcing = 1`,
   `initialized = 2` — so a 5.4 profile without the block resolves exactly like every other
   family's; the block is an override on every family, and no family gets a stricter check.
   The original bug was a missing row, not a missing refusal.

## 1. Evidence

### 1.1 The 5.4 `selinux_state` layout — measured on both images

All addresses are file offsets (`VA − _text`), disassembled with
`external/port/five-four-tools/disasm.py` against each image and its QEMU kallsyms dump.

| image | function | evidence | pin |
|---|---|---|---|
| venus | `sel_write_enforce` (`0x6A6054`) | `ldrb w22,[x21,#1]` at `0x6A6100`; `strb w8,[x21,#1]` at `0x6A61D4` | enforcing at **+1** |
| venus | `selinux_init` (`0x2A589D4`) | `strb w8,[x19,#1]` at `0x2A58A34` (enforcing boot param), `strb w8,[x19,#2]` at `0x2A58A48` (checkreqprot) | enforcing +1, checkreqprot +2 |
| venus | `selinux_set_mnt_opts` (`0x698E40`) | `adrp x8,0x30A1000; ldrb w8,[x8,#3]` at `0x698EAC` | initialized at **+3** |
| venus | `selinux_sb_clone_mnt_opts` (`0x69BD50`) | `ldrb w8,[x24,#3]` at `0x69BD74` | initialized +3 |
| venus | `security_load_policy` (`0x6B4730`) | `strb w9,[x20,#3]` at `0x6B4958` — the `selinux_mark_initialized` store | initialized +3 |
| star | `selinux_set_mnt_opts` (`0x6A7F0C`) | `adrp x8,0x30CB000; ldrb w8,[x8,#3]` at `0x6A7F7C` | initialized +3 |
| star | `selinux_init` (`0x2A69074`) | `strb w8,[x19,#1]` at `0x2A690D4`, `strb w8,[x19,#2]` at `0x2A690E8` | enforcing +1, checkreqprot +2 |

So on both 5.4 kernels the struct is:

```
selinux_state + 0   disabled        (present even with CONFIG_SECURITY_SELINUX_DISABLE off)
selinux_state + 1   enforcing
selinux_state + 2   checkreqprot
selinux_state + 3   initialized
```

Both configs: `CONFIG_SECURITY_SELINUX=y`,
`# CONFIG_SECURITY_SELINUX_DISABLE is not set`, `CONFIG_SECURITY_SELINUX_DEVELOP=y`,
`CONFIG_SECURITY_SELINUX_CHECKREQPROT_VALUE=0`.

**Why 5.10 is `(0, 2)` instead.** In the 5.10 tree the `disabled` field is guarded by
`#ifdef CONFIG_SECURITY_SELINUX_DISABLE` (`security/selinux/include/security.h`), so with
DISABLE off the struct starts at `enforcing` and `initialized` is at 2. On these 5.4 builds
the `disabled` byte is present regardless, shifting every later bool by one. The 5.10
template therefore does not transfer, and the 5.4 template must be a separately measured
release template.

**Why the file cannot be read.** `selinux_state` is BSS at the very end of each image
(venus `0x30A1000`, star `0x30CB000`), so there are no bytes to inspect; the evidence is
the reader/store disassembly above (and, on a booted QEMU guest, the runtime bytes).

### 1.2 The current profile values

`symbols.rs` maps the key `off_selinux_enforcing` to the **`selinux_state` symbol**; both
5.4 profiles carry `offset.selinux_enforcing = selinux_state + 0`:

- venus: `50991104` (`0x30A1000`, comment "= `selinux_state+0` (DISABLE off)")
- star: `51163136` (`0x30CB000`, same comment)

The `+0` was inferred, not measured: §3.6 of each port README is a *symbol* table from the
QEMU runtime, with the field offset assumed from the 5.10 fact.

### 1.3 Why the gap stayed silent

Three layers filled the missing fact with the 5.10 assumption instead of refusing:

1. **Extractor** — `selinux_layout()` (`tools/extract_rs/src/main.rs:262`) has exactly one
   no-BTF template, `5.10 → (enforcing 0, initialized 2)`. Everything else returns `None`,
   which suppresses the `selinux` block and the `w1_scratch` (fail-closed for the scratch)
   but does **not** mark `offset.selinux_enforcing` unverified.
2. **Native** — `TargetProfile::selinux_enforcing_offset()` (`src/core/profile/model.h:561`)
   defaults to 0 and is **never called**; `selinux_initialized_offset()` returns the
   profile's value or a release default (2 below 6.6, 1 from 6.6). The W1 target
   (`cve_2026_43499_backend.cpp:793`) is `data_alias(selinux_enforcing())` with no field
   offset.
3. **Kotlin** — `ProfileResolver.validateStage1Blocks` requires `selinux.enforcing == 0`
   ("must be 0", `profile-core/.../ProfileResolver.kt:297`), encoding the 5.10 layout as a
   rule.

And no 5.4 W1 ever ran: the venus report died in KernelSnitch before W1, the star was never
device-gated, and the 5.4 QEMU gates exercised the futex-hash leak and the route geometry,
not `selinux_state`.

### 1.4 Why byte 1 matters — the W1 value arithmetic

W1 stores `value = source alias + 0x100` at the target and relies on byte `k` of the value
landing at `target + k`.

- Page path: `value = page+0x100`, page is order-3 (or order-1) aligned, so `value` low 16 =
  `0x0100` → byte 0 = `0x00`, byte 1 = `0x01`.
- Derived scratch: `value = base+s+0x100` with `s` chosen so byte 0 = 0; byte 1 is the
  base's byte 1 plus the `s` carry.
- With the current struct-base target on 5.4, byte 1 lands on `enforcing` and is never 0
  (`0x01` page, `0x73` venus scratch) → SELinux stays enforcing; the W1 verify fails after
  every attempt.

## 2. Design

### 2.1 One coherent definition of the three selinux facts

| profile fact | meaning | 5.10.236 | 5.4.274 / 5.4.233 | 6.6.x |
|---|---|---|---|---|
| `offset.selinux_enforcing` | image offset of the `selinux_state` **symbol** | symbol | symbol | symbol |
| `selinux.enforcing` | byte offset of `enforcing` inside the struct | 0 | **1** | 0 |
| `selinux.initialized` | byte of the W1 **value** that lands on `initialized` (= initialized_struct − enforcing) | 2 | **2** | 1 |

W1 target and value:

```
target = data_alias(KIMAGE_TEXT_BASE + offset.selinux_enforcing + selinux.enforcing)
value  = source_alias + 0x100              # page base, or w1_scratch image
predicate: value byte 0 == 0               # enforcing = 0
           value byte [selinux.initialized] odd   # initialized stays true
```

The change is a no-op for every profile whose `selinux.enforcing` is 0 (5.10 and 6.x, i.e.
the whole bundle) and for every profile without the block on a measured family.

### 2.2 Extractor (`external/ghostlock-app/tools/extract_rs`)

1. **`selinux_layout()`** (`src/main.rs:262`) returns
   `(enforcing_field, initialized_value_byte, source)`:
   - BTF: `e = field("selinux_state","enforcing")`, `i = field("selinux_state","initialized")`,
     result `(e, i − e, "btf")`. Accept a `disabled` field (a 5.4 BTF image will carry it);
     the guards become `e < 8`, `i < 8`, `i > e` (the old `enforcing != 0` and
     "disabled is present → omit" guards are 5.10-only facts).
   - No BTF: the release template table — `5.10 → (0, 2)`, `5.4 → (1, 2)` (raw struct
     `(1, 3)`; the emitted second value is the value byte, i.e. initialized − enforcing).
     Both are `"release-template"`, the same kind of measured fact; the config premises
     (SELINUX=y, DEVELOP=y, DISABLE unset) are recorded in the `# fact_sources` provenance,
     not used as a 5.4-only gate. A release not in the table stays `None` (fail closed, as
     today).
   - The two-image evidence (venus/star, §1.1) is the template's provenance note.
2. **`named_candidate()`** (`src/anchor.rs:485`) matches the requested name exactly, so the
   5.4 vendor builds' LTO clones (`serial8250_ports.llvm.<hash>`, the star's second-region
   candidate) are invisible to the options scan. Accept exactly one `<name>.llvm.*` clone
   when the plain name is absent (two or more stay ambiguous, fail closed) — the same rule
   the timer extractor got in R1.
3. **`pick_w1_scratch()`** (`src/anchor.rs:629`) keeps its algorithm; its
   `initialized_offset` argument is now the value-relative byte, so its existing predicates
   (value byte 0 = 0, byte `initialized` odd) are exactly right. The doc comment that names
   `selinux_state.enforcing` as "byte 0" is updated.
4. **Call sites** — `main.rs:1287` (options), `main.rs:1549` (profile render),
   `options.rs:378-402` (candidate placements) pass the relative byte; the report
   (`report.rs:447-458`, `560-590`, `659-690`) emits
   `selinux { enforcing = <field>; initialized = <value byte> }` and the matching
   `# fact_sources` lines.
5. **Tests** — the 5.10 render byte-identical; a 5.4 template unit test (1, 2); an unknown
   no-BTF release → `None`; a BTF layout with a `disabled` field → accepted with `(1, 2)`;
   a unique `<name>.llvm.*` clone resolves and two clones stay ambiguous; the two-image
   render check (venus in-anchor `0x32F7200`; star in-anchor `None`, second region
   `0x335A000` from `serial8250_ports.llvm.* + 0x50`).

### 2.3 Native (attack path — this is the L-level hunk)

1. **`runtime_struct_offsets.h:51`** — `selinux_enforcing()` returns
   `symbol_image(...) + profile.selinux_enforcing_offset()`, wiring the dead field offset
   into the W1 target.
2. **`model.h:546`** — `selinux_initialized_offset()` keeps returning the profile's value
   (now documented as the value-relative byte); `selinux_enforcing_offset()` gains a
   release-derived default: the profile value when stated, else `1` for a 5.4 release and
   `0` otherwise. `selinux_initialized_offset()`'s existing release rule already yields 2
   for 5.4 (below 6.6), so the fallback pair is `(1, 2)` with no family-specific refusal;
   the block remains an override on every family.
3. **`payload_builder.cpp:136`** — no change: the page acceptance check already consumes
   the value-relative byte.
4. **Tests** — a `(1, 2)` profile targets symbol+1 and checks value byte 2; `(0, 2)` and
   `(0, 1)` profiles are byte-identical to today; a 5.4 release without the block resolves
   to `(1, 2)` through the release fallback, identical to the same profile with the block.

### 2.4 App / Kotlin / docs

1. **`ProfileResolver.validateStage1Blocks`** (`profile-core/.../ProfileResolver.kt:288`) —
   allow `selinux.enforcing` in 0..7 (not "must be 0"); keep `initialized` in 0..7; add
   `enforcing + initialized < 8` (the value is one 64-bit word). Update the comment that
   says "enforcing is the struct's first byte on every sampled image".
2. **`NativeProfile.kt`** — no wire change: `selinux.enforcing` / `selinux.initialized` are
   already carried. No schema-digest move; the native-document golden is unchanged.
3. **Docs** — `docs/kernel_profiles/PROFILE_SCHEMA.md` and `_ZH.md`: restate the two
   fields' semantics and add the 5.4 row; the port READMEs' §3.6 symbol tables gain the
   field-offset column.
4. **Tests** — profile-core validator tests for `(1, 2)` accepted, `(0, 2)`/`(0, 1)`
   unchanged, `(8, x)` and `initialized >= 8` refused.

### 2.5 Profiles

**Venus** — add to `external/port/venus-5.4/profile-5.4.274-qgki-g76ac0b265f44.conf`:

```hocon
selinux {
  enforcing = 1
  initialized = 2
}
w1_scratch {
  image = 53441024        # 0x32F7200 = lock_anchor_image + 0xF8
}
```

`page_free_reason` then reads `policy+scratch+anchor` (sigreturn is `page_free_capable`),
the reserved window `0x1F8..0x20F` skips anchor slots 15–16, and W1 never calls
`prepare_kernel_page`.

**Star** — add the `selinux` block (`enforcing = 1`, `initialized = 2`) and a
second-region scratch. The in-anchor picker returns `None` for `0x3321148`
(`base = 0x233A1148`, value byte 2 = `0x3A` even for every `s`), but the second-region
lane finds two: `serial8250_ports.llvm.* + 0x50` (image `0x335A000`, value `0x233DA100`,
byte 2 = `0x3D`; the unconditioned class) and `s3c24xx_serial_ports + 0xA0` (image
`0x2ED1700`, in-file zero window; the conditioned class). The profile carries the
`serial8250_ports` one: outside the anchor, so it reserves no slots; the premise — no
8250 device on the phone — is a device fact, and a kprobe readback gates page-free before
the profile claims it.

**Regeneration** — the 5.4 route geometry is hand-derived (the extractor refuses to emit a
family geometry for 5.4), so the profiles stay hand-completed; the new block is produced by
the extractor's partial render (or copied from its values) and checked with `HoconCheck`
plus the app's profile-core validator. The report memo for the venus records that the
candidate now carries the block and the scratch.

### 2.6 KernelSnitch

#### 2.6.1 The venus page-free route

With §2.5 the venus no longer enters `prepare_kernel_page()` at all: no spray, no leak, no
scan range, no slab-order dependency. The venus report's 15/15 leak failures become
unreachable for the supported route. The QEMU leak gate (`external/port/venus-hash-2026-11-20/`)
stays the record that the leak itself is correct; it is no longer on the venus's path.

#### 2.6.2 The page path's scan range — a conditioned second phase

Today `__run_mm_leak_pass()` clips the scan at `min(g_direct_map_end, IDENTITY_END)` = 48 GiB
when no `.ghostlock_iomem` cache exists, and `context_scan()` runs four passes over that
range. The venus suspicion is a bank above 48 GiB.

Design: keep the four existing passes exactly as they are; only when all four miss and
`g_direct_map_end > phase1_ceiling`, run the same four shapes over the uncovered tail
`[phase1_ceiling, g_direct_map_end)` (16 GiB by default; the model end stays 64 GiB).
Ranges are built from the same source (banks ∩ tail when a cache exists, otherwise the tail
split across workers); 48 GiB is 1-GiB (`COARSE_SZ`) aligned, so the seam is clean; add a
`phase=` field to the existing `[kernelsnitch] scan ...` log.

Why conditioned, not unconditional: the scan is early-terminating and the span is split into
`span/thread_cnt` contiguous chunks; widening 48 → 64 GiB changes the chunk from 6 to 8 GiB
and moves the diting's known find (+38.4 GiB) from 2.4 GiB into its chunk to 6.4 GiB —
about a 2.7× first-pass time on the one device that currently succeeds. The fallback costs
zero on the success path and the same +33 % as an unconditional widening on the failure
path. The tail bound stays at `DIRECT_MAP_END` (64 GiB); `PAGE_END` is 256 GiB on
`VA_BITS=39`, so moving there is a separate decision that needs bank evidence.

#### 2.6.3 The cached-bank tail

The same `ceiling = min(g_direct_map_end, IDENTITY_END)` clips measured `/proc/iomem` banks
at 48 GiB, so a rooted device whose banks extend above 48 GiB still misses. Phase 2 is not
gated on `source=span`: with a cache it scans `banks ∩ [phase1_ceiling, g_direct_map_end)`.
`apply_iomem_cache()`'s own sanity bound (`span < DIRECT_MAP_END − DIRECT_MAP_BASE`) stays.

#### 2.6.4 The slab order is a runtime fact (page path)

The venus/star `mm_struct` **stride** is 960 (`object_size 920`, `slab_size 960`) — the same
as the diting. The **order** is not an image fact: SLUB's `calculate_order()` uses
`min_objects = 4 × (fls(nr_cpu_ids)+1)`, so the same Image yields different orders at
different possible-CPU counts. Measured on the venus Image (2026-11-20): 1 vCPU →
`objs_per_slab 8`, `order 1`; the phone's 8 vCPUs → **`objs_per_slab 34`, `order 3`** —
the same 32 KiB order-3 slab as the diting, i.e. the page path's compiled `ORDER3_SIZE`
geometry matches the real devices. The earlier 2026-11-17 QEMU dump (`qemu-runtime.txt`)
booted at 1 vCPU, which is why it read order 1; it is not the phone (`slab-order.txt` in
both port dirs records the 1-vs-8 measurement).

Residual risk: a boot with a different possible-CPU count (e.g. `maxcpus=4`, or a future
SoC) would change the order, and `mm_objs_per_slab`, the base rounding and the stride probe
would then be wrong. The app cannot read the kernel's actual order (`/sys/kernel/slab/*` and
`/proc/slabinfo` are root-only), and the `calculate_order` rule is version-, config- and
cmdline-dependent (`slub_min_order`/`slub_max_order`/`slub_min_objects`, `slub_debug`, vendor
patches) — a pure rule is an unverified prediction, and a wrong order breaks the spray
silently. It is not a blocker for the 5.4 phones: their 8-CPU order is 3, and both devices
now take the page-free route anyway.

**S8 design — derive the order at runtime, no profile key, no cache (implemented
2026-11-20).** Two layers:

1. **Rule prior.** From the stride and `nr_cpu_ids` (`_SC_NPROCESSORS_CONF` /
   `/sys/devices/system/cpu/possible`) compute the expected order with the release's
   `calculate_order` shape. The slub cmdline parameters (`slub_min_order`/`slub_max_order`/
   `slub_min_objects`) are not available to the app — only the root script can read
   `/proc/cmdline` — so the prior is the CPU-count rule alone. The sampled families give
   order 3 at 8 CPUs for strides 960/1024/1280.
2. **Leak vote.** The page path leaks a fresh `mm_struct` right before the spray. For a
   candidate slab size `S = PAGE_SIZE << order`, with `off = leaked & (S-1)`, the candidate
   passes iff `off % stride == 0` and `off + stride <= S`. Simulated over random slab
   alignment (200k draws/cell): the true candidate passes 100 %, a false candidate passes
   with probability `min(S, S_true) / max(S, S_true)` — e.g. true order 3: order 2 0.50,
   order 1 0.24, order 0 0.12; true order 1: order 2 0.50, order 3 0.25. So a **failure is
   decisive** against that candidate, while a pass is not confirmation. Evaluate every
   candidate on each leak and intersect the passing sets across prepare rounds: the true
   order is in every intersection, and a false candidate survives `k` rounds with
   probability `ratio^k`. The prior is kept while it has never failed; after a failure the
   nearest surviving order takes over (a prior order 3 against a true order 1 fails 3/4 of
   the time per sample). One leak cannot identify the order on its own; the round count is
   the §5 Q3 policy question.
3. **No root-cache override (rejected 2026-11-20).** `.ghostlock_slabinfo` has no sound
   invalidation for the order: its only stamp is the release, and the order is not a
   release property (`nr_cpu_ids` and the boot's slub cmdline parameters, which the app
   cannot read to compare); the app also deletes the capture before every measured run
   (`AndroidGhostlockRepository`), so a native start-of-run read is always empty — the file
   exists only mid-run, after the root phase, i.e. after the spray it would have informed.
   The leak vote re-derives the order per run, so the cache adds no validity. The measured
   DB keeps `mm_struct_sz` display-only; no profile or wire field is added.

`prepare_kernel_page()` then uses the voted `S` for `mm_objs_per_slab = S / stride`, the base
rounding `leaked & ~(S-1)`, the send-buffer size/step and the stride probe's modulo. Cost: a
few instructions per leak — no extra leak rounds. One ordering constraint: the spray contexts
are sized from `mm_objs_per_slab` before the leak completes (`prepare_ctxs()` in `util.cpp`),
so a corrected order re-sizes the *next* prepare call; the first call of a run uses the prior.
The non-tcp payload fits any order ≥ 1 (`0x1b88 < 0x2000`); the tcp layout (`0x5800`) needs
order 3, so a voted order < 3 with tcp selected must refuse (the 6.1 phones are 8-CPU/order 3).

This replaces the earlier `kernelsnitch.mm_slab_order`/`objs_per_slab` profile-key proposal:
no schema, wire or store change, and it self-corrects a vendor/cmdline difference the rule
alone would miss once a leak has been sampled. It is a page-path-only, attack-path change,
implemented in `src/core/kernelsnitch/slab_order.h` and wired through `prepare_kernel_page()`,
`prepare_skb_payload()` and `probe_mm_stride()` (`src/core/support/util.cpp`). On the 8-CPU
phones the prior is order 3, i.e. today's compiled behavior, so the run is byte-identical
there. Tests: `src/core/tests/slab_order_test.cpp` pins the prior for 960/1024/1280, the
exact pass counts (`min/max` per candidate) and the vote policy; the diting page-based
control (2026-11-20) exercised the page path (`page_free=0`) and the prior held on 9/9
leaks — every prepare read `slab_order=3 slab_size=32768 objects_per_slab=34`, W1 and W2
verified, and the run then stopped at the known W3 CPU-active class — so the derivation
picks the phone's order and the spray is unchanged.

#### 2.6.5 Star status

The star is page-free through the existing second-region lane, not page-based: the
`serial8250_ports.llvm.*` window resolves the in-anchor refusal. What remains is the
clone-name resolution (§2.2 item 2) so the auto plan lists it, plus the standard 8250
premise (no device on the phone) and a kprobe readback gate. The page path (§2.6.2,
§2.6.4) is then only the fallback if that premise fails on a unit.

### 2.7 Gates

| gate | what | where |
|---|---|---|
| G1 host | extractor unit tests + the two-image render; native build/lint/host suite; profile-core + app tests; `cmp_disasm` for the W1 hunk | the two repos |
| G2 QEMU | a W1 page-free mechanism gate on both 5.4 Images: the guest performs the page-free write at `selinux_state+1` with the derived value (scratch), reads `/sys/fs/selinux/enforce` 1→0; a negative control with the old struct-base target must leave it 1 | five-four-tools harness (new init) |
| G3 device | diting no-regression smoke: the target is identical for `enforcing = 0`; a full chain run | diting rig |
| G4 device (external) | the venus reporter runs the updated page-free candidate: W1 verify + full chain + KSU | the report |
| G5 page path | with §2.6.2 on the diting (a control run, unchanged first phase) | diting rig |

G2 is the pre-device proof that the new target/value actually flips SELinux on 5.4; G4 is
the only end-to-end 5.4 gate available.

## 3. Work items and order

| # | item | files |
|---|---|---|
| S1 | extractor: 5.4 template, BTF guard, relative byte, LTO-clone name resolution, picker doc, tests | `tools/extract_rs/src/{main.rs,anchor.rs,options.rs,report.rs}` + tests |
| S2 | native: W1 target offset, 5.4 release fallback, tests | `src/core/kernel/runtime_struct_offsets.h`, `src/core/profile/model.h`, `src/core/session/backend/cve_2026_43499_backend.cpp` + host tests |
| S3 | Kotlin validator + schema docs + tests | `profile-core/src/main/kotlin/.../ProfileResolver.kt`, `docs/kernel_profiles/PROFILE_SCHEMA*.md` |
| S4 | profiles: venus block+in-anchor scratch, star block+second-region scratch, port READMEs, report memo | `external/port/venus-5.4/`, `external/port/star-5.4/`, `external/github_error_reports/5.4.274-qgki-g76ac0b265f44/` |
| S5 | KernelSnitch phase 2 + bank tail + tests | `src/core/kernelsnitch/kernelsnitch.h`, `src/core/attack/ops.cpp` |
| S6 | QEMU W1 gate | `scripts/`, `external/port/five-four-tools/` |
| S7 | the venus reporter run | external |
| S8 | slab order: runtime derivation (rule prior + leak vote, no cache), no profile key; page path only; implemented, host- and device-gated (diting control 2026-11-20) | `src/core/kernelsnitch/slab_order.h`, `src/core/support/util.cpp`, `src/core/memory/heap_context.*`; `slab_order_test` |

Order: S1 → S2 → S3 → S4 (they ship together; a profile with the new block is refused by
an old app and misread by an old native) → S6/G2 → G3 → S7. S5 is independent of S1–S4 and
can land in parallel; S8 last.

## 4. Risks and rollback

- **Attack-path change (S2).** The only behavioral change is for `selinux.enforcing != 0`;
  every existing profile is 0, so the diting and the bundle are byte-identical. Rollback is
  a revert of one native hunk; the profiles of §2.5 must then be reverted too.
- **The 5.4 template's premise.** It is a measured row for the 5.4 series (both sampled
  images, §1.1), not a config-gated check. An unseen 5.4 build whose config drops
  `enforcing` (DEVELOP=n) would get the wrong fallback; no sampled image does. A config
  premise check applied to every no-BTF selinux template (5.10 included) would be the
  symmetric hardening if that risk ever needs closing — a separate item, not a 5.4 special
  case.
- **Side-effect bytes.** With the enforcing-field target the value's byte 1
  (checkreqprot) and byte 3 (policycap[0]) become nonzero. The 5.10 page path already
  clobbers the same relative bytes (its value is a direct-map address), so this class is
  accepted by the diting's proven runs; the venus scratch's `policycap[0] = 0x23` is the
  same class. If a later gate shows it matters, a second scratch region with a different
  residue is the lever.
- **The venus has no local unit.** G2 is the pre-device gate; G4 is the real one. The star
  remains ungated and candidate.
- **KernelSnitch phase 2 only helps if the range is the cause.** If the venus page path is
  ever needed and phase 2 still misses, the verbose collision set is the next diagnosis.
- **The star's second region is a premise, not a guarantee.** `serial8250_ports` needs no
  8250 device bound on the unit; the page-free claim waits on the kprobe readback, and if
  that premise fails the page path (§2.6.2/§2.6.4) is the fallback.

## 5. Open questions

1. The 5.4 source's exact `struct selinux_state` guards — the disassembly is authoritative;
   record the upstream source (5.4.y `security/selinux/include/security.h`) when available.
2. The star's 8250 premise (no 8250 device on the unit) and the kprobe readback gate
   before its profile claims page-free.
3. S8's vote policy: implemented as "the prior is kept while it has never failed; after a
   failure the nearest surviving order takes over", with the first call of a run using the
   prior. The diting control exercised the keep path only (9/9 the prior held). Still open:
   whether a run whose prior is wrong should take an extra leak round before the first
   spray — today the corrected order applies to the next prepare call (the contexts are
   sized before the leak completes), and the page path is expensive.
4. Whether the venus candidate is bundled after the reporter gate — an operator decision,
   outside this plan.

## Appendix A — the scratch arithmetic (for reference)

```
venus (in-anchor):
        delta = 0xA0080000 − 0x80000000 = 0x20080000
        base  = 0x20080000 + 0x32F7108 = 0x23377108
        s     = (−base) & 0xFF = 0xF8;  s + 0x100 + 0x18 = 0x210 ≤ 0x1000
        value = 0x23377300  ->  byte0 0x00, byte1 0x73, byte2 0x37 (odd), byte3 0x23
        image = 0x32F7200 (53441024), window 0x1F8, slots 15..16
star (in-anchor):
        base  = 0x20080000 + 0x3321148 = 0x233A1148
        s     = 0xB8;  value = 0x233A1300  ->  byte2 = 0x3A (even)
        every s in the 4 KiB region keeps byte2 = 0x3A  ->  picker returns None
star (second region):
        serial8250_ports.llvm.* 0x3359FB0 + 0x50 -> image 0x335A000
        value = 0x233DA100  ->  byte2 0x3D (odd); BSS beyond the file
        s3c24xx_serial_ports 0x2ED1660 + 0xA0 -> image 0x2ED1700
        value = 0x22F51800  ->  byte2 0xF5 (odd); in-file window zero
```

## Appendix B — the current fallbacks this plan retires

| place | today | after |
|---|---|---|
| `selinux_layout` template | 5.10 only, `(0, 2)` | + 5.4 `(1, 2)` (value byte) |
| BTF guard | rejects `enforcing != 0` and a `disabled` field | accepts any coherent `(e, i−e)` |
| native `selinux_enforcing_offset()` | default 0, never read | read; profile fact, else release fallback (5.4 → 1, otherwise 0) |
| native `selinux_initialized_offset()` | profile value or release 2/1 | unchanged semantics (value byte) |
| Kotlin validator | `enforcing` must be 0 | 0..7, `enforcing + initialized < 8` |
| `pick_w1_scratch` input | raw initialized offset | value-relative byte (same algorithm) |
