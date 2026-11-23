# Route choice matrix — one select box per choice (plan, 2026-11-22)

**Status 2026-11-23 — layout A implemented and device-checked: both carriers on the duChamp
(2026-11-22), and DirtyFrag off/on on the diting (2026-11-23).** Operator decisions (2026-11-22):

- "The GhostLock block actually can carry many combinations: carrier, DirtyFrag on/off, syscall.
  I think for this route specifically we should render a select box per choice."
- "I think you should try to keep top-level block one block per route, so GhostLock is still a
  whole block. You can redesign the keys in the same way as pipeline so it expresses the choices."
- "'syscall' = the carrier syscall" (the `route.<name>` branches: select/pselect, setsockopt,
  sigreturn, tcp).
- DirtyFrag: **on / off**, off = GhostLock only.

Supersedes the "carrier variants" deferred item in
[`route-superset-plan.md`](route-superset-plan.md).

## 1. Problem

The picker renders one entry per route. A route is not one configuration: each carrier syscall
(select/pselect, setsockopt, sigreturn, tcp) can be carried by the native 64-bit implementation or
the AArch32 compat one (the compat select also carries a futex ABI), and the run can use the
profile's DirtyFrag-before-GhostLock pipeline or GhostLock only. The profile today carries one
carrier per branch and the pipeline is invisible in the picker.

## 2. The matrix (operator)

The home shows three select boxes for the GhostLock backend:

| box | values | where the values live |
|---|---|---|
| Syscall (route) | the viable `route.<name>` branches | the profile, as today |
| Carrier | `native` / `compat`, per chosen syscall | a list-valued key inside that route's block |
| DirtyFrag | `on` / `off` (off = GhostLock only) | the profile's ordered `pipeline` list; the box appears only when it names `dirtyfrag` |

There is **no separate ABI box**: the compat carrier option means "the compat carrier this image
derived" (its futex ABI is part of that carrier's geometry, as today). The timer route is the
syscall box's `timer_98260` entry and has no carrier/DirtyFrag choices.

Every choice is remembered per exact release (the carrier per release+syscall) and self-heals
against the profile's current sets, like the route choice today.

## 3. Document shape (one block per route, pipeline-style choice keys)

Keep the top-level structure — one `route { <syscall> { ... } }` block per carrier syscall; the
GhostLock side stays whole. Inside a route block, the choice is a list key, like `pipeline`:

```hocon
route {
  select_stack {
    carriers = [ "native", "compat" ]
    /* native carrier */
    waiter_shift = 1
    compact_waiter = 1
    lock_anchor_image = 35802320
    lock_anchor_bytes = 4096
    lock_anchor_stride = 32
    /* compat carrier (present only when the image derives it) */
    waiter_home = 488
    select_dst_depth = 496
    compat_futex_abi = 1
  }
  multicast_waiter {
    carriers = [ "compat" ]
    compat_carrier = 1
    compat_waiter_off = 72
    compat_buffer_size = 260
    compat_futex_abi = 1
    task_offset = 48
    lock_offset = 56
    compact_waiter = 1
    lock_anchor_image = 35802320
    lock_anchor_bytes = 4096
    lock_anchor_stride = 32
  }
}
```

Rules:

- `carriers` is a list of `"native"` / `"compat"`, in the profile's preferred order (no ranking;
  the app displays the list order). **Absent `carriers` keeps today's semantics** (the branch's
  `compat_carrier` flag selects the carrier), so legacy and hand-finished profiles keep working.
- The branch carries the union of the feasible carriers' geometry. Native keys keep their names;
  compat keys keep the existing `compat_*` names (the compat select's `waiter_home` /
  `select_dst_depth` are already unprefixed and do not collide with native keys).
