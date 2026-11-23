# The module + Magisk implementation (5.4) — L-level plan

**Status 2026-11-22: implementation started (bench slice).** This file lives only on
`feat/dirtyfrag-5x`. The new implementation of the same goal the GhostLock pipeline pursues — root
on a locked phone — is for the 5.4 qgki line, where the existing pipelines cannot run: no route is
feasible (G1), and KernelSU cannot be late-loaded (no supported kernel module for a 5.4 KMI). The
new chain uses the DirtyFrag primitive without W1, a per-kernel module as the SELinux switch, and
Magisk as the userspace root manager.

The branch is rebased onto the timer-route `document` (schema v3, 2026-11-23); the profile design
below is the surviving v3-compatible form.

First device results (diting, 5.10.236): **G2a passes** — the existing dirtyfrag pipeline with the
Magisk handoff leaves magiskd running under Enforcing and the manager reports 31.1 installed.
**G2b passes** — the module pipeline delivers a per-kernel module into a **vendor_file** target
through the **crash_dump bridge**, the init stub insmods it as `vendor_modprobe`, the module sets
SELinux permissive, the socket connects and the Magisk handoff runs to `RESULT=OK`, with
Enforcing restored. The profile section below is unchanged by the bench.

**Profile decision (2026-11-23): the module chain earns one top-level `module` block that is the
pair manifest** — the bundled artefact's name and hash and the ROM delivery targets. There is no
`modules/index.conf`: the profile is the only place a pair is
declared. Selection stays in `pipeline`; the block makes the entry viable and supplies its data.
**Schema slice landed (2026-11-23):** `ModuleBlock` (validator + parser), the resolver's
entry↔block coupling, `ProfileDocument` rendering, the profile-core tests, and the EN/ZH schema
section 4.10. The exporter skips a module-only profile (no native wire).

**The 5.4 pairs and the run path (2026-11-23):** the switch modules for star and venus are built
from each Image's exact config (`tools/dirtyfrag_module/build.sh`, vendor clang, the Image's own
CRCs) and load in their Images under QEMU (`finit_module rc=0`, `DFSWITCH … enforcing=0`). Both
ship as `assets/modules/<release>.ko` with a bundled module profile: the app stages the block's
`asset` after the hash check, uses its `vendor_files`, and stages an installed Magisk manager
(`MagiskStage`) for the handoff. The diting bench path is unchanged. The 5.4 DirtyFrag delivery
itself is still device-ungated (no 5.4 unit).

### The module delivery (from the reference, verified against our ROM policies)

The app cannot read `vendor_file` (only `crash_dump`, `vendor_modprobe`, `vendor_init`,
`ueventd` can), so the page-cache write for the module cannot come from the app's own process.
DFRoot's chain:

1. patch a tiny helper into `crash_dump64`'s page cache (the app can read it), then exec it — the
   exec takes the policy's `crash_dump_exec → crash_dump` transition;
2. the helper has two modes over a pipe: **read** 16 bytes of a target file, and **splice** 16
   bytes of the target's page (the app computes the IV and sends the ESP packet, as it does for
   libc++);
3. write the module into a **vendor_file** target this way — DFRoot's candidates are
   `/vendor/lib64/libbinderdebug.so` (present on our phones, 100 KB),
   `libstagefrighthw.so`, `libstagefright_aidl_bufferpool2.so`;
4. the init stub's grandchild writes `u:r:vendor_modprobe:s0` to `/proc/self/attr/exec` and execs
   `/vendor/bin/insmod <vendor target>`; `vendor_modprobe` holds `module_load` on `vendor_file`;
5. the module sets `selinux_state = false` (and, in the reference, calls `call_usermodehelper`
   for its bootstrap; ours leaves the init shell to do the Magisk step).

Both 5.4 policies already carry the exact rows this needs — `(allow crash_dump vendor_file (file
(ioctl read …)))`, `(typetransition domain crash_dump_exec process crash_dump)`, `(allow init
vendor_modprobe (process (transition)))`, `(allow vendor_modprobe vendor_file (system
(module_load)))` — and the G3 preflight verified the first three. The diting has the same rows and
both vendor targets. DFRoot's own support check is GKI-only (5.10+); the 5.4 qgki module is ours to
build, which G4c covers.

