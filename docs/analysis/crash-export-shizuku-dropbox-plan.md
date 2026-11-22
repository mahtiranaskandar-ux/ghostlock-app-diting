# Crash export: make the exporter actually use Shizuku, and read the kernel log where it exists

**Date:** 2026-11-21. **Scope:** app-only (Kotlin + AIDL), no native, no wire/profile change.

## Problem

The automatic crash capture has a Shizuku path (`collectCrashEvidence` →
`ShizukuExploitRunner.collectCrash` → `GhostlockUserService.readBootFacts`/`openPstore`), but:

1. **It never asks.** `readCrashFacts` uses Shizuku only when `status()` is already `READY`;
   `status()` demands a shell-run server, a granted permission and a live binder. A
   `PERMISSION_REQUIRED` or `NOT_RUNNING` state falls silently to the `su` path and then to a bare
   "no shell available" capture. The export sheet's retry was additionally gated on the *Shizuku
   mode* being enabled, so even a ready server was ignored when the user runs the direct entry.
2. **It reads the one thing shell cannot.** `openPstore` opens `/sys/fs/pstore/<name>` directly;
   those nodes are root-only on Android, so a shell-run server always fails. A denied open and an
   absent file were both recorded as `missing`, which is why a report could not say whether pstore
   was empty or merely unreadable.
3. **The shell-readable channel was not used at all.** Android's `system_server` reads pstore at
   boot and stores the previous kernel console as the DropBox entry `SYSTEM_LAST_KMSG`. `shell`
   may read DropBox. That is exactly the no-root kernel-log channel the venus report needs.

## Change

- **AIDL `IGhostlockUserService` + method 5** `writeDropboxLastKmsg(ParcelFileDescriptor): long`:
  the service probes the `dumpsys dropbox` listing for the tagged entry and streams
  `dumpsys dropbox --print SYSTEM_LAST_KMSG` into the app's file. Returns the byte count, `0` for
  an empty entry and `-1` when the tag is absent. Bounded at 4 MiB (a console-ramoops entry is a
  few hundred KiB).
- **`readBootFacts()`** gains one `pstore.<name>=exists:<size> | denied | missing` fact per
  allowlisted node (`Os.stat` + `ErrnoException`). The app keeps that fact unless a root-run
  server can actually open the node.