- The wire's `compat_carrier` is no longer a document fact for a `carriers` profile: the app sets
  it (and drops the other carrier's keys if needed) when it cuts the selected carrier. The native
  wire schema does not change.
- The extractor writes both carriers when both are feasible (today it writes only the chosen one);
  a compat-only route keeps `carriers = [ "compat" ]` and the compat geometry.
- DirtyFrag is not a document key: the profile's `pipeline` list stays the capability order. The
  app's "off" trims the resolved order to `ghostlock` for that run.

## 4. UI and run path

- **Home: one route entry**, whose summary names the backend and choices
  (`GhostLock · select_stack · native (64-bit)`, or the timer).
- The entry opens a sheet with **two bordered, clickable backend cards**: a large **GhostLock**
  card and a compact **timer** card. The selected card carries a primary border and a 当前
  marker; both are clickable to choose the backend.
- Both cards use the app's standard card colors (`CardDefaults.defaultColors()`), the same fill
  the home's rows use; the selected card's primary border is what marks the choice.
- The GhostLock card **keeps its three choices as folded rows** — **syscall**, **carrier**,
  **DirtyFrag**. A row with more than one option is drawn as an **inset field** (rounded
  `surfaceContainerHigh` fill, 12 dp inset) with the current value and the app's standard
  `ArrowRight` arrow (rotated to point down while its list is open), so its bounds read as a
  control rather than plain text; a single-option row is plain text with no arrow and does not
  open (the profile has nothing to choose there). Tapping a row opens a **floating list above
  the sheet** (anchored under the row, flipped above when the window has no room below): the
  sheet layout never moves, only the opened row's options are visible, an outside tap closes it,
  and selecting a value closes it and updates the draft. The carrier list follows the draft's
  syscall; the DirtyFrag list comes from the profile's pipeline order (`on` when it names dirtyfrag, `off` when it names ghostlock; a dirtyfrag-only KDP
  profile is a forced `on`).
- The sheet is a **draft**: taps change the draft only, and a single **保存** button applies it
  (syscall + carrier + DirtyFrag, then the selected backend last) and closes. There is no cancel
  button; dismissing the sheet discards the draft. While the timer is the chosen backend the
  GhostLock choices are still saved, so they remain the remembered native configuration
  (`native_route_choice_*`).
- Self-healing: a stored carrier not in the chosen route's `carriers` is cleared; the first
  listed carrier applies. A stored DirtyFrag choice is cleared when the pipeline no longer names
  it.
- Run: (syscall, carrier, dirtyfrag) → one native document cut from the route block (the app
  injects `compat_carrier` for compat) or the timer backend; the DirtyFrag choice filters the
  pipeline order. The run log/meta records all three.
- Generation stays choice-free: the extractor writes every feasible carrier; the home picks.

## 5. Layout (decided)

**Layout A** (flat keys + one `carriers` list) is implemented: the extractor writes `carriers`
and the union geometry for every feasible carrier, the store was regenerated, and the app parses
the list, renders the carrier and DirtyFrag boxes, injects the wire's `compat_carrier` from the
choice, and trims the pipeline for DirtyFrag off. Nested `native`/`compat` blocks were rejected.
Device: the duChamp ran `select_stack` with **both carriers** (compat `20261122-220324`, native
`20261122-220531`, both `entry=direct`/`state=completed`, `KernelSU ready`), with the call's choice
remembered across a reboot. The DirtyFrag box is correctly absent there (its pipeline is
GhostLock-only). On the diting (2026-11-23) its two values ran: `关` trimmed the pipeline
(`pipeline=ghostlock (profile order)`, GhostLock chain completed) and `开` ran the DirtyFrag stage
(`RESULT=OK script=0 enforce=1`, `state=completed`), both from the bundled schema-v3 asset —
`external/port/dirtyfrag-choice-2026-11-23/`.

## 6. Work items (after §5)

1. Extractor: emit `carriers` + the union geometry per route (superset and cut), including the
   second carrier's plan/anchor; regenerate the store and assets; keep `--route` cuts unchanged.
2. App: parse `carriers`; per-route carrier candidates; the carrier box + per-release preference;
   the DirtyFrag on/off box + trim; the wire injection; validation per carrier.
3. Tests: carriers round-trip; per-carrier validation and native documents; per-dimension
   self-healing; DirtyFrag trim; the picker's sets.
4. Device: one run per carrier on the duChamp (native + compat select) — done 2026-11-22; the
   DirtyFrag on/off trim on a profile that names it (the diting line) — done 2026-11-23.