Cost: the stripped module is 11 KB → 691 blocks, and the reference spawns `crash_dump64` once per
read and once per splice; that is ~1.4 k short-lived processes, to be measured. The helper and the
vendor target are restored after the module loads (the app already keeps the original blocks for
libc++).

## 1. The chain

```
app          resolve pipeline "module" (section 3) → ROM preflight → primitive probe
             ├─ bridge: patch a helper into crash_dump64, write the module into a vendor target
             └─ build the libc++ page-cache plan, start the DirtyFrag stage    (no W1, no route)
init         libc++ sentry ctor runs the stub → worker child
             ├─ grandchild: attr/exec=u:r:vendor_modprobe:s0, insmod <vendor target>
             ├─ module sets selinux_state = false → permissive                 (the W1 replacement)
             ├─ the app's socket link now succeeds → root script, init domain:
             │     Magisk late install (tmpfs, magiskpolicy --live, magiskd, boot stages)
             └─ restore Enforcing, restore the patched blocks, print the verdict
app          verify: getenforce, magiskd alive, su from an external shell, run store completed
```

**What it removes:** W1 and the whole futex/scheduler class; the native W1 invocation; the route
geometry; KernelSU and its manager scanner for this pipeline; any credential write (KDP is
irrelevant — the second way never forges credentials).

**What it keeps:** DirtyFrag as the primitive (ESP page-cache write, libc++ sentry hook, init
execution, the init shell and its socket), and the per-kernel module idea already gated.

## 2. Why this shape

- **KernelSU late-load is not available on 5.4.** Late-load is a kernel-module load for the exact
  KMI; upstream supports GKI kernels and vendor 5.4 qgki is outside that set. A module can in
  principle be built (the gate shows the loader accepts one), but building a KSU port for 5.4 is a
  product, not a handoff.
- **Magisk needs no kernel module.** It is a daemon, a live policy patch, and mounts. The
  DirtyFrag chain already gives code execution inside init (uid 0, `u:r:init:s0`), which is exactly
  the context Magisk's first stage would have.
- **The module is the SELinux switch.** The live policy patch (`magiskpolicy --load … --live`)
  checks `security:security load_policy`, and **no domain in the shipped star/venus policies has
  that permission** — only the class definition and a neverallow. Under Enforcing the patch is
  denied from every domain. It must run while the device is permissive, and the module
  (`selinux_state = false`) is the cheapest way to get there without W1. This is the load-bearing
  finding: the module and Magisk are not alternatives, they compose.
- **The rest fits the init domain.** On both ROMs init carries `mounton` on rootfs/tmpfs/
  system_file/vendor_file, a broad `property_service set` (so `ctl.restart` works), and
  `sys_admin`/`mknod`/`chown`; `vendor_modprobe` carries `sys_module` + `module_load` for the
  `insmod` step. The Magisk setup must run in init, only the module load drops to vendor_modprobe.
- **Evidence already in hand:** the loader accepts a module built from the exact config on both 5.4
  Images (QEMU) and on the 5.10 and 6.1 phones; the DirtyFrag chain reaches an init shell on the
  diting; the ROM preflight passes on both 5.4 packages. What is not yet measured: the primitive on
  a real 5.4 phone, and the Magisk late install itself.

## 3. Profile representation — additive, pipeline-scoped

### Constraints

1. Every existing profile (61 bundled assets, user imports, snapshots) must parse and behave
   identically.
2. `schema_version` is the document version, now **3** for every profile: the timer work retired
   v1/v2 and added `routes`, `carriers` and `timer_98260` to schema 3 (`document` 2026-11-23). It
   is not a chain version, and the module chain does not bump it.
3. The Kotlin resolver refuses unknown top-level keys and unknown pipeline values — a new document
   read by an old app must fail closed, never be misread.