- **`ShizukuExploitRunner`**: `crashAccessStatus()` accepts a **shell- or root-run** server for the
  crash collector (the run entry's `status()` is unchanged); `awaitCrashAccess()` waits out the
  permission dialog; `collectCrash` writes `kernel-log.txt` from DropBox and records
  `dropbox.last_kmsg=ok:<bytes>|empty|absent|failed`.
- **Export flow** (`onExportRunLog`): when the stored evidence has no kernel log, it calls
  `collectCrashEvidenceWithShizuku()`, which requests the permission from this user-initiated
  path and waits up to 20 s. If Shizuku is installed but not running, a toast says so instead of
  exporting silently. The start-up capture stays silent (no prompts from a background start).
- **Root path** (`RootCrashCollector` script): same DropBox capture into `kernel-log.txt`, plus a
  `pstore-listing.txt` (`ls -l /sys/fs/pstore`) so a rooted device records what pstore holds.

`kernel-log.txt` is already the name `CrashEvidenceStore` treats as kernel evidence and
`RunLogStore.bundleZip` already ships every file of a capture, so the bundle carries it without
further changes.

## Tests and gates

- New `DropboxKmsgTest` pins the entry-line matcher: a real listing line matches, the `Searching
  for:` header does not, and a prefixed tag (`SYSTEM_LAST_KMSG_OLD`) does not.
- App unit suite and `:app:assembleDebug`.
- Device: the diting (root, no Shizuku installed) must produce a capture with the new
  `pstore.*` facts and `dropbox.last_kmsg=absent` and no new prompt; the shell-side commands are
  verified with `adb shell` (same uid as Shizuku): DropBox listing readable, pstore `denied`.
  A full Shizuku-run test needs the Shizuku manager installed (none of the rigs has it); the
  permission-request path is covered by the code path above and by the toast/fallback on a device
  without it.

## Risks

- `dumpsys dropbox` output size: capped; the listing read is bounded at 2 MiB.
- A root-run Shizuku now qualifies for the *crash* path only; `status()` for the exploit entry is
  deliberately untouched, so the run environment (shell) does not change silently.
- The permission prompt on export is user-initiated; a denied dialog falls through to the existing
  choice flow after the 20 s bound.

## Follow-up 2026-11-21: the Generate step's missing Developer-options entry

Some ROMs ship Developer options **without** the "Take bug report" entry — the tester's screenshot
of that page (NAS, 2026-11-20) shows only "Bug report handler". The wizard's Generate step assumed
the entry and dead-ended.

Since shell is exactly what those users already have through Shizuku, the Generate step now offers
it directly: **Generate the report via Shizuku**. `GhostlockUserService.startBugreport` runs the
on-device `bugreportz -p` (the zipped form; the flat one is deprecated), parses its `OK:<path>`
line (`BugreportzOutput`), and streams the zip through a pipe into the app's importer — no 300 MB
copy is stored, and the app needs no storage permission. The report lives under the shell app's
private bugreports directory, which is why only the service can read it. Measured on the diting
(HyperOS, 5.10.236): `bugreportz` 1 m 49 s, 368 864 412 B, and `BugreportImporter.extract`
consumes it in 1.16 s, keeping `stability-last_kmsg`, `mtdoops.md`, `md_history.txt`,
`last_logcat.txt` and the main kernel log.

The step also carries the universal fallback for devices without a shell: `adb bugreport
report.zip` on a computer, then import the file. A device without Shizuku keeps only the
Developer-options route and the fallback text; the button is hidden when no shell is reachable
(`crashShellAvailable` in the UI state).

Reachability fix in the same pass: the export used to go straight to the destination when *any*
shell capture existed, so the Generate step — and the new Shizuku generator — was unreachable
exactly where the shell capture had no kernel log (the diting/venus DropBox-absent case). The flow
now skips the choice only when a **kernel log** is in hand; a partial shell capture shows the
choice, whose "Attach a bug report" leads to the Generate step. That also puts a full bugreport —
including the MIUI stability zips that can hold the previous boot's kernel log — one tap away on
shell-capable devices.

Tests: the existing importer test plus the new `BugreportzOutputTest` (OK line, FAIL line, empty);
the streaming path and the wizard advance are device-gated on the diting.

**Device result (diting, 5.10.236, HyperOS, build 774, 2026-11-21).** Report sheet → Choice (the
partial shell capture no longer skips it) → Attach → Generate shows “通过 Shizuku 生成错误报告”
plus the adb fallback; tapping it raised the Shizuku permission prompt, and after the grant:
`I GhostLock: bugreport generate: bugreport-shizuku-9275ec29.zip 104606 ms, ok=true`. The wizard
advanced to the destination picker and the saved bundle (6,279,916 B, md5 `ff98888b…`) carries
`last-crash/20261121-002135-import/` with `kernel-log.txt` 2,097,086 B, `stability-last_kmsg`
337,360 B, `mtdoops.md`, `md_history.txt`, `last_logcat.txt` and `crash_evidence=yes`. The
`stability-last_kmsg` entries came out of the MIUI stability reboot zips — the previous boot's
kernel log, the channel that matters on devices whose pstore/DropBox are empty (the venus). The
duchamp runs 774 with no Shizuku: the button is hidden and the step keeps the Developer-options
route plus the adb fallback.

## Crash-capture device test (diting, 2026-11-21): which channel actually holds an oops

A deliberate panic (`echo c > /proc/sysrq-trigger`; the rig's `/proc/sys/kernel/sysrq` is 0 and
`panic_on_oops` 0, but the proc trigger bypasses the sysrq mask) rebooted the phone in ~40 s. After
the reboot:

- **pstore is empty** (root `ls /sys/fs/pstore` — the directory has never held a file on this ROM),
  and DropBox has **no `SYSTEM_LAST_KMSG`**. The shell capture records the facts correctly —
  `pstore.*=missing`, `dropbox.last_kmsg=absent` — so the DropBox channel added in this plan is a
  no-op on this ROM; the app now says so instead of the old undifferentiated "not readable".
- **The vendor stability recorder did capture the panic**: a fresh
  `/data/miuilog/stability/reboot/-1_ke_eee7c901…zip` (188,130,549 B) appeared with
  `temp/last_kmsg` (630,172 B) containing `sysrq: Trigger a crash`,
  `Kernel panic - not syncing: sysrq triggered crash`, the call trace
  (`__handle_sysrq`/`write_sysrq_trigger`) and the panic tail (including
  `PHYS_OFFSET: 0x80000000`).
- **The Generate-via-Shizuku route pulled it in**: `bugreport generate: bugreport-shizuku-c020a5b0.zip
  108433 ms, ok=true`; the saved bundle (6,003,265 B, md5 `afa703cb…`) carries
  `last-crash/20261121-003053-import/stability-last_kmsg` (630,172 B — the crash's `temp/last_kmsg`)
  and `crash-excerpts.txt` (502,498 B) with the panic markers.
- **A real oops is on the same channel**: an older stability zip already in the report
  (`stability-last_kmsg-1`, 337,608 B, from the 2026-11-20 10:43 crash) contains
  `Unable to handle kernel paging request at virtual address 0030322d31310a46`,
  `Internal error: Oops: 96000004 [#1]` and `Kernel panic - not syncing: Oops` — a genuine fault,
  not the sysrq marker.

Conclusion: on MIUI/HyperOS the no-root crash channel that works is the **vendor stability zip,
surfaced by the generated bugreport**; pstore/DropBox is empty on this ROM and the app's facts now
say so. The venus reporter's next crash should use 附加错误报告 → 通过 Shizuku 生成错误报告 on
build 774+.

**UI follow-up (2026-11-21).** Two corrections to the Generate step: the Shizuku button is no
longer hidden when the shell is unavailable — it stays in place, greyed and non-clickable, with
“请先启动 Shizuku，再回到本应用” underneath, so the route the user came for never disappears.
And the verdict is now polled every 1.5 s while the report sheet is open: the Shizuku
binder/permission listeners cover most events, but a grant made in the Shizuku manager does not
notify the app, and starting or stopping the server should flip the button in place rather than on
the next app start. The poll stops when the sheet closes (`onRunLogPickerDismiss`/`onClearRunLogs`)
and is cheap (a binder ping plus a permission check per tick).

**Device (diting, 776).** With the report sheet on the Generate step and the server up, the button
is enabled; killing the server greyed it and revealed “请先启动 Shizuku，再回到本应用” within one
tick, and a tap on it is a no-op (focus and the app log unchanged — note the button's *text* node
still reports `enabled=true`; the clickable parent is where the disabled semantics live). Restarting
the server re-enabled it and removed the reason line within ~2 s, with the same sheet open; closing
the sheet to the home stops the poll.

**Revised Generate layout (diting, 777, 2026-11-21).** The step now opens with a
one-line description and a right-aligned step chip, then a single primary card
(label “通过 Shizuku 生成”, a full-width “开始生成（约 2 分钟）” button, and the
subtitle “无需电脑；完成后自动导入。”), then “其他方式” with two rows: the
Developer-options route (chevron) and “在电脑上生成” (`adb bugreport report.zip`
plus a 复制命令 button wired to the new `onCopyBugreportCommand`). The
bottom-right action is renamed “导入已生成的报告” because the primary path
auto-advances; the generic 下一步 remains on the other bug-report steps. The
disabled state greys the button in place and puts one line, “Shizuku 未运行：启动
后此处会自动可用。”, in the card. The dialog title/summary stay centered — Miuix's
`OverlayDialog` takes plain strings — so the body carries the left-aligned
hierarchy. Screenshots (NAS share root):
`ghostlock-ui-generate-v2-enabled-2026-11-21.png` (md5 `6e1c515f…`) and
`ghostlock-ui-generate-v2-disabled-2026-11-21.png` (md5 `ec1c9c0d…`).

## NAS package (2026-11-21)

`smb://nas.local/haoyu_ssd/ghostlock-builds/2026-11-21-crash-export-generate-777/` — the review
build 777 (APK 22,910,129 B, md5 `6214b9801bea5fbfbedbfcbc5c593d0a`, sha256
`c81b12501113bd6af040f89498eee85c44b83bdbe4d4fe1af15de8f9ad465189`), a README with the English
overview and the Chinese how-to, this design doc, and the two Generate-step screenshots
(`ui-generate-enabled.png`, `ui-generate-disabled.png`). Every file is listed in `MD5SUMS` and
`SHA256SUMS`; both sets verified on the share (`md5sum -c`).

## Freshness gate for vendor stability zips (2026-11-21)

The venus report of 2026-11-21 (build 777) claimed `crash_evidence=yes` while both imported
`stability-last_kmsg` files were **byte-identical** (`md5 0478710c…`), began with a 2022 vendor
header, and ended at a **312,638 s uptime** — from vendor zips dated Nov 13/15, a week before the
Nov 21 `long_power_key` reset. The importer kept any nested `temp/last_kmsg` without asking which
boot it belonged to.

**Minimum fix (implemented, host-gated only).** A vendor stability zip counts as crash evidence
only when its own filename epoch (`..._<epoch>_<rand>.zip`) falls inside the crashed boot's window
derived from `persist.sys.boot.reason.history`: `[previous reboot, capture reboot + 120 s]`.
Out-of-window files are dropped and recorded as `nested.stale.*`; every candidate keeps
`nested.zip_epoch.*` and, for `stability-last_kmsg`, `nested.last_uptime.*` (the last `[<uptime>]`
stamp), so a fresh zip carrying stale content is visible in the next report. There is no content
rejection yet — that second check is only justified if the facts show it is needed.

Tests: the nested-stability test now uses a realistic name inside the window and asserts the
epoch, uptime and absence of a stale fact; a new test rejects an out-of-window zip and expects the
`heuristic` flag. `:app:testDebugUnitTest` passes. **No device gate** — the phones are in A/B use;
the change surfaces in the next exported report.

Not covered by this minimum: `crash_evidence` can still be `yes` from the top-level
`/mnt/rescue/` `last_kmsg` files and from the vendor reboot-records section (the venus report
stays `yes` for those reasons). Making the flag mean "kernel text for this boot" is a separate
decision; the next report's `nested.stale.*` / `nested.last_uptime.*` facts are what decide
whether a content check is worth adding.
