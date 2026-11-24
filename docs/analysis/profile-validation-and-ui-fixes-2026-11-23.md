# Profile validation and the UI gaps from the 2026-11-23 device pass

**Status:** implemented (uncommitted) on `fix/ui-behaviour-2026-11-23`, cut from
`document` @ `de9b3d2b` (versionCode 870). Operator direction: surface the external-selection
reason instead of migrating old documents; make generation and import share one platform rule;
reject a document whose declared route branch is incomplete.

## 1. The physical layout is a route prerequisite, with one rule

The pair (`kernel_phys_load` / `kernel_phys_offset`) is what the GhostLock routes translate the
image address through. It is a **document** value with a platform-dependent fallback. The
native's `ResolvedAddresses::init_for_soc` (`src/core/memory/address_space.cpp`) is the authority:

| family | load | offset |
|---|---|---|
| MediaTek | **required** (native `EINVAL` without it) | **required** in the app's rule: the compiled `P0_PHYS_OFFSET` is a Qcom-shaped default and the MTK fleet's offsets differ |
| Qcom (incl. the native's fallback for an unrecognised SoC) | compiled default (`P0_KERNEL_PHYS_LOAD`, `QC_GKI_6_12_PHYS_LOAD` on 6.12) | compiled default (`P0_PHYS_OFFSET`) |
| Google/Tensor | compiled default (`KIMAGE_TEXT_BASE - MTK_VADDR_BASE`) | compiled default |
| Xring | compiled default (`XRING_KERNEL_PHYS_LOAD`) | compiled default |

`app/.../domain/model/PhysicalLayout.kt` now exposes this as one `physRule(socName)`
(`PhysSource.Required` / `PhysSource.Defaulted`). Both consumers derive from it:

- `generationPhysComplete` — the parse flow stops when a `Required` half is missing, so **no
  profile is written** (previously the two functions could drift; the rule was hand-written
  twice).
- `physPairErrors` — import verification rejects a document that omits a `Required` half.

A unit test (`PhysicalLayoutTest`) asserts the two paths reach the same verdict for every
family × presence combination. On a defaulted platform a null pair is valid and is not a partial
config: the sidecar is a calibration. The image prompt now says which half the image supplies and
what skipping means on this platform (`parse_phys_required_hint` / `parse_phys_default_hint`,
shown through the new `AppDialog.ListDialog.messageRes`). On MediaTek, skipping cannot derive
GhostLock and generation ends with the existing reason instead of emitting a profile.

## 1b. The timer is derived first, and a derived timer makes the native inputs optional

The 98260 analyzer is independent of the physical pair: it reads the boot image only. The
generation flow now reflects that:

- The first pass runs on the staged boot image **before** any sidecar prompt (previously the app
  asked for vendor_boot/xbl_config before it had analyzed anything, so the timer fact did not
  exist when the prompt was worded).
- Inside that pass the native extractor and the `libtimerextract.so` analyzer run on the same
  image; the timer block is validated before the prompts are chosen.
- The prompt says what skipping means on this platform: defaulted platform → GhostLock keeps its
  compiled default; required platform with the timer derived → "跳过则只生成 98260 定时器路线";
  required platform without the timer → the strict hint and generation stops on skip.
- On MediaTek, an incomplete pair with a derived timer produces a **timer-only profile** instead
  of a partial GhostLock one: `release`, `schema_version = 3`, `kernel_major`,
  `pipeline = [ "timer" ]`, and the `timer_98260` block, with none of the route/W1 sections
  (`TimerOnlyDocument`). Import validation exempts a document that selects no W1 chain from the
  pair requirement, the route picker offers only the timer, and `onRun` already routes the timer
  selection to the timer tool.
- The profile format gains the `timer` pipeline entry: it is a non-W1 chain, and the entry and
  the `timer_98260` block are one authored pair (the block is required with the entry).
- Tests: `PhysicalLayoutTest` (the pair rule with `needsW1 = true/false`, generation and import
  agreeing), `TimerOnlyDocumentTest` (the projection's shape, a MediaTek import accepted without
  a pair, and a native profile still refused without one).

Device evidence (POCO X6 Pro, MediaTek MT6897, 6.1.138): the first prompt after the parse reads
"跳过则只生成 98260 定时器路线…"; skipping vendor_boot and dtbo reaches the save step instead of
the stop notice, and the saved document is the timer-only projection (982 bytes, `pipeline =
[ "timer" ]`, no `task_struct`/`cred`/`offset`/`kernel_phys`/`route`).

## 2. Import validates every declared route branch

`validateProfileFields` used to validate only the active route. `viableRoutes` silently drops a
branch with errors, so a `select_stack` branch missing `waiter_shift` made the resolver pick
`sigreturn` — a different, weaker route than the document promised, with no notice. The
validator now runs the per-branch geometry checks for **every branch the document declares**
(the active route, the fallback target keeps its own legacy carrier rule, and all other declared
branches). A document with an incomplete declared branch is rejected at import with the field
path (`route.select_stack.waiter_shift: invalid`), so the silent route switch cannot happen.
`ProfileBranchValidationTest` pins both the pristine and the broken case.

## 3. Schema v2 is an import-time rejection, not a stored state

An external copy that fails content checks cannot be a *user* state:

- The released r11 is `com.ghostlock.app`; this build is `com.sesame.app` (the rename landed
  2026-11-22, after r11). An in-place update cannot carry the old package's data across, so no
  user upgrades this build onto a v2 copy.
- The real path is a user importing a profile **file** they exported with r11. That goes through
  `selectExternalProfile`, which rejects it and restores the previous selection (transactional).
  The rejection dialog now localizes the document-level reason (`本版本只读取 schema 3 的配置
  （此配置为 schema 2）`) and per-field paths (`pselect waiter 位移
  (route.select_stack.waiter_shift)：无效或缺失`), and refreshes the Home afterwards so it
  cannot keep the pre-import state.
- A stored copy that somehow fails to resolve shows the neutral
  `该外部配置当前不可用，请重新导入或取消外部配置` in Parameters — no schema wording on screen,
  and no Home banner. The previous plan's "surface the external layer error on Home" is dropped:
  it would have designed UI for a state the product cannot produce.

No migration: v2 documents are refused deliberately (`ProfileMigrationEquivalenceTest` records the
policy); the remedy is a re-export with this build.

## 4. The smaller UI corrections

- The measured-DB merge preview was orphaned when the god state was deleted (`91364724`); it is
  reachable again from Measurements (`查看合并`), and the hint no longer points at a nonexistent
  Home button.
- The builtin hint no longer describes the deleted editor; the Advanced summary drops the removed
  CPU-pair row; `cpu_pair_label` is gone; the Shizuku switch summary says what it does.
- The last-run row is store-backed: the Home status is seeded from the newest run store entry, and
  the sheet replays the stored log when the process has none (the in-memory log dies with the
  process, which is exactly the crash case the store exists for).
- The AccessPending card splits "not installed" from "installed but stopped".
- A hand-picked builtin for another release is labelled `不适用于本机内核`; if one is active the
  Home names it and offers `恢复自动匹配` (the pref key keeps its historical
  `debug_builtin_release` name — renaming needs a migration and is not worth it yet).
- The import rejection dialog localizes known field paths (via `FieldLabels`) and keeps the raw
  key for editing.
- The run sheet's running line carries elapsed time; the terminal verdict gets its own row so it
  no longer wraps beside the two action buttons.

## Gates

- `:app:testDebugUnitTest`, `:profile-core:test`, `:app:assembleDebug`.
- No native, wire-format or attack-path change: `cmp_disasm` is not required (the wire is
  untouched; `recommended_cpus`/`selected_cpus` handling is unchanged).
- Device pass on the diting (870 + this branch): external v2 copy shows the reason; a broken
  branch is rejected; the phys prompt says the right thing on QTI; merge preview reachable;
  last-run replay after a relaunch; measurement/prompt strings; a full run still completes.