4. The native wire document is written only for a W1 run. The new chain has no wire; the DirtyFrag
   stage is invoked as `--dirtyfrag <plan>` and reads no profile.
5. A profile is a *kernel-image* document, plus the authored pairing of an implementation that
   cannot be derived. The module chain's artefact is referenced by name and hash — not embedded —
   because the pair is bundle-only and hand-validated; installed managers and runtime choices still
   do not belong in it.
6. **No derivation.** The module pipeline is an authored choice. The extractor never emits it; a
   supported release is a curated pair — a hand-written bundled profile and a hand-compiled,
   bundled module — that was validated together. There is no extractor work in this plan.

### Proposal

**P1 — one new value in the existing ordered list.** `pipeline` already names the chains:
`["ghostlock"]`, `["dirtyfrag"]`, `["dirtyfrag", "ghostlock"]`. Add a third value:

```hocon
pipeline = [ "module" ]
```

It is not a repurposing: no existing value changes meaning, and the resolver's walk (first viable
entry, resolved before any stage starts) needs no new mechanism — only a third viability callback.

**P2 — requirements become pipeline-scoped, not global.** Today `route`, `task_struct`, `cred` and
`offset` are unconditionally required. A module profile needs none of them: there is no W1, no
route, no kernel-memory geometry. Validate as:

- the W1 field groups are required **iff** the list contains a W1-based entry (`ghostlock` or
  `dirtyfrag`); a list of `["module"]` may omit them entirely;
- `route`, when present, is still validated exactly as today (one branch);
- `release`, `schema_version`, `kernel_major` stay required (all three are cheap and are present in
  every authored document);
- the KDP rule widens: `kdp = 1` now accepts the strict `["dirtyfrag"]` **or** `["module"]` — both
  avoid the credential write.

This is conditional validation, not a new document version: every schema-3 document satisfies the
stricter branch unchanged, and a `["module"]` document is still schema 3.

**P3 — the `module` block is the pair manifest (2026-11-23).** One new top-level block carries
everything the chain needs beyond its `pipeline` entry; there is no separate `modules/index.conf`.
Selection stays in `pipeline` — the block makes the entry viable and supplies its data — and the
two imply each other: a `module` entry without the block, or a block without the entry, is an
error.

```hocon
module {
  version = 1

  # The bundled artefact this profile was validated with (relative to assets/).
  asset = "modules/5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284.ko"
  asset_sha256 = "…"                       # 64 hex characters

  # The ROM-side delivery targets, preferred first (optional; absent = the
  # built-in libbinderdebug → stagefrighthw → stagefright_aidl_bufferpool2 list).
  vendor_files = [
    "/vendor/lib64/libbinderdebug.so",
    "/vendor/lib64/libstagefrighthw.so",
    "/vendor/lib64/libstagefright_aidl_bufferpool2.so",
  ]
}
```

`version`, `asset` and `asset_sha256` are required; `vendor_files` is optional; unknown keys are an
error. The validator checks the asset is a relative `modules/<name>.ko`, the hash is 64 hex
characters, and each vendor path is absolute, under `/vendor/`, duplicate-free and shorter than the
stub's 64-byte target field. The block and the `module` entry are present together.

At resolve time the app opens `assets/<asset>` and verifies `asset_sha256`, refusing before any
delivery on a mismatch. The image's module ABI is deliberately not a profile key: the build gate
pins the exact Image config (G4b/G4c) and the loader enforces the ABI — a wrong magic half or CRC
is a clean `ENOEXEC` — while the hash binds the exact artefact. The one non-clean loader outcome
(an undersized `struct module`) is prevented at build time; the app cannot read the running
kernel's ABI (no BTF on 5.10, no config access), so a profile copy could not be checked against it.
`vendor_files` is resolved by the runtime probe (exists → bridge-readable → large enough), never by
enumeration: the app cannot read `vendor_file`.

Deliberately not in the block: `handoff` (implied by the pipeline, P4), `bridge_exec` (probed from
the two known paths), `insmod`/`load_domain` (baked into the stub; it cannot consume them until it
reserves patchable fields), and the ABI triple above.

