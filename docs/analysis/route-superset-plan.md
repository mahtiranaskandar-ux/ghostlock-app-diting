# Route superset and the execution-time route choice — plan (2026-11-22)

**Status 2026-11-22 — implemented end to end for this slice: the app picker and run dispatch,
the extractor superset, the ship/store migration (61 supersets), the bundle goldens and the
tests, and the on-device test of a generated superset.** Both items this plan deferred are now
delivered on top: the per-route carrier list and the DirtyFrag choice
([`route-choice-matrix-plan.md`](route-choice-matrix-plan.md)) and the timer block in generated
profiles ([`timer-block-generation-plan.md`](timer-block-generation-plan.md)). Parents: the
pipeline amendment ([`profile-pipeline-selection-plan.md`](profile-pipeline-selection-plan.md)) and
the timer W3 delivery plan ([`timer-w3-app-delivery-plan.md`](timer-w3-app-delivery-plan.md)).
Operator decisions of 2026-11-22 are §2.

**Extractor and ship record (2026-11-22).** `options::render_superset` renders every feasible
route's block (its `Auto` plan's geometry and anchor), the shared blocks, the `pipeline` and the
phys maps. **There is no route order**: no `routes` list is written; the app displays the viable
routes alphabetically. The `w1_scratch` is emitted only when every chosen plan shares the same
region (a mixed set omits it — the page path — rather than claim a slot that does not fit another
route's anchor). `--format conf` without `--route` writes the superset when more than one route
is feasible; the route ambiguity no longer exits 7, and `--route` still renders the one-route
cut. `ship_golden_profiles.py --refresh-all` migrated the store assets: **61 bundled, 0 missing,
0 mismatched, 11 exempt**; `--check` now also compares the asset's route branch set to the
store's feasible set. Verified on the 6.1.138 store image: all four blocks with the shared
anchor, no `routes` line, `pipeline = ["ghostlock"]`; the `--route select_stack` cut is
unchanged. Extractor lib tests: 158/0. The run log carries both the runtime cut (`profile.bin` /
`timer-profile.json`) and the superset source (`profile.conf`) with a `profile.meta` selection;
the timer path writes the source alongside its runtime document.

**App record (2026-11-22, same slice).** The app renders its native wire from the picker's
`selectedRoute` (`buildNativeDocument`'s route override) — before this slice it used the
document's first branch, a real defect the refreshed assets exposed. The `ProfileExporter` picks
the same default (viable branches alphabetically, the first with no route-specific error). The
generation route picker is **deleted** (`RouteChoice`, `ParseStep.Route`, `showRouteDialog`,
`parsedFromOptions`, `RouteDisplay`/`RouteGrade`); a multi-route parse exports the superset and a
pipeline-ambiguous parse is a failure notice. The native-document golden was re-frozen and the
suites pass: profile-core 71/0, app 279/0.

**Generated-superset device test (2026-11-22, duChamp).** A profile generated on the device (the
raw 6.1.138 Image + its `vendor_boot`) was exported through SAF, selected, and run: the home read
the generated document as the source with the alphabetical default, the picker listed the image's
viable native routes, a bogus remembered choice was cleared and fell back to the default, and the
`select_stack` run completed (`entry=direct`, `state=completed`, route calls `clean=1/1`,
`KernelSU ready`, `native exited code=0`). The first pass exposed a defect the second verified
fixed: `tcp_zerocopy`'s superset branch carried no `compact_waiter` (its stamp shape is the shared
`waiter_layout.compact` fact), so the app's validator dropped the route, and the generated
document's round-trip dropped the empty branch as well. `report::conf_route_geometry_with_sigreturn`
and `options::render_superset` now emit the shared flag for the tcp branch (17 store supersets
regenerated and their assets refreshed; 28 bundled tcp branches carry it in all, including the
hand-finished and template assets that already did; `RouteCandidatesTest` expects the five
candidates). A store regeneration also overwrites the `timer_98260` blocks — the main extractor
does not emit them — so the two shipped blocks were restored byte-identically from the bundled
assets. Generated profiles now carry the timer block too: the parse path runs a vendored
timer analyzer and merges its block when the app's own parser accepts it
([`timer-block-generation-plan.md`](timer-block-generation-plan.md)).

**Followed by:** the per-route carrier list and DirtyFrag choice (implemented;
[`route-choice-matrix-plan.md`](route-choice-matrix-plan.md)) and the timer block in generated
profiles (implemented; [`timer-block-generation-plan.md`](timer-block-generation-plan.md)).

**Implementation record (2026-11-22).** The app implements §2–§6: `RouteCandidate` /
`ProfileConfig.routeCandidates` / `selectedRoute`; `viableRoutes()` is a pure profile parse
(native branch blocks + the timer block; errors belonging to another branch or the timer are
ignored per candidate); the choice is a `route_choice_<release>` preference with self-healing at
load; the home row opens the viable-only list dialog; the run dispatches to the timer or the
native chain; the timer's Shizuku ask, reboot-first notice and the one-attempt boot contract are
wired. *(The consent dialog that shipped with this record was removed the same day by operator
decision; the reboot-bound facts are logged at run start, and the ask now reads the live Shizuku
status instead of the toggle-gated snapshot — see
[`timer-w3-app-delivery-plan.md`](timer-w3-app-delivery-plan.md) step 2.)* `renderMerged` no longer trims other routes' tuning, and the app has no
route order (the display and default are alphabetical; a legacy `routes` list is validated but
ignored). The device test —
`external/port/timer-w3-app-2026-11-22/route-picker-duchamp/` (root repo) — passed on both
backends: the timer pick → consent → full way-2 chain (`20261122-171111`, completed), the
same-boot refusal notice with no new run, the choice persisting across a restart and a reboot,
then the native pick → `select_stack` native chain (`20261122-171408`, completed,
`KernelSU ready`). Host gates: profile-core 71/0, app 279/0, `assembleDebug`. The extraction-side gap this record
used to name — one native route written, `routes` emitted, the generation picker still asking —
is closed by the superset slice at the top of this document (the extractor writes the superset,
no `routes` line, and the generation route picker is deleted).

## 1. Problem

We cut the profile to one route at generation time (`extract_rs` requires `--route` when more than
one route is feasible; the app's parse flow asks; `renderMerged` trims to the active route). The
route verdict and its parameters can be **wrong for a kernel** — the generator's image-level
derivation is not the device's runtime truth. Cutting at generation therefore discards the
alternatives exactly when they are the escape hatch, and the timer route makes the same kernel a
multi-route device (native tool vs Shizuku-delivered shell tool) whose viability is partly a
runtime property.

The pipeline amendment already retired the same pattern for pipelines (ordered list, resolved
before W1, no generation picker). This plan generalizes it to routes, with one deliberate
difference: **no ranking resolver.** The user chooses.

## 2. Decisions (operator, 2026-11-22)

- **Keep the superset in storage and export**: every viable route's block, its parameters, its
  order and its provenance. Nothing is trimmed.
- **Display only the viable routes and let the user choose.** No score, no ranking: the user has
  the empirical knowledge from running.
- **Resolution is stateless**: it is a pure parse/validation of the profile. No device probes, no
  measured-DB ranking, no resolution state.
- **No route order.** The app displays the viable routes alphabetically; a legacy `routes` list
  is accepted but ignored. The default is the alphabetically first viable route.
- **The choice is remembered per exact `uname -r` and self-healing**: if the remembered route is
  not in the viable set when the UI renders, it is simply cleared and the default applies. No
  migration or versioned state.
- **The run uses the chosen route.** The native wire stays single-route (the app renders the
  selected branch exactly as it renders the cut profile today).
- **Runtime preconditions are not viability**: a missing Shizuku for the timer is asked at run
  time, not hidden from the list (the operator's earlier requirement).

## 3. Viability — a pure function of the profile

`viableRoutes(profile, release)` returns the ordered candidate list, using **only the profile**
(plus the exact release match). A route is viable when:

- the profile's `release` equals the running `uname -r`;
- its block exists and parses (`route.<name>` for a native route, `timer_98260` for the shell
  route), and the per-route required-field matrix is satisfied;
- the static guards the native would apply at load pass: the route×waiter-shape guard
  (`waiter_layout.compact`), anchor alignment when anchored, page-free capability requirements,
  and the timer's complete fake group / trigger.

Routes the extractor did not derive, and blocks that fail the above, are **not in the list**. The
extractor writes only feasible routes; the app's validation is the second, fail-closed gate.

Order: alphabetical. The default is the first viable entry; there is no stored or declared
preference.

## 4. Profile shape and migration

- `route` becomes a parent with **one or more** branches (the schema's "exactly one" rule is
  retired for profiles that declare more). A one-branch profile remains legal and behaves as
  today.
- No order key is written. A legacy `routes = [ ... ]` list is accepted and validated but
  ignored; the app sorts the viable routes alphabetically. The timer has no `route` branch; its
  block is `timer_98260`.
- `fallback` stays as today for the native route's own internal fallback; it is not a picker
  order.
- The superset export preserves every branch and its fact provenance; `renderMerged`'s trimming
  to the active route is removed. The editor and `ProfileMerger` must merge per branch (the
  current branch-replacement semantics apply to each branch, not to the parent object).
- The canonical `ProfileDocument`'s `native.route` stays the **runtime** single branch, rendered
  per run from the chosen branch, so the native wire and its digest are unchanged.

## 5. The picker

- **Home row**: shown whenever the profile has viable routes; when there is exactly one it is
  read-only (no dialog). The row shows the chosen route.
- **List dialog** (the existing `AppDialog.ListDialog` pattern): the viable routes only. Each row
  shows the name and a short fact line — e.g. `select_stack — compact waiter`, `timer_98260 —
  shell via Shizuku`. No stars, no scores, no confidence ranking. Run-time hints (timer: needs
  Shizuku; one attempt per boot) are shown but do not remove the entry.
- **Persistence**: one preference keyed by the exact release; selecting writes the name. The
  primary 执行 uses it.
- **Self-healing**: when the UI renders, if the stored name is not in the viable list, clear the
  preference; the alphabetically first viable route applies. No migration, no versioning.
- **Run record**: the chosen route is written into the run store (`route=<name>`) so the outcome,
  the profile hash and the route travel together in the report.
- The generation flow's route picker (`ParseStep.Route`) and the extractor's "more than one
  feasible route; pass --route" error retire: generation writes the superset.

## 6. Runtime preconditions (not viability)

- **Timer selected**: if Shizuku is not `READY`, the run asks (open Shizuku / request permission)
  regardless of the native `shizukuEnabled` toggle — the live status is read through
  `shizukuShellStatus()`, because the snapshot reports `NOT_REQUIRED` while the toggle is off.
  There is no consent dialog (removed 2026-11-22); the run starts directly and logs the
  reboot-bound facts. If `timer_attempt_boot_id` equals the current boot, refuse with "reboot
  first".
- **Native selected**: the existing preflight/load checks apply; the native's own load refusal is
  the final gate. The app must not list a route the static guards reject (§3).
- The picker's rows show these facts; they never remove a viable route.

## 7. Failure handling

- The remembered choice persists. The run store records route + profile hash + outcome; the user
  can re-pick on the home row.
- No automatic ranking and no automatic route fall-through in this plan. (A failed run could
  record a `nextRoute` note for the next boot; that is an explicit later option, not built here.)

## 8. Work items

1. **Profile model**: multiple `route` branches; per-route validation; legacy `routes` accepted
   but ignored; `ProfileMerger` per-branch merge; `renderMerged` no trimming; editor/support
   surfaces show the branch set.
2. **Extractor**: `--format conf` emits the superset instead of the cut; the `--route` ambiguity
   error goes (keep `--route` for the explicit one-route cut).
3. **App**: `viableRoutes(profile, release)` (pure); the home picker; the per-release preference
   with self-healing; the run path renders the selected branch; the run log records it.
4. **Timer**: the candidate in the picker, the Shizuku ask and the boot contract (the
   service/run pieces already exist and are device-gated).
5. **Bundle/ship**: assets carry the superset; `ship_golden_profiles.py` checks all branches; the
   native-document golden is per run and unchanged in shape.
6. **Docs**: `PROFILE_SCHEMA.md` §3/§4 (multiple branches, `routes`), and the pipeline plan's
   cross-reference.

## 9. Gates and tests

- **Host**: parse/serialize round-trip with multiple branches; per-route viability (waiter-shape,
  incomplete block, timer fake-group); legacy one-branch/`fallback` migration; the picker choice
  self-healing; a rendered native document per viable branch (the existing goldens by route); the
  extractor corpus (`options`, `cut`, `route_verdicts`, `codegen`) regenerated and compared.
- **App**: `:profile-core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`.
- **Device**: one run per selectable route on a device (duChamp: `select_stack` + `timer_98260`;
  diting: `select_stack`; whichever the profile carries); a route switch persisted across a
  restart and self-healed when the route disappears; the timer's ask/same-boot refusal.
- **Invariant**: the native wire digest does not move (the runtime document is still one route).

## 10. Risks

- A wrong parameter **within** the chosen route is not fixed by the superset — it gives an
  alternative, not correctness. The editor's per-route fields and provenance are the remedy.
- The static guards must match the native's; a stale profile that lists a route the native
  refuses must fail cleanly at run start, not half-way.
- Page-free/anchored facts differ per route; the picker's fact line must not imply more than the
  profile carries.
- The picker must not regress single-route users: one route renders as today, no dialog.
