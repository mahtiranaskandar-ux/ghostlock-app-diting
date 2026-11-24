# Profile schema cleanup — L-level plan (schema v3, pre-release)

**Status:** in progress. The transport is implemented and device-proven; the
authored-shape migration is the remaining slice. See *Implementation status*
below.

**Base:** `fix/ui-behaviour-2026-11-23` @ `18641467`; cut the work branch after that merges to
`document`.

**Branch:** `feat/profile-schema-cleanup` (work is on the base branch while it is unmerged).

## Implementation status (2026-11-24)

Implemented on `fix/ui-behaviour-2026-11-23` (commits `1a002862`..`38aaa949`):

| slice | state |
|---|---|
| Native JSON transport (decision 19) | **done**: `src/core/profile/json.{c,h}`, `json_profile.cpp`, `timer/profile_fields.c`; the GLK1 wire deleted; `cmp_disasm` control identical; device-proven on 5.10/6.1/6.6. |
| App transport render | **done**: `ProfileTransport` = the resolved tree plus `mode`/`safe_mode` and the native execution shape; `profile.json` sidecar + sha256; Shizuku frame unchanged. |
| Authored schema (profile-core) | **done**: `ProfileResolver` validates the one-document schema (fixed key sets, null-not-absent, verdict-derived ladder, engine/carrier selection, anchor containment, complete carriers, W1 facts); `ProfileMerger` nests the tuning under `ghostlock.execution`; `PerDevicePhys` selects `devices.<id>.phys`; `Timer98260`/`ModuleBlock` read the cleaned blocks. |
| App layer | **done**: the controller resolves/validates/renders the cleaned tree, the picker enumerates `ghostlock.syscalls` × carriers, the measured key is `kernel.heap.stride`, generated exports are stored cleaned, and the generation path's presence/anchor checks read the v3 document (`devices.<id>.phys`, `ghostlock.anchor`). |
| Bundled assets | **done**: all 74 converted (one-off Python); the ship now renders each asset from the store's `document` (no converter), `--check` reads the cleaned shape (61 bundled, 0 missing, 0 mismatched, 12 exempt). |
| Extractor | **done**: one document builder (`src/document.rs`) serves both conf renderers and the store's `document`; `render_facts`, `parse_conf_blocks`, `Superset.profile` and the legacy cut API are deleted. Extractor suite 165 + the store corpus. |
| Consumers | **done**: the ship composes the cleaned render with the store-only facts (devices, stride, timer block and its shared symbols, the credential sid); the app's `LegacyProfileShape` is deleted, a non-cleaned document is refused, the on-device cut and the timer composition emit the cleaned shape. |
| Store + timer (decision 20) | **done**: the store entry carries `document` (the cleaned tree) instead of the old-keyed `profile` map; **the envelope itself is schema-clean (2026-11-24, later)**: `document.devices` carries every device pair (the merge harness widens it; 19 pairs that lived only in `devices[]` moved in), `devices[]` became `device_analysis` (per codename: `phys_source` + `candidates` + `plans`; the pair is not repeated), the duplicated top-level pair and `candidates`/`plans` are gone, the envelope `schema_version` is 3, and the ship no longer widens devices (all 60 assets re-render byte-identically). The whole store was regenerated (60 entries changed, the excluded sidecar-less 6.6.102 entry converted by hand); `report::render_facts`, `parse_conf_blocks`, `Superset.profile` and the legacy cut API are deleted; the timer analyzer emits the cleaned fragment (`kernel` facts + `timer`) and `tools/sweep_timer_store.py` merges it into every store entry (`timer_analysis` keeps the evidence); the ship renders each asset from the store document (no extractor re-run, no conversion), `--refresh-all` rewrote all 60 store-derived assets, `--check` reads 61 bundled / 0 missing / 0 mismatched / 12 exempt; the app's `OptionSuperset` clones the document and selects route/carrier/anchor/device, and its timer merge is a deep merge (`TimerBlockText` and the mapping deleted). The store was regenerated from the images (the corpus `options_ondevice`/`structs_ondevice`/`codegen_ondevice` tests are green). |
| Docs | **done**: `PROFILE_SCHEMA.md` + `_ZH` rewritten to v3; the four docs templates and the four annotated asset templates rewritten; `defaults`, `README`, `MEDIATEK`, `BUNDLED_PROFILES` key references updated; `FieldLabels` maps the new dotted paths (53 new strings, EN+ZH). |
| Measured overlay (decision 13) | **done**: `MeasuredOverlay` applies the root-measured `kernel.heap.stride` to the resolved map, never the document; `nativeDocumentWithOverlay` renders it and both run paths log `overlay: <path> document=… effective=… (source)` and write the same provenance to the run-store meta. The generation flow **writes its accepted stride into the exported document** (`kernel.heap.stride`, so a generated profile is self-contained) and records the same value as weaker evidence (`generation-probe`, excluded from `profileFacts()`: never overlaid, never merged; a root `/proc/slabinfo` fact is ground truth and never replaced). A profile whose ladder needs W1 (ghostlock or dirtyfrag) must carry a non-null stride — the validator refuses a null at import, run and export; module-only and timer-only documents may leave it null. The generation flow's in-memory **probe frame** is the one exception: the probe measures the stride, so `nativeDocumentFor` validates it with `requireStride = false` (the device pass caught this — a null-stride frame used to make the probe refuse before it could run). A store load drops retired keys (`kernelsnitch.mm_struct_sz`) so only the declared set is merged. Unit gates: overlay apply/ignore/immutability, the overlaid render, the probe-vs-root rule, the meta survival, the retired-key prune, generation materialization, the import refusal. |
| Leftover cleanup | **done**: the generation path's old-key checks fixed; the v2 model island (`NativeProfile.kt` 959 lines, `Profile.kt`, the five route-config classes, the typed-slot models, the three wire tests), `completeProfileFields`, `uncarriedRouteKeys`, `OffsetMatching` and the dead `credential-*`/`kernelsnitch-6x` fragments deleted; stale comments/readmes corrected; the root harness/tooling reads `profile.json` and the v3 store. Tests 318/0. |
| Device gate (903) | **diting build 903**: the generation flow parses the stock 236 boot image + the diting `uefi.img`, the stride picker shows 960 `已确认` (root-measured), Use writes `kernel.heap.stride = 960` into the exported document, and importing that file is accepted (`外部 · select_stack`); a null-stride copy is rejected naming `kernel.heap.stride` with the previous selection kept (no imported copy). A fresh-boot run reads `state=completed`, `pipeline=dirtyfrag`, `RESULT=OK script=0 enforce=1`, `overlay=kernel.heap.stride:960->960(root-slabinfo)`, `guard_bad=0 guard_moved=0`. **POCO (6.1.138, build 903)**: store-document asset `bundled, authoritative`, `pipeline=ghostlock`, 16 clean calls, `completed`. **OnePlus (6.6.66, build 903)**: store-document asset completed; the DirtyFrag preflight now refuses its cave (`remainder 640 < stub 732`, the stub grew 2026-11-22) so the pre-W1 fallback ran GhostLock. **diting build 897 (overlay)**: fresh boot, run store `state=completed`, `overlay=kernel.heap.stride:960->960(root-slabinfo)` in the meta, the log line `overlay: kernel.heap.stride document=960 effective=960 (root-slabinfo)`, `dirtyfrag: T+2422ms RESULT=OK script=0 enforce=1`, and the measured DB pruned to the current keys; **build 899 (post-cleanup)**: fresh boot, `state=completed`, the same overlay meta, `guard_bad=0 guard_moved=0`. Build 893 proved the store-document assets (`resolved profile loaded`, `pipeline=dirtyfrag`, `state=completed`, the native default offset); build 887 the cleaned assets; build 875 the transport on the POCO 6.1.138 and OnePlus 6.6.66. |