**P4 — the handoff is implied by the pipeline, not configured.** `module` ⇒ Magisk; the existing
values keep KernelSU. If a kernel ever supports both handoffs, add an optional `handoff` key then
— with a default that preserves today's behaviour. Do not add it speculatively.

**P5 — authored, bundled-only, no extraction.** The pipeline is only honoured when the effective
profile is the **bundled** profile for this release **and** a hand-built module for the same
release is bundled with it. The two were validated together by hand; the bundle is the authority
for this pipeline. An external or imported profile that declares `module` is refused with a
reason, even when its release matches. Support is a curated list, not a derivation: each supported
release is one pair, added by hand; an unsupported release fails closed. Deriving it from the image
was considered and rejected — the module's build (config, compiler, CRCs) and the profile's
preflight are a hand-validated pair, and an emitted profile for a release with no module would only
produce a refusal at run time.

### Compatibility matrix

| reader → / profile ↓ | current app/build | new app/build |
|---|---|---|
| existing profiles | unchanged | unchanged (strict branch of P2) |
| new `module` profile (bundled, with the block) | refused: unknown pipeline and unknown top-level key (fail closed) | runs the new chain |
| `module` entry without the block | refused: unknown pipeline | refused: the block is required |
| block without the `module` entry | refused: unknown top-level key | refused: the block is inert without the entry |
| external import declaring `module` | refused: unknown pipeline | refused: the module pipeline is bundle-only |

The wire/native golden corpus must not move, and the extractor is untouched, so the
option/route corpus cannot change either. A test pins that adding or removing the block leaves
the native document byte-identical (the block is Kotlin-side, like `timer_98260`). The resolver
gains a third value, the block validator and the coupling rules; `PROFILE_SCHEMA.md`/`_ZH.md`
gain the pipeline section and a section for the block.

### Alternatives rejected

| alternative | why not |
|---|---|
| a new top-level `implementation`/`chain` key, `pipeline` untouched | two sources of truth; the resolver already walks one ordered list |
| a new `schema_version` (4) for the new chain | the document version is not a chain choice; schema 3 already exists and the module adds no document-format change |
| `route { module {} }` | `route` is the carrier (futex/select/multicast), not the chain; a module chain has no carrier |
| a separate `modules/index.conf` manifest | two manifests drift; the profile is the unit of authoring, and the hash check in the block gives the same integrity. The earlier objection to an artefact in the profile is answered by the bundle-only rule, the closed key set and the hash check — the asset path is inert metadata unless a bundled profile selects the chain |
| handoff as a profile key | installed managers are runtime state; the kernel image only constrains which handoffs are possible, and today that is already one-to-one with the pipeline |
| make `route` optional globally | weakens validation for every W1-based profile for no benefit |

### Open profile questions

- The name is settled: `module`, matching the `pipeline` value. `dfmodule` was the alternative; no
  compatibility cost either way before shipping, and the block is bundle-only.
- Whether `["module", "ghostlock"]` should ever be emitted (module preferred, W1 as fallback).
  Recommendation: not before the module has a measured device record; the fallback would also
  re-impose the W1 requirements under P2.
- Hand-writing a profile for a new kernel: possible only as a new **bundled pair** (a profile with
  the block + the hand-built `.ko` it names, added together). The external-profile path cannot
  authorize a module chain.

## 4. Components and changes