Remaining:

1. The generation UI flow (parse → stride/lock-source → export) has unit coverage but no device pass after the v3 cleanup; run it on the diting (the plan's U6) with the next build.
2. The POCO and OnePlus rigs have not run the store-document assets (they proved the transport on build 875).
3. The pre-release release cut (r12).

## 1. Why

The current v3 document is not clean in six measurable ways (audit of the 74 bundled assets):

| finding | evidence |
|---|---|
| One fact, up to four serialisations | authored HOCON; `ProfileDocument.render`'s `kernel{}`/`native{}` backend JSON; the store's options JSON; the binary wire. Each renames fields (`profile.cred.caps_count` is a string in one, a number in the other). |
| Duplicated facts with no cross-check | ≈300 timer-block fields duplicate `cred`/`task_struct` (61 profiles); 2 copies already disagree in `5.15.194` (`caps 48/3` vs BTF `40/5`). 38 route branches repeat the waiter layout; the anchor triple is repeated per branch. |
| Three address encodings | image-relative decimal (`offset.init_task`), signed decimal VA (`cred.ref0_image = -272688810528`), quoted hex VA (`timer.selinux_state`) — and the same symbol appears twice (`selinux_state` vs `selinux_enforcing`, exactly `image_base` apart). |
| Dead / incompatible keys | `routes` (never used), `fallback` (the native never executes it), `recommend_shizuku`, `safe_mode`, `Auto`, block-local `version`, 640 explicit nulls emitted inconsistently with the schema's own "omit unused" rule. |
| One key, two axes | `pipeline` mixes the chain (`dirtyfrag`/`ghostlock`), the delivery (`module`) and the backend (`timer`); the picker models the timer as a fake route named `timer_98260`. |
| Measured facts handled two ways | the DirtyFrag probe is auto-reused by the resolver; the `mm` stride needs a manual merge/export. |

## 2. Decisions (settled with the operator)

| # | decision |
|---|---|
| 1 | Thorough cleanup; the document stays **schema v3** (never released) |
| 2 | Top level: `devices{}` + `kernel{}` + one block per engine (`ghostlock`, `timer`) |
| 3 | No route fallback; every declared branch lives in the engine that runs it |
| 4 | No chain list: `kernel.dirtyfrag` is the static verdict; the app's ladder is DirtyFrag first (when allowed), then GhostLock; `kdp` forces DirtyFrag only. The module is a delivery: `ghostlock.module` replaces `ghostlock.syscalls`. |
| 5 | Quoted hex addresses; decimal struct/stack offsets, sizes, counts; booleans; word enums |
| 6 | No platform-family key; the phys rule stays on the running device's SoC |
| 7 | The `mm` stride stays inline (`kernel.heap.stride`) |
| 8 | Carriers are nested blocks inside a syscall; the blocks present are the carriers |
| 9 | Derive `kernel_major`; drop block versions, `recommend_shizuku`; `kernelsnitch` → `kernel.heap` |
| 10 | Engine blocks own their layout and selection (`syscall`, `carrier`, `syscalls`, `anchor` — which carries `scratch` —, `module`, `execution`) |
| 11 | Vocabulary: engine block / syscall / carrier; "route" leaves the document |
| 12 | No engine selector: the app's display order decides; the first option is the default; a stored choice wins |
| 13 | Measured facts are an automatic overlay (see §3.3) |
| 14 | No absent state: a key with no value is `null`, never omitted (see §3.2) |
| 15 | `w1_scratch` is a field of the anchor block: `ghostlock.anchor { image, bytes, stride, scratch }` |
| 16 | The `5.15.194` cred layout is re-derived from the image's BTF and adopted (expected `caps 40/5`); the rewriter must not choose between the copies |
| 17 | An unknown key under `ghostlock.execution` (or `execution.routes.<route>`) is an import error |
| 18 | A timer-only document is the full fixed shape with nulls (§3.2) |
| 19 | **Every backend reads the same JSON document.** The GLK1 binary wire is deleted (encoder, decoder, digest, and the planned cut); the native decodes the render directly, through the reader the timer tool already has (§4.6) |
| 20 | **Every artifact that carries profile facts uses the v3 document.** The test-kernels store's `profile` map (old-keyed facts) becomes `document` (the cleaned v3 tree, route-superset form); the timer analyzer emits the cleaned fragment (`kernel.*` facts + `timer`) instead of a `timer_98260` block. The store→asset and analyzer→document conversions are deleted; composition is a deep merge of documents plus the measured overlay (see §4.5.1) |

## 3. The target document

### 3.1 Example (the real 6.1.134 Neo7 asset, converted)

```hocon
schema_version = 3
release = "6.1.134-android14-11-o-gd252f36df142"

devices {
  RE6022L1 { phys { load = "0x40000000", offset = "0x40000000" } }
}

kernel {
  image_base = "0xffffffc008000000"
  symbols {
    init_task = "0x201fbc0"
    init_cred = "0x2032028"
    root_task_group = "0x2208580"
    empty_zero_page = "0x2201000"
    selinux_state = "0x225afd8"        # was offset.selinux_enforcing
    selinux_blob_sizes = "0x1558a60"
    security_hook_heads = "0x1558350"
    nf_logger = "0x2012f88"
    loggers_0_1 = "0x2012ed8"
    boot_id = "0x237e188"
    panic_on_warn = "0x200b2c8"
    root_user = "0x2030d00"
    init_user_ns = "0x2030a70"
    init_ucounts = "0x2032588"
    init_groups = "0x2032020"
  }
  struct {
    task { prio = 132, …, cred = 2104, seccomp = 2304 }
    cred {
      copy_size = 128
      usage { offset = null, size = 4, value = 1 }
      uid = 4
      security = 120
      security_sid = 4
      caps { offset = 40, count = 5, mask = "0xffffffffffffffff" }
      refs {
        user       { offset = 128, image = null }
        user_ns    { offset = 136, image = null }
        ucounts    { offset = 144, image = null }
        group_info { offset = 152, image = null }
      }
    }
    waiter { shape = compact, pi_tree = 24, task = 48, lock = 56, wake_state = 64 }
    rt_mutex { size = 32 }
    selinux_state { enforcing = 0, initialized = 2 }
  }
  heap { stride = 1024, collisions = 4 }
  dirtyfrag = infeasible
  kdp = false
}

ghostlock {
  syscall = sigreturn
  carrier = null
  anchor {
    image   = "0x22568b8"
    bytes   = 4096
    stride  = 32
    scratch = "0x2256900"
  }
  syscalls {
    select_stack     = null
    sigreturn        { native { waiter_off { plain = 232, sve = 232 } }, compat = null }
    multicast_waiter { native = null, compat { waiter_off = 168, buffer_size = 260, futex_abi = time64 } }
    tcp_zerocopy     = null
  }
  module = null                     # module-only images: module { … }, syscalls = null
  execution = null
}

timer {
  trigger    = killable
  match_bits = 21
  sites {
    timer      { offset = "0x585510" }
    task_alloc { low_bits = "0x1c75f0" }
  }
  object_size     = 264
  task_alloc_size = 4800
  node_offset     = 120
  cred { request_size = 176, alloc_size = 192 }
  trace { gfp { offset = 24, size = 8 } }
}
```

Variants (reviewed in `/tmp/sesame-schema-review/shapes.conf` during the design):
module-only (`kernel` all-null except `dirtyfrag = unknown`, `ghostlock.module`, `timer = null`);
timer-only (`ghostlock = null`, `devices = null`, only the timer facts non-null); KDP
(`kdp = true`, `dirtyfrag = feasible`, the ladder is DirtyFrag-only).

### 3.2 The null principle

Every key the shape declares is present; a key with no value is `null`. A fixed vocabulary:
`kernel.symbols` names every symbol (null when the image does not export it); `ghostlock.syscalls`
lists the four known branches (null when infeasible); a branch states both carriers (null for the
one it does not declare). `null` means "no value" and the consumer's compiled default applies. The
merge already ignores incoming nulls (`deepMergeValues`), so a fully expanded document does not
shadow the presets or the builtin. The validator is strict: a missing key in a fixed block is a
schema error.

### 3.3 Measured facts (automatic overlay)

Declared measurable set: `kernel.heap.stride`, and only from a root `/proc/slabinfo` measurement
(match release). The generation flow writes its chosen value into the exported document instead;
its DB record (`generation-probe`) is weaker evidence, visible in the measurements screen, and is
excluded from both the overlay and the merge. The DirtyFrag probe is the ladder's viability input
(the measured-DB scoped exception), not a document value. A matching root measurement replaces the
document's value **in the resolved map**, never in the document; the run log and store meta record
the effective value and its source; a static-vs-measured disagreement is logged as an extractor
defect. A document whose ladder needs W1 must carry a non-null stride; the validator refuses a
null one at import, run and export (module-only and timer-only documents do not spray and may
leave it null). The merge preview + export stays, as the way to materialize the effective facts
into a self-contained document. Nothing outside the set can be overlaid.

## 4. Implementation

### 4.1 profile-core

- `ProfileResolver.kt` — rewrite validation for the new shape:
  - block constants and fixed key sets (`devices`, `kernel`, `ghostlock`, `timer`, the symbol
    vocabulary, the four routes, the carrier pair);
  - cross-block rules: at least one engine; `ghostlock` ⇒ `syscall` names a non-null branch and
    `carrier` is null or a block of that branch; exactly one of `syscalls`/`module` is non-null;
    `kdp` ⇒ `dirtyfrag` ∈ {feasible, unknown}; the timer's fake group; multicast/sigreturn require
    `waiter.shape = compact`; an unknown key under `ghostlock.execution` (or
    `execution.routes.<route>`) is an error;
  - the anchor block's containment check (the only physical-layout rule that moves): a `scratch`
    inside `image .. image + bytes` must leave room for the 0x18-byte collateral window; a scratch
    outside the range is a second region and is allowed;
  - keep the physical-layout group checks (cred bounds, waiter ordering, `rt_mutex` vs anchor
    stride) on the new paths;
  - **delete** the global `KnownTopLevel`/`DeprecatedTopLevel` list, `pipelineOrder`,
    `requiresW1`, `W1RequiredTopLevel` (the W1 set moves inside `ghostlock`), `nativeValue`'s
    synonym table, the route/fallback search, `validateCompatCarrierGroup`, the carriers list, and
    the `compact_waiter` vs `waiter_layout.compact` comparison (the latter is the only copy now).
- `PerDevicePhys.kt` → reads `devices.<id>.phys`; a null half is the compiled default; an unknown
  device is a refusal; the map/scalar/null three-shape and key-set rules go.
- `ProfileMerger.kt` — drop `mergeRouteObjects` and the `fallback` special cases; deep merge on the
  new tree; `fillRouteExecutionDefaults` → `ghostlock.execution`; identity checks unchanged.
- `ProfileDocument.kt` — delete the `kernel{}`/`native{}` re-nesting; the backend document is the
  resolved map (one shape).
- `RouteCarriers.kt` — delete (carrier blocks are the declaration).
- `ModuleBlock.kt` — `ghostlock.module`, `sha256`; version dropped.
- `Timer98260.kt` — read `timer` plus the shared `kernel.symbols` / `kernel.struct.cred`
  (deduplicated overlay); `match_bits`; `sites`.
- `ProfileExporter.kt` — export the resolved document as HOCON; no mapping table, no wire.
- **Delete** `NativeProfile.kt`'s GLK1 encoder and its digest (§4.6); the render is the transport.
- **New** `MeasuredOverlay.kt` — pure overlay + provenance (facts in, effective map + records out).

### 4.2 App data layer

- `AndroidProfileConfigController.kt` — resolve/validate on the new shape; candidates become
  `(engine, syscall?, carrier?)` with the timer candidate without the `timer_98260` pseudo-name;
  display order and the stored per-engine choice; `completeProfileFields` emits the fixed shape;
  import dialog paths/labels; the single render (`renderTimerDocument` becomes the one transport,
  with the tool's VAs computed as `image_base + offset`); `injectPhysMaps` → `devices`; the merge
  preview writes `dirtyfrag`/stride.
- `AndroidGhostlockRepository.kt` — remove the pipeline list from the run path: the ladder
  (DirtyFrag if the effective verdict allows it, then GhostLock; KDP forces DirtyFrag; module when
  `ghostlock.module` is non-null); the measured overlay for `dirtyfrag` and the stride; provenance
  lines in the log and the run-store meta; the native input is the rendered JSON document (§4.6);
  the DirtyFrag probe recording unchanged; `meta.pipeline` still fed (1/2) from the chosen chain.
- `PipelineResolver.kt` — delete (the ladder replaces it); its unit test becomes a ladder test.
- `MeasuredProfileStore.kt` — key paths move to `dirtyfrag` / `heap.stride`; the overlay API
  (fact + ROM/protocol match + provenance). The DB is app-local and starts empty for the new
  package, so no migration.
- `TimerOnlyDocument.kt` — emit the full fixed shape with nulls (the timer's facts only).
- `TimerBlockText.kt` — delete: it exists because the old single-route cut never carried the timer
  block and the export had to append its text (`GhostlockViewModel.kt:2864`). The resolved document
  carries `timer` itself.
- `Profile.kt` — the resolved model, unchanged in role. `NativeProfile.kt` and
  `NativeProfileDigestTest` are deleted (§4.6).

### 4.3 App UI

- `RouteMatrixDialog.kt` — engine section (ghostlock/timer), syscall and carrier rows from the
  non-null branches, and the DirtyFrag row gated by the effective verdict rather than a chain list.
- `HomeScreen.kt`, `GhostlockViewModel.kt`, `SupportStatus.kt`, `AdvancedUI.kt` — source labels
  and the candidate model; `dirtyFragSelectable` reads the effective verdict.
- `FieldLabels.kt` — the new dotted paths for the import dialog.
- Measurements — effective vs document for the two measurable facts.

### 4.4 Extractor (Rust)

- `tools/extract_rs/src/report.rs` — the new `--format conf` render: the four blocks, hex
  image-relative symbols, `image_base`, the `struct` groups, `heap`, `dirtyfrag`, `kdp`, the
  branch/carrier catalogue with nulls, the timer block as offsets.
- `tools/extract_rs/src/derive.rs` — emit `image_base`; the link base the timer VAs were already
  built on; keep the send-path verdict as `dirtyfrag`.
- `tools/extract_rs/src/options.rs` — the options document's profile section to the same shape
  (the superset facts the ship script consumes).
- `tools/timer_extract/src/report.rs` — the timer block as offsets (no cred overlay, no
  `version`).
- Tests: the render assertions and the corpus (`options`, `cut`, `route_verdicts`, `codegen`,
  `families`) against the regenerated store.

### 4.5 Store and ship

- `tools/ship_golden_profiles.py` — render the new shape from the document; `dirtyfrag` from the
  `pipelines` verdicts; the module block; nulls; the measured stride; the `--check` compares the
  new asset bytes.
- The store: regenerate each entry's profile facts with the updated extractor (deterministic from
  the in-hand images), then re-apply the hand-curated additions (device pairs, module manifests,
  the A16 KDP cell); review the per-entry diff.
- **`5.15.194`**: re-derive the cred layout from the image's BTF during the regeneration (the
  expected answer is `caps 40/5`; today's asset ships `48/3`) and adopt it. The regeneration must
  not pick between the two copies silently — this is the one entry where the resolution is
  recorded by hand, in `meta.txt`. Benign in value (the two skipped words are zero in
  `init_cred`), but wrong for the native's caps write.
- The 4 reference templates (`5.15/6.1/6.6/6.12-template.conf`) rewritten.

### 4.5.1 The store carries the document (decision 20)

The store entry (`external/test-kernels/<release>/options-<release>.json`) keeps its
analysis envelope — `routes`, `pipelines`, `pipeline_facts`, `pipeline_order`,
`image_base`, `device_analysis[]` — and replaces the old-keyed `profile` map with
`document`: the cleaned v3 tree the CLI renders for that image (the route-superset
form: all feasible syscalls with their carriers, the shared anchor/scratch,
`devices.<codename>.phys` for **every** device the entry derives,
`kernel.heap.stride = null`, the timer symbols null, `timer = null`). The envelope
itself is schema 3 (2026-11-24, later): the pair has one home (the document) and
`device_analysis[]` keeps only the evidence that produced it.

Producers and consumers:

- **Extractor** (`tools/extract_rs`): `options::build` constructs the document tree
  from the same facts `--format conf` already uses; the CLI renders it to HOCON with
  one generic renderer. `report::render_facts`, `parse_conf_blocks`, the
  `Superset.profile` map and the legacy cut API (`cut`, `cut_plan`, `render_cut`)
  are deleted; `cut_ondevice` with them.
- **Timer analyzer** (`tools/timer_extract`): the block it emits is the cleaned
  fragment — `kernel { symbols { panic_on_warn, root_user, init_user_ns,
  init_ucounts, init_groups }, struct { cred { security_sid, refs } } }` and
  `timer { … }` with image-relative offsets — for both `--emit-timer-conf` and the
  store overlay's `block`. The `timer_98260` spelling leaves the profile surface
  (the overlay object keeps the analyzer's evidence fields).
- **Ship**: an asset is the store `document` plus the store-only composition —
  `kernel.heap.stride` from the measured entry (the device pairs are already in the
  document), the timer fragment merged — then the header. `--check` compares the asset tree to that
  document tree directly. `expected_facts`, `_expected_clean_document`,
  `cleaned_timer`, `sync_timer_block`, `render_timer_block` and the
  `migrate_profile_shape.convert` call are deleted; `--sync-facts` (which inserted
  old-keyed blocks) goes with them.
- **App** (`OptionSuperset`, `AndroidGhostlockRepository`): the on-device extractor's
  `document` is the picker's base; `cut` clones it and applies the chosen
  syscall/carrier, the plan's anchor/scratch, the device pair, the pipeline and the
  presets — no block assembly. The timer merge is a deep merge of the analyzer's
  fragment; `mergeTimerBlock`'s mapping and `TimerBlockText` are deleted.
- **Regeneration**: `generate_options` writes the new envelope (it preserves the
  timer overlay, which must be regenerated once with the cleaned analyzer). The
  per-entry diff is reviewed; hand-curated facts (device pairs, module manifests,
  KDP) live outside `document` and are untouched.

### 4.6 The transport: one JSON document for every backend

Decision 19 deletes the GLK1 v2 wire. Today the app renders canonical JSON for the timer and a
bespoke byte stream for ghostlock; from this release both are fed the same
`ProfileDocument.render(resolved)` document, and each reader takes the blocks it owns and ignores
the rest.

**Deleted:** `profile-core/src/main/kotlin/com/sesame/app/data/NativeProfile.kt` (959 lines:
encoder, digest, presence bookkeeping), `src/core/profile/binary.{cpp,h}` (690),
`NativeProfileDigestTest`, the wire halves of `NativeDocumentEquivalenceTest` /
`ProfileRoundTripTest` / `RouteCandidatesTest`, and the planned `ProfileCut.kt` is never written.

**The shared reader — two layers, because the existing timer reader already has them:**

- `src/core/profile/json.{c,h}` — the tree parser promoted from `src/timer/profile_json.c`: the
  arena, `MAX_DEPTH 32`, `MAX_VALUES 4096`, the 1 MiB cap, no duplicate keys, integers, `0x…`
  strings above 2^63, `null`/`true`/`false` (today's lines 65–527, plus `obj_get`/`value_u64`).
  The typed accessors and the strict per-block key check (`check_block_keys`) become public.
- `src/core/profile/json_profile.cpp` — ghostlock's mapper: tree → `profile::kernel_offsets`. It is
  the same name → field table the GLK1 decoder carries today, so the table moves and only the
  tokenizer changes. It derives `kernel_major` from `release` (decision 9).
- `src/timer/profile_fields.c` — the timer's mapper: the current `timer_profile_parse` body,
  rewritten to `timer` / `kernel.symbols` / `kernel.struct.cred`.
- Both binaries link `json.c`. Build: the ghostlock `Makefile` swaps `core/profile/binary.cpp`
  for `core/profile/json_profile.cpp` and adds `core/profile/json.c` to `C_SRCS` (the `.c` rule and
  `-Icore` already exist — `dfprobe.c` uses them); the timer's rule gains `-Icore`; `json.h` carries
  `extern "C"` guards. A new `src/core/tests/json_test` replaces `profile_binary_test` in
  `native-host-tests` (today's `profile_json_test.c` is built by no target at all).

**Transport mechanics.** `read_glk1_stdin` / `read_glk1_file` / `read_glk1_frame_stdin` become
`read_profile_*` over JSON; the Shizuku frame keeps its length prefix and carries JSON bytes (the
AIDL signature stays `ByteArray`, so the service's binder surface does not move);
`--load-prebuilt-profile` takes a JSON file (no profile `.bin` ships; the two `assets/*.bin` are
DirtyFrag blobs); the timer tool keeps `--profile <file>` (the service already writes
`timer-profile.json`). `pipeline_check` keeps its cross-check — the render states the
resolved chain for a run, as today.

**Sidecars and the log line.** `dumpRuntimeProfile` writes one `profile.json` — the exact bytes
handed over — instead of `profile.conf` + `profile.bin`, and the run log quotes its sha256 where it
quotes the wire digest today (`logProfileDigest`). That sidecar is the device test's evidence
channel (§6.1).

**Native strictness.** Each mapper refuses an unknown key in a block it owns — the app validator's
rule (decision 17) applied at the native edge. This is what replaces the digest's key-drift
detection, and it is testable with no run (§6.1 T3).

**Presence and the digest.** The wire's "key omitted ≠ provided 0" is now "null ≠ value": a null
key leaves the field unset in `kernel_offsets`, so the compiled default applies — the same rule the
document states (§3.2). `meta.recommend_shizuku` and `meta.fallback_route` are never written and
keep their 0 defaults. The wire's `kDigestMismatch` is replaced by `schema_version` plus strict
unknown-key rejection (decision 17); the digest only ever caught app/native skew inside one APK,
and that is the one protection this drops deliberately.

**The equivalence gate.** Freeze the **decoded `kernel_offsets`** for all 74 bundled profiles from
the current branch (a host-test dump produced through today's wire path), then require the new path
(asset → rewriter → resolver → render → `json_profile`) to reproduce it. Compare *effective* values
(the native's fallback applied), not optional presence, so "was absent, now stated as the same
value" is not a difference. The enumerated, reviewed exceptions:

1. `5.15.194`'s caps move `48/3` → `40/5` (§4.5).
2. `fallback_route` is gone (decision 3). It was never executed — only read by the
   non-compact-waiter guard (`src/core/attack/ops.cpp:106`) — and the six profiles that declared
   `fallback.to = select_stack` now carry that branch in `ghostlock.syscalls`, where it is also
   selectable (an intended UI change for those six).
3. Values the new document states unconditionally (the waiter shape, `rt_mutex.size`, the cred
   `usage`/`uid`/`security` fields, `selinux_state`) now always arrive; behaviour-neutral iff equal
   to the default the old path applied, which the gate asserts per family (uid 4 / security 120 /
   usage 4 on 5.x; the release-derived waiter and SELinux layouts elsewhere).

Every difference outside the list fails the gate.

### 4.7 Timer tool

The tool keeps reading the document the app renders; what changes is the reader's split (§4.6) and
its key names (`timer`, `kernel.symbols`, `kernel.struct.cred`). The app still computes the tool's
VAs (`image_base + offset`), so the tool's arithmetic is unchanged. `TimerBlockText.kt` and
`renderTimerDocument` follow. The loader's only gate becomes the shared `src/core/tests/json_test`
(a rendered 6.1.134 document through the timer mapper) — today's `profile_json_test.c` is built by
no target, and that gap closes here for both readers.

### 4.8 Docs and templates

`docs/kernel_profiles/PROFILE_SCHEMA.md` and `_ZH.md` rewritten for the new shape and the null
principle; `defaults.md` (the execution block path); the store entry READMEs that quote keys; the
asset header comments (`# fact_sources:` stays a comment).

## 5. Migration

1. **Freeze the decoded-profile golden** from the current branch for all bundled profiles — the
   native decoder's `kernel_offsets` per asset (§4.6).
2. Land profile-core + the shared native reader + tests against the *existing* assets through a
   one-shot `tools/migrate_profile_shape.py` that mechanically converts the old HOCON to the new
   shape: symbols hex, `image_base` derived from `timer VA − offset`, the timer overlay removed, the
   `dirtyfrag` verdict from the store's `pipeline_facts` with the old `pipeline` as fallback,
   `syscall` set to the old exporter's default route (the first viable alphabetically) so the
   decoded `route` does not move, a `fallback.route` branch moved into `ghostlock.syscalls`, and
   nulls for absent values. The rewriter is a verification aid, not the shipping path: every
   difference from the regenerated assets is listed and reviewed.
3. Regenerate the store profile facts and re-apply the hand-curated bits; `--check`; diff review.
4. Pre-release v3 rejection: a document carrying the retired top-level keys (`route`,
   `kernelsnitch`, `pipeline`, `offset`, `fallback`, …) is refused with one message
   ("pre-release v3 layout; regenerate with this build") rather than a list of unknown-key errors.
5. App data/UI, the ladder, the overlay, the timer mapper.
6. Docs, templates, device pass, release cut.

No document, preference or measured-DB migration is needed: the package rename
(`com.sesame.app`) starts all three empty, and an external file from the fork's pre-release builds
is refused with the message in step 4. The installed rigs are upgraded by install, not by data
carry-over.

## 6. Gates

- `:app:testDebugUnitTest`, `:profile-core:test`, `:app:assembleDebug` — including the new
  `execution` unknown-key rejection, the null-shape validator, and the overlay provenance tests.
- The decoded-profile equivalence golden (74 profiles, with the enumerated differences of §4.6 —
  no difference outside the list).
- Extractor `cargo test` + the store corpus (`options`, `cut`, `route_verdicts`, `codegen`,
  `families`) against the regenerated store; `ship_golden_profiles.py --check`.
- Native: `make -C src ghostlock`, `make -C src timer`, `lint-tidy`, `native-host-tests` (with the
  shared `json_test`: a rendered document through both mappers, plus a malformed-input corpus —
  truncated, deep, duplicate keys, unknown keys, oversize — each refused cleanly, never a crash).
  `cmp_disasm` is run as a control: the attack functions' instruction counts must not move (the
  profile reader is a separate call path); any change fails the gate.
- Device pass: §6.1 (one run per fresh boot; every run classified with the run-failure table
  before it is read as a verdict).
- No profile's support claim changes; only `5.15.194`'s caps value moves.

### 6.1 On-device test design

The attack code is untouched, so the device question is not "does the exploit still work" but
**does the native receive the same facts on a real device, on every family** — and does the new
parser fail cleanly. The three rigs pin three different mappings:

| rig | kernel | what it pins |
|---|---|---|
| diting (`cb7548c8`) | 5.10.236, QTI | the no-BTF 5.10 template (cred, the release-derived waiter/SELinux layout), the DirtyFrag ladder, the measured overlay, both transports (direct + Shizuku), the timer route |
| POCO X6 Pro (`FYPRFEG685EQP7LJ`) | 6.1.138, MTK | BTF-derived facts, the MTK phys rule, the four-branch catalogue, tcp/select, the **timer-only** document shape |
| OnePlus 13s (`e9b70795`) | 6.6.66 | the non-compact `rt_waiter_node` shape, the ladder selecting DirtyFrag end to end, the guarded-route refusal |

**T0 — provenance.** On every rig: versionCode, `lastUpdateTime`, and the packaged
`libghostlock.so` **and `libtimer.so`** md5s against HEAD (`libtimer.so` carries the timer mapper —
a stale tool would read new keys as unknown). Recorded before any run; the upgrade is
`adb install -r -d` with prefs and run-store listed before and after.

**T1 — the load path, one run per rig.** Fresh boot, the canonical harness
(`scripts/sigret-run-once.sh`: cpu-active hold, cpuset gate, live-bounds 执行 tap). Pass: the native
prints `resolved profile loaded: <release>` (the mapper's success line), the run store ends in a
terminal state, and the run's `profile.json` sidecar (debug export on) hashes to the sha256 the log
quotes. The stage reached is classified with the run-failure table — a `+0x188`, a W2 miss or a
phantom kill is a known class, not a schema signal. **One run proves the load path, not a rate**;
no rate claim is made.

**T2 — both transports (diting).** One direct run and one Shizuku run. Pass: the same T1 line from
each, plus the Shizuku ACK handshake succeeding (the frame now carries JSON with an unchanged
`ByteArray` signature). The OnePlus has no Shizuku and the POCO's arm is optional, so this is
diting-only.

**T3 — the decoder's refusals (device, no run).** The native takes `--load-prebuilt-profile <file>`
and refuses before any attack when the document is bad. Copy the packaged binary to
`/data/local/tmp` (it cannot be executed from the app's lib dir) and run it from `adb shell` — a
refusal only reads the file, so no root is needed — then push files and read the messages:

| input | expected |
|---|---|
| the rig's own rendered `profile.json` | **not run here** — a valid document proceeds into the attack; T1 covers it |
| the same with an unknown key in `ghostlock` / `kernel.struct.*` | refused, naming the key |
| truncated JSON | refused as malformed |
| today's GLK1 `profile.bin` | refused — no silent misparse of the old format |
| a valid document with another `release` | refused by the existing uname check |
| (OnePlus) a document selecting `multicast_waiter` with the non-compact waiter | refused by the route/waiter-shape guard (`src/core/attack/ops.cpp:106`) |

Each refusal must be a non-zero exit with one message, never a crash — and the last row is the
device half of the guard that the app validator mirrors. This is the cheapest real-device test of
the new code and the one that would catch a parser defect before a run does.

**T4 — the ladder and the overlay.** OnePlus: the ladder picks DirtyFrag from the effective verdict
and the log names the source (`static` / `probe` / `db`), ending `RESULT=OK script=0 enforce=1`.
Diting: seed the measured DB with a stride and a dirtyfrag record → the log and the merge preview
name the source and the effective value; clear the record → the document's value is used. Pass is
the provenance line, not the run outcome.

**T5 — generation → import → run.** Diting: parse `external/boot.img` (md5 `ab88fe15…`) on-device,
save; the exported document must be the new shape and its image-derived facts must equal the host
extraction (the ten offsets and the anchor), with the stride the picker reports on this rig (960);
import it → Home Ready → a T1-line run. POCO (MTK): skip the physical-pair prompt so generation
emits the **timer-only** document (§3.2) → import → the route picker offers only the timer → one
timer run through Shizuku, which exercises the timer mapper's new keys and the app's `image_base` VA
arithmetic. This is the only arm that feeds the timer block a real document.

**T6 — import rejection (diting).** Import a pre-release asset → the one-message rejection (§5.4);
import a document missing a fixed-block key → a schema error naming the path; import the same file
twice → stable. After each refusal the previous selection is intact (the existing transactional
rule).

**T7 — unreachable, recorded as gaps.** The module-only document (no 5.4 unit in hand) and the KDP
cell (no A16 unit). Neither is a pass line.

**Rig hygiene.** No flashing anywhere. The POCO is a daily device: upgrade only (`adb install -r
-d`), no clearing; every route choice, external selection and measured record a test touches is
restored from a pre-state dump. Runs are scheduled last, one per fresh boot, with the pstore
capture available (`CAPTURE=1`). Shizuku arms run only where the service is installed (diting,
POCO).

**Evidence.** This is an attack-path-adjacent change, so the records are archived, not ephemeral:
`external/port/profile-json-<date>/` with per-run `summary.txt`, the deciding log excerpt, the
run's `profile.json` sidecar (text, so it travels), and the T3 refusal outputs. Builds do not.

**Pre-registered fail rules.** Any of these fails the gate: (i) a rig whose app reports Ready but
whose native never prints the load line; (ii) a native crash or app `FATAL EXCEPTION` in T1–T6;
(iii) a T3 refusal without a message, or any T3 crash; (iv) a T5 export differing from the host
extraction outside the §4.6 exception list; (v) an import refusal that leaves the old selection
changed. A run that stops in a known class is reported as such, not as a pass.

## 7. Risks

| risk | mitigation |
|---|---|
| Store regeneration loses hand-curated fields (device pairs, module manifests, KDP) | the rewriter's diff per entry; `--check`; review each changed entry |
| The JSON mapper reads a value differently | the 74-profile decoded golden; an enumerated difference list |
| Null expansion changes merge behaviour | `deepMergeValues` already ignores incoming nulls; a merge test with a fully-expanded document |
| The ladder changes which chain runs | the ladder is the uniform policy the bundle already carried; device logs name the choice; KDP/probe gates unchanged |
| The new reader is on the run path | the strict bounded reader (fixed caps on depth, value/item counts and the string arena; duplicate and unknown keys refused), shared with an already-shipped consumer, the malformed corpus, `cmp_disasm` as a control |
| The timer tool breaks | the shared reader's host test with a rendered document; the app computes the VAs |
| An old pre-release profile imports silently | the explicit retired-key rejection (§5.4) |
| A rig is unavailable, or a run stops in a known failure class | T1's pass is the load line, not the run's outcome; T3 needs no run; an unavailable rig is recorded as a gap, never a pass |
| UI churn (engine/candidate model) | the route-matrix test and a device check of the sheet |

## 8. Decisions resolved in review

1. `w1_scratch` lives inside the anchor block: `ghostlock.anchor { image, bytes, stride,
   scratch }` (decision 15).
2. `5.15.194` is re-derived and adopted at `caps 40/5` (decision 16).
3. An unknown `execution` key is an import error (decision 17).
4. A timer-only document is the full fixed shape with nulls (decision 18).
5. Every backend reads the same JSON document; the GLK1 wire is deleted (decision 19, adopted
   after review).

## 9. Sequence and effort

| slice | work | est. |
|---|---|---|
| 1 | profile-core schema + the shared JSON reader + the native mapper + the decoded golden + the rewriter | 2–2.5 d |
| 2 | app data: resolver, ladder, overlay, controller, store | 1.5 d |
| 3 | extractor + store regeneration + ship + `5.15.194` | 1.5–2 d |
| 4 | app UI + timer mapper + docs/templates | 1 d |
| 5 | device pass (§6.1, three rigs) + release cut (build, NAS, notes) | 1–1.5 d |

Commits (one per slice, `feat(profile): …`), the branch flow per AGENTS.md. The exploit's attack
code is untouched, but its profile input is new: `cmp_disasm` is run as a control, and the decoded
golden in §4.6 is the review surface.

## Appendix A — key map (abbreviated)

| today | new |
|---|---|
| `kernel_major` | derived from `release` |
| `kernel_phys_load/offset` maps | `devices.<id>.phys.load/offset` (null = default) |
| — | `kernel.image_base` |
| `kdp` | `kernel.kdp` (boolean) |
| `pipeline` | `kernel.dirtyfrag` + the ladder (no list) |
| `route.<r>.lock_anchor_{image,bytes,stride}` + `w1_scratch.image` | `ghostlock.anchor.{image,bytes,stride,scratch}` |
| `route.<r>.carriers` / `compat_carrier` / geometry | `ghostlock.syscalls.<r>.<carrier>.*` |
| `route.<r>.compact_waiter`, `.task_offset`, `.lock_offset` | `kernel.struct.waiter.*` |
| `fallback`, `routes`, `recommend_shizuku` | gone |
| `offset.*` | `kernel.symbols.*` (hex); `selinux_enforcing` → `selinux_state` |
| `task_struct.*` | `kernel.struct.task.*` |
| `cred.*` (`ref0..3`, `ref_count`) | `kernel.struct.cred.*` + named `refs` |
| `waiter_layout.*` | `kernel.struct.waiter.*` (`compact` → `shape`) |
| `kernelsnitch.*` | `kernel.heap.*` + `kernel.struct.rt_mutex.size` |
| `selinux.*` | `kernel.struct.selinux_state.*` |
| `module` | `ghostlock.module` (`asset_sha256` → `sha256`) |
| `timer_98260.*` | `timer.*`, with `cred`/`task`/symbols deduplicated into `kernel.*` |
| `execution` | `ghostlock.execution` |
| `schema_version` / `release` | unchanged |