| area | change |
|---|---|
| `profile-core` `ProfileResolver` | `module` in `PipelineNames`; pipeline-scoped required groups (P2); KDP rule; the `module` block validator (`ModuleBlock`) and the entry↔block coupling |
| `ProfileExporter` (Gradle) | skip a module-only profile: no native wire is generated; a mixed list keeps the W1 fallback's wire — implemented |
| `profile-core` schema docs | document `pipeline` (missing today) and the pipeline-scoped rules; EN+ZH together |
| `PipelineResolver` | third entry + viability callback (bundled profile source, the block's asset/hash check, ROM preflight, Magisk source) — implemented |
| `AndroidGhostlockRepository` | module pipeline path: no W1 native run, no `nativeDocument`; stages `assets/<asset>` after the hash check (`BundledModule`) and passes the block's `vendor_files`; still `runDirtyfragStage`; preflight refusal replaces the ksud gate for this pipeline; refuses `module` unless the effective profile is the bundled one (`bundledAuthoritative`); runtime env passes the handoff + module path to the stage — implemented, diting bench unchanged |
| native runtime config / `ops.cpp` | the root script branches on `GHOSTLOCK_HANDOFF=magisk`: insmod → wait permissive → Magisk setup → restore Enforcing; the KSU block stays for existing pipelines. No new invocation mode and no wire change (the stage is already `--dirtyfrag <plan>`) |
| DirtyFrag stub (`dirtyfrag_stub.bin`) | the reference prologue: the worker child forks a grandchild that writes `u:r:vendor_modprobe:s0` to `/proc/self/attr/exec` and execs `/vendor/bin/insmod <vendor target>` (the target string patched like the socket name), then serves its socket exactly as today |
| crash_dump bridge | a small helper patched into `crash_dump64`'s page cache (the app can read it) and exec'd for the `crash_dump` transition; its read/splice modes let the app write the module into a `vendor_file` target. Restore `crash_dump64` and the target after the module loads |
| module bundle | two shipped pairs: star `5.4.233-qgki-gbb70cde46897` and venus `5.4.274-qgki-g76ac0b265f44`, `.ko` under `assets/modules/`, referenced by their `module` block — no index file; the profile is the manifest. Source and the reproducible recipe: `tools/dirtyfrag_module/` |
| bundle check | every bundled profile whose `pipeline` contains `module` must have the referenced asset and a matching `asset_sha256`; every `assets/modules/*.ko` must be referenced. Implemented app-side in `BuiltinProfilesTest`; the ABI agreement stays in the build gate record |
| Magisk staging | `MagiskStage` locates an installed official/alpha Magisk manager, stages its binaries (the 32-bit half from the APK) into the app's files dir for the root script, and refuses with a clear reason when none is installed — implemented |
| app UI | pipeline display for `module`; the module/Magisk readiness on the support card; no new profile editor fields — pending |

## 5. Gates

**G1 — static (host).** Validation/resolver/schema tests, including the fail-closed matrix above and
the bundle-authority rule (an external profile declaring `module` is refused; a bundled pair with a
missing module asset is refused); the bundle check (every bundled module-pipeline profile has its
asset and a matching hash, and every module asset is referenced by exactly one profile); the ROM
preflight extension (libc++ cave,
`insmod`/`vendor_modprobe`, Magisk source) on both 5.4 packages. The corpus is untouched by
construction — the extractor does not change. The policy checks in section 2 are already done.

**G2a — diting, Magisk only.** Run the *existing* DirtyFrag pipeline (W1 is device-proven there)
with the runtime handoff switched to Magisk. Isolates the new part: policy patch in the permissive
window, daemon, su, re-enforce, daemon survival, app recovery. Requires a test build only.

**G2b — diting, module replaces W1.** Build the real module for `5.10.236`, add it to a test
bundle, run a `pipeline = ["module"]` test profile. This is the new chain end to end on a phone
with known-good parts.

**G3 — 5.4 device (star/venus).** The real gate: primitive + module + Magisk on the target line.
Pass = `su` works from an external shell and the manager sees root, Enforcing is restored,
magiskd is gone after reboot, no bootloop, run store completed.

### Results so far (2026-11-22, diting)

- **G2a passed.** With `GHOSTLOCK_HANDOFF=magisk` on the unchanged `dirtyfrag` pipeline: the root
  script patched the live policy (`MAGISK-PHASE policy rc=0 source=live` — this ROM has no
  monolithic precompiled file), started magiskd, restored Enforcing, and the stage read
  `RESULT=OK script=0 enforce=1`; the manager reports Magisk 31.1 installed and su requests reach
  the daemon (rejected for uid 2000 by Magisk's default policy).
- **G2b passes on the diting (2026-11-22).** The module pipeline: the aux-install stage patches the
  helper into `crash_dump64` (109 blocks); the app reads the vendor target's bytes through the
  bridge and computes the IVs; the main stage delivers the stripped module into
  `/vendor/lib64/libbinderdebug.so` (691 blocks), restores `crash_dump64`, patches the libc++
  stub, and triggers init; the stub's worker writes `attr/exec=vendor_modprobe` and insmods the
  target; the module sets `selinux_state = false`; the socket connects as `uid=0`; the Magisk
  handoff reaches `MAGISK-SUMMARY` (policy rc=0 source=live, magiskd 31.1) and the stage reads
  `RESULT=OK script=0 enforce=1`. The module is unloaded after the check.
  Three build rules this pass established: the stub blob must be **linked at 0** before extraction
  (an unlinked object leaves every `adr` as an unresolved relocation pointing at itself); one
  **monotonic ESP sequence per stage, far from the install run's range** (the SA's replay check
  drops a stage that restarts its sequence); and each packet's 24-byte header needs **its own
  arena page** (a queued skb may be processed after a stack buffer was reused). The reference's
  per-block process cadence hid the last two.
  The vendor target's page cache is clean/reclaimable, so it reverts to the original library after
  the load (the module was loaded from the cache before reclaim); `crash_dump64` is restored in the
  same stage.

No stage may fall back after the stub has run: a failure is restore-and-report (W1-style
discipline).

## 6. Failure and restore semantics

- The permissive window is bounded by the module load and the policy patch; the script restores
  Enforcing on every exit path, the written pages are restored regardless, and a failure is
  `RESULT=FAIL` — never a silent permissive device.
- A module that fails to load leaves the device as it was; the chain reports and reboots clean.
- If the Magisk setup fails after the policy patch, the device is still enforcing afterwards; the
  run store records the failure and the manager is simply not functional.
- The module has no persisted state; a reboot removes everything (temporary root, as today).

## 7. Risks and open questions

- **Curated support, not a farm.** There is no build automation: each supported release is a pair
  built and validated by hand (profile + `.ko`) and shipped in the bundle. The cost is per release,
  and an unsupported release fails closed. The offline build recipe is the existing one (exact
  config + vendor clang + Image CRCs), so a new pair is reproducible, not guesswork.
- **The bridge costs one `crash_dump64` spawn per block mode.** An 11 KB module is 691 blocks
  (read + splice each), so ~1.4 k short-lived processes; measure before adopting, and batch the
  helper if the stage budget demands it. `crash_dump64` and the vendor target are restored after
  the module loads; a crash mid-write leaves the system crash handler and one unused vendor
  library patched in the page cache until reboot.
- **Reference licensing.** DFRoot's technique is read and re-implemented here, not copied: the
  reference is GPLv2 and this app is not. The same rule as with the other reference sources
  applies — technique in, code out.
- **Magisk binary sourcing and drift.** Using the user's installed manager avoids redistribution
  (Magisk is GPLv3) but couples us to its binary layout and version. Probe and pin versions; fail
  closed on an unexpected layout.
- **Zygote restart.** The example restarts zygote; it kills the app mid-run and may not be needed
  for `su`. Test with and without before adopting it.
- **su exposure on Android 13/14.** `/system/xbin` does not exist on either ROM; the example's xbin
  bind is legacy. The modern exposure (Magisk's own mount mechanism or the manager's path) must be
  designed and tested.
- **Vendor policy differences.** `magiskpolicy` patches the precompiled vendor policy; the rules
  Magisk expects must still apply after the patch. Verify by checking magiskd and `su` under
  Enforcing, not by the patch's exit code.
- **Primitive on real 5.4 hardware** is still unmeasured (the probe exists; the 5.4 unit is G3).
- **App UX**: the new pipeline changes the preflight refusal (Magisk missing instead of ksud
  missing), the pipeline label, and the post-run state (a working manager, not KSU).

## 8. Non-goals

- Replacing `ghostlock`/`dirtyfrag` on the kernels where they already work; changing KSU/Shizuku;
  persistent root; attestation bypass; bundling Magisk itself.
