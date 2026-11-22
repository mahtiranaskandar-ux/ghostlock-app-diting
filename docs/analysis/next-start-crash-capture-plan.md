# Next-start crash capture — plan and implementation

Status: **implemented in the working tree, uncommitted, 2026-11-17.** Host gates pass
(unit suite + debug APK). The report flow was reworked after the r5 review; the
unsupported Xiaomi feedback-app path was removed, the real 192 MB Xiaomi bugreport was
imported and exported on the diting, and the layered bugreport extractor (nested
stability texts, reboot records, panic summaries, generic crash blocks) was probed
against the diting, duchamp and OnePlus reports. The reduced import is now **in-memory
for one export session**, not persisted; old stored imports are deleted at startup.
The operator's UI review and the remaining device gates are open; nothing is committed
until the operator confirms.

## Goal

A failed run often leaves the app's log ending mid-stage, with no kernel text: the run
store says `running`, the device rebooted, and the decisive oops is visible only to a
shell. The app should carry the kernel side of a report when it can, and make the
manual path (a user-supplied system bugreport) a first-class import when it cannot.
The export unit becomes one bundle: every retained run plus the crash evidence.

## The layers

| layer | source | availability | what it gives |
|---|---|---|---|
| base | the app itself | always | each run log's `[boot] id=… start=…` line, the log mtime, `meta` state, the resolved profile |
| collected | Shizuku, then `su` | only when the app has a shell | the pstore nodes (`console-ramoops*`, `pmsg-ramoops*`, `dmesg-ramoops*`) and the boot-reason properties/history, captured automatically at start and refreshed at export |
| imported | a user-supplied bugreport zip | universal, manual | the boot-reason properties/history, the `KERNEL LOG (dmesg)` section, and any `last_kmsg`/pstore entries the ROM put in the report |

The bundle labels every evidence directory with its `source`; the analyst joins the
evidence to a run using the run's boot id and log mtime, not an app-side guess.

## What was measured before choosing this shape (2026-11-16)

- **The app domain is denied the kernel facts.** On the duchamp (Android 16), a real
  `untrusted_app` process read `ro.product.marketname`/`ro.boot.hardware` but got empty
  values for `ro.boot.bootreason`, `sys.boot.reason`, `sys.boot.reason.last` and
  `persist.sys.boot.reason.history`; `/proc/uptime` was unreadable; `boot_id` was
  readable. The loaded policies on both the diting (Android 15) and the duchamp grant
  `pstorefs` only to `bootstat`, `charger_type`, `dumpstate`, `init`, `logd`,
  `recovery_*`, `shell` and `system_server`; the boot-reason properties are granted to
  `shell` (all three), `priv_app` (`sys.boot.reason`), `platform_app`
  (`sys.boot.reason.last`), `bootstat` and `system_server`, never `appdomain`.
  **So Shizuku/root is the only automatic path; a bugreport's `dumpstate` is the
  manual one.**
- **A system bugreport carries what we need, device-dependently.** The duchamp's
  27.5 MB bugreport had `FS/data/misc/recovery/last_kmsg` and `.1`…`.10` (262,132 B
  each), the boot-reason properties (including the history) and a `KERNEL LOG (dmesg)`
  section. The OnePlus's 21 MB bugreport had the properties and the kernel-log section
  but **no pstore/last_kmsg** entries (`Error dumping /proc/last_kmsg: No such file or
  directory`); the device's `/sys/fs/pstore` standard names are ENOENT. The importer
  therefore matches names/patterns and keeps whatever exists.
- **The system bugreport is reachable through the picker, not through an app
  query.** The shell app exposes `com.android.shell.documents` (AOSP
  `BugreportStorageProvider`), root id `bugreport`, title 错误报告. The provider is
  `android:permission="android.permission.MANAGE_DOCUMENTS"` (signature); the system
  picker holds it, a normal app does not. `ACTION_OPEN_DOCUMENT` with
  `EXTRA_INITIAL_URI = content://com.android.shell.documents/root/bugreport` opens
  the picker directly in 错误报告 (verified on the duchamp: the 27.51 MB full report
  and its dumpstate text were listed); the `document/bugreport` form and a picker
  instance that was already running did not navigate. The selected document comes
  back with a read grant, so the importer can stream it.
- **The Xiaomi feedback app is not the crash-report app (measured 2026-11-16).**
  `com.miui.bugreport` exposes `miui.intent.action.BUGREPORT_HOME` →
  `com.miui.feedback.HomeActivity` (the feedback home) and `miui.intent.action.BUGREPORT`
  → `.ui.FeedbackActivity` (the feedback composer); neither generates the system bug
  report. `miui.intent.action.VIEW_FEEDBACK_FULL_LOG` → `.ui.CatchFullLogActivity`
  ("日志抓取") is a category-based log-capture tool that stages logs for a feedback
  submission (no importable report landed in app storage during the test). The real
  full report is the Settings **Developer options → 错误报告 → 完整报告 → 报告** dialog;
  the `*#*#284#*#*` secret-code receiver starts the same `dumpstate` generation, but
  the broadcast is protected (shell gets a `SecurityException`; only root could send
  it) and the MIUI dialer treated the code as an MMI call on the diting, so there is
  no public app-launchable shortcut. The generated report is the standard AOSP zip:
  the diting's run produced
  `bugreport-diting-AQ3A.250226.002-2026-11-16-23-40-46.zip` (192,210,522 B; 589
  entries; 72.4 MB main text; a `KERNEL LOG (dmesg)` section; the boot properties;
  no `last_kmsg`/pstore because the pstore was empty), and the 错误报告 picker listed
  it. Our importer kept `kernel-log.txt` (2,097,082 B) and the boot history from it.
  The unsupported vendor path is removed; the flow uses Developer options on every
  ROM. OnePlus's `com.oplus.logkit` remains permission-locked, and its OPPO dialer
  code `*#800#` failed on the test build.
- **The boot-reason history is the correlation tool.** It is
  `persist.sys.boot.reason.history`, `reason,epoch` lines newest first (three entries),
  maintained by `bootstat`; the newest timestamp is the current boot's start. The
  pstore record at a capture is from the boot immediately before the capture boot; a
  run whose log mtime lies between that boot's start and the capture boot's start was
  alive in the evidence's boot. The app does not compute this verdict; the bundle
  carries the facts.

## Implementation

- **Run birth line.** `RunLogWriter.init` writes `[boot] id=<boot_id> start=<epoch_ms>`
  as the first log line and syncs it immediately. `RunLogStore.indexText` parses it for
  the bundle index.
- **`CrashEvidenceStore`** (`last-crash/`): captures named
  `yyyyMMdd-HHmmss-<bootid8>`, three kept; `boot.txt` is the completion marker and
  carries `source`, `capture_boot_id`, `captured`, the boot properties and the
  per-pstore outcome; `boot-reason-history.txt` keeps the raw multi-line value.
- **Collected layer.** `GhostlockUserService` gained `String[] readBootFacts()` and
  `@nullable ParcelFileDescriptor openPstore(String)` (AIDL transaction ids 3 and 4;
  the pstore names are a fixed allowlist). `ShizukuExploitRunner.collectCrash` binds
  the same UserService briefly, reads the facts and streams the PFDs into the capture
  directory. `RootCrashCollector` runs the same script through `su` (15 s bound) for
  rooted devices without Shizuku. `AndroidGhostlockRepository.collectCrashEvidence`
  prefers Shizuku, then root, then writes a bare `source=none` capture so the bundle
  still records the boot.
- **Imported layer (2026-11-17).** `BugreportImporter` streams the zip once and keeps
  a layered, content-matched set; no rule is gated by device, model or build.
  - **Main text:** the boot-reason properties, the current-boot `KERNEL LOG (dmesg)`
    (≤4 MiB), the Xiaomi `Kernel reboot records` / `dgt and det match` block (raw,
    ≤4 MiB), vendor panic-summary blocks (`panic reboot`, `fst_frame`, `keyinfo=`,
    `CriticalLogKernel`, …; ±10 lines, ≤256 KiB) and generic kernel-crash blocks
    (`Kernel panic`, `Unable to handle kernel`, `Oops`, …; 64 lines before/256 after,
    ≤4 MiB). A small `report-sections.txt` lists the main text's section headers for
    a future unsupported vendor.
  - **Top-level files:** basename matches `last_kmsg*` or `*oops*` (case-insensitive;
    covers `console-ramoops*`, `pmsg-ramoops*`, `dmesg-ramoops*`, `mtdoops`), ≤4 MiB
    each and ≤8 MiB total.
  - **Nested stability zips:** every `FS/**/stability/reboot/*.zip` is opened one
    level deep (never recursively). It keeps `temp/last_kmsg` (≤4 MiB),
    `temp/md_history` (≤1 MiB), `log/mtdoops.md` and `temp/last_logcat` (≤16 MiB each),
    ≤32 MiB total. Minidumps, TZ logs/keys and everything else are skipped. Each
    file records the outer and nested entry path plus the zip time.
  - **In-memory for one export session.** The reduced evidence is an
    `ImportedEvidence` object held by the ViewModel; it is never written to
    `last-crash` and never survives the report sheet. Its `capture_boot_id` is the
    app's boot at import (no more `unknown`). The bundle writer adds it under
    `last-crash/<stamp>-import/` for that one bundle, then the sheet drops it. Old
    on-disk `source=bugreport` captures from earlier builds are deleted at start and
    filtered from bundles.
  - The `boot.txt` facts state `kernel-log-scope=current-boot` and
    `crash_evidence=yes|heuristic|no`, so a report with only the current boot's
    dmesg is distinguishable from one with a real crash log. The UI uses
    `crash_evidence` for the export warning and the imported status.
  - The full report, its main text, logcat, app data and minidumps are never retained;
    the original zip remains in the system's 错误报告 folder.
  - **The scan is byte-level (2026-11-17).** `ByteLineReader` splits the stream on
    `\n`/`\r`/`\r\n` into one reused buffer, `ByteRing` keeps the last 520 lines as
    raw bytes in a 512 KiB arena for the block context, and the evidence writers
    append the bytes they keep verbatim. A `MarkerMatcher` (Aho-Corasick over the
    fixed token set, ASCII-folded) marks the lines that can possibly satisfy
    `isCrashMarker`/`isPanicRecord`; only those are decoded and lowercased, and the
    exact predicates run unchanged. Nothing is allocated per line on the common path.
    The measured effect (host, same four survey reports): OnePlus post-crash 1144 →
    720 ms, diting 192 MB 1499 → 804 ms, duchamp 1217 → 813 ms, OnePlus pre-crash
    1187 → 561 ms; on the OnePlus device the post-crash report imports in **1.7 s**
    (previously about a minute), and the 192 MB diting report in **5.6 s**. The
    extracted files were compared byte-for-byte against the bundle the r9 build
    produced on the OnePlus: identical names and contents. The import also logs
    `bugreport import: <name> <ms> ms, ok=<bool>` at `I` level.
- **Bundle export.** `RunLogStore.exportBundle` writes `index.txt` plus every run
  directory, every retained `last-crash/` automatic capture, and the session's
  in-memory import when one is present; zip entries keep the file mtimes (DOS
  two-second granularity) and `index.txt` repeats the run's `boot_id`, `start`,
  `state` and `log_mtime` in epoch milliseconds. Stored `source=bugreport`
  captures from older builds are excluded.
- **UI (r6 flow).** The report entry (home → 导出报告 / Export report) is no longer a
  fixed four-step wizard. The entry decides the path from the evidence:
  - **A shell-backed capture, a kernel log, or a session import is already in hand**
    (`source` = shizuku/root, an automatic capture with kernel text, or the in-memory
    import from this report sheet): go straight to the export flow — a message says
    to choose the destination and the system save dialog opens immediately; the sheet
    behind it offers the button again if the dialog is cancelled. A shell capture
    with an empty pstore counts: the shell already read what the boot had, and the
    export warning still names the missing kernel log. A **stored** imported report
    does not count; if no automatic evidence exists, every export asks again.
  - **No kernel log**: the first screen asks **附加系统错误报告？** with
    **附加错误报告（推荐）**, **不附加，直接导出**, and an **已有错误报告？直接导入**
    shortcut. Export-without-it goes to the same immediate destination dialog; the
    export screen keeps the **未包含系统日志（不推荐）** warning and a **返回导入** link.
  - **Attach**: the app reads `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED`. When off,
    the **启用开发者选项** screen explains the About-phone route (seven taps), opens
    About phone, and re-checks on `onResume` — enabling Developer options advances to
    the generation instructions by itself; a **已启用？重新检查** link and a
    not-detected toast cover the manual cases. When on, the flow starts at
    **生成错误报告** (Open Developer options + the help link; the instructions name the
    actual item, 错误报告 → 完整报告 → 报告 on Xiaomi). Then **导入错误报告** → export.
    A successful import advances to the export flow and opens the destination dialog.
    **While a report is being parsed no transition is allowed**: Back, Next and the
    import action are disabled (Miuix draws them gray), and a tap outside or system
    Back is ignored by `onRunLogPickerDismiss`, so the parse always owns the sheet
    until it ends. The choice screen now puts 12 dp between the attach and
    export-only buttons.
    This is the same path on Xiaomi/Redmi/POCO: the feedback app is not the system
    bug report (see the measurement above), so there is no vendor branch.
  - The step counter is per path: four steps with the Developer gate, three without,
    two for a direct import, and none for a direct export.
  - The wizard summary carries each screen's explanation; the body holds only the
    actions. The old skip confirmation is gone — the initial choice states the
    recommendation, and Back reaches Choice for the export-only branch.
  The manifest still accepts `ACTION_SEND` for `application/zip`/`application/octet-stream`,
  so the bugreport notification's Share sheet can hand the zip straight to the importer;
  an import outside the flow records the evidence without opening the flow.

## Tests

- `RunLogStoreTest`: boot-line assertions; bundle contents, index fields, crash path,
  mtime preservation.
- `CrashEvidenceStoreTest`: rotation, fact/history parsing, incomplete directories,
  `findForBoot`, bare capture.
- `BugreportImporterTest`: in-memory property/history extraction, kernel-log section,
  top-level `last_kmsg*`/`*oops*` matching, nested stability extraction, Xiaomi
  reboot-record boundaries, vendor panic blocks, crash-evidence facts, single-line
  history, empty/corrupt zips, section-inventory-only and reboot-block-only reports,
  CRLF/CR line endings, a line longer than the reader buffer, and a mixed-case
  compound panic line. The env-gated real-report probe prints per-report
  milliseconds and can dump every evidence file (`GHOSTLOCK_REPORT_DUMP_DIR`).
- `RunLogStoreTest`: the bundle carries a session `ImportedEvidence` under
  `last-crash/<stamp>-import/`, including its `boot.txt`, index line and zip time,
  while stored `source=bugreport` captures are excluded.
- The importer was also run against the real reports in a host probe
  (`GHOSTLOCK_REPORT_DIR`), 2026-11-17:

  | report | crash_evidence | extracted |
  |---|---|---|
  | diting 192 MB | yes | `stability-last_kmsg` (330 KB, full `+0x188` oops), `md_history.txt` (58 KB), `mtdoops.md` (14 MB), `last_logcat.txt` (2.1 MB), `reboot-records.txt` (4 MB, includes the `dgt and det match` oops), `crash-excerpts.txt`, `report-sections.txt` |
  | duchamp 27.5 MB | yes | top-level `last_kmsg*` from both recovery stores, `reboot-records.txt` (includes the `+0x1ec` oops excerpts), `crash-excerpts.txt`, `report-sections.txt`; nested stability zips yielded no `temp/*` files |
  | OnePlus pre-crash 21 MB | **no** | `kernel-log.txt` + `report-sections.txt` only; no `last_kmsg`, no panic line |
  | OnePlus post-crash 20 MB | yes | `kernel-log.txt`, `panic-record.txt` (`BootReceiver record Panic Reboot` + `CriticalLogKernel`), `crash-excerpts.txt` |

  The pre-crash OnePlus report is the important negative: only the current boot's
  kernel log exists, and `crash_evidence=no`; it is no longer mistaken for a crash
  capture. The post-crash report shows the only extra marker that device exposes, the
  vendor panic summary; the full `CriticalLogKernel` is not in the AOSP zip.
- Full `:app:testDebugUnitTest`, `:app:assembleDebug`, `make -C src ghostlock`,
  `make -C src lint-tidy` and `make -C src native-host-tests` pass (2026-11-17 for the
  app gates; the native gates stand from 2026-11-16).

## Open for the operator / UI tuning

- The automatic capture at app start tries `su` on a rooted device; on the diting this
  may show a KSU prompt the first time. Decide whether to gate the root attempt behind
  a manual "collect now" or an explicit preference.
- The Shizuku capture runs even when the exploit's Shizuku toggle is off, as long as
  Shizuku is ready and permitted. Decide whether the capture should follow the toggle.
- The Xiaomi/Redmi/POCO feedback app (`com.miui.bugreport`) is not the system bug
  report: its `BUGREPORT_HOME`/`BUGREPORT` entries open the feedback home/composer, and
  its "日志抓取" screen is a category-based capture for feedback submission. The full
  report is the Settings Developer-options **错误报告** dialog (or the protected
  `*#*#284#*#*` receiver), so the app uses Developer options on every ROM. The OnePlus
  Log Kit is permission-locked and the OPPO `*#800#` code failed on the test build.
- The app cannot read the shell's bugreport provider itself (`MANAGE_DOCUMENTS`); the
  no-shell path is the picker, now pre-selected at 错误报告. With Shizuku/root the
  shell UID could list the provider/directory and import the newest complete report
  automatically (dedupe by name+mtime, skip partial zips); that discovery is not
  implemented yet.
- ~~The importer scans the main `bugreport-*.txt` once (potentially ~88 MB) with a
  buffered reader; the report sheet only says "Importing…". Progress/duration tuning is
  open.~~ Closed 2026-11-17: the byte-level scan above took the OnePlus report from
  about a minute to 1.7 s and the 192 MB diting report to 5.6 s. The sheet still only
  says "Importing…" — with these durations a progress bar no longer earns its place,
  but the text may be reconsidered for very large reports on slow storage.
- The report sheet no longer lists runs or offers a per-run export; the bundle is the
  only export. The single "Clear all logs" action remains and clears runs only; crash
  evidence is kept. Decide whether that action should clear both or be split.

## Device checks run 2026-11-16

### r6 flow (superseded by the vendor-path removal)

- **Duchamp (POCO X6 Pro, no kernel log in hand):** Home → 导出报告 showed
  **附加系统错误报告？** with the crash-log card at 未收集到. **附加错误报告（推荐）**
  opened the then-current vendor screen (第 1 / 3 步), **下一步** the import screen
  (2/3), and **导入错误报告** opened the system picker already at **错误报告**.
  Selecting the 27.51 MB full report imported it and advanced straight to the export
  flow: the **请选择报告的保存位置** toast appeared over the system save dialog, the
  save wrote `/sdcard/Download/ghostlock-report-20261116-232120.zip` (3,625,654 B),
  and the sheet then showed **报告已保存**, the imported name, **分享报告**, and
  **技术详情**. The bundle contains `index.txt`, every retained run, and the imported
  capture (`last_kmsg*`, `kernel-log.txt`, `boot-reason-history.txt`, `boot.txt`).
- **OnePlus 13s, Developer options on/off:** attach reached **生成错误报告** (1/3) with
  the switch on; with it off, **启用开发者选项** (1/4), a not-detected toast on
  **下一步**, and the automatic advance to **生成错误报告** (2/4) after enabling it and
  resuming. Settings restored to 1/1.
- **OnePlus, export without a report:** **不附加，直接导出** opened the save dialog
  immediately with the **请选择报告的保存位置** toast; cancelling left the warning,
  **返回导入**, **选择保存位置**, and **清除全部日志**.
- **Direct path:** with an imported report stored, the duchamp's next 导出报告 skipped
  the choice and opened the save dialog immediately.

### r7 flow (vendor path removed)

- **OnePlus, Developer options on:** choice → **附加错误报告（推荐）** → **生成错误报告**
  (第 1 / 3 步); the summary names 错误报告 → 完整报告 → 报告, and the help dialog has
  no Xiaomi-feedback button. The vendor screen is gone.
- **OnePlus, Developer options off:** attach → **启用开发者选项** (第 1 / 4 步);
  **下一步** still off showed **仍未检测到开发者选项**; enabling + resume advanced to
  **生成错误报告** (第 2 / 4 步). Settings restored to 1/1.
- **Diting (Xiaomi feedback app exercised; consents accepted on the operator's
  instruction):** the feedback app's `BUGREPORT_HOME`/`BUGREPORT` screens are the
  feedback home/composer, and `VIEW_FEEDBACK_FULL_LOG` is the category-based
  "日志抓取" capture; none produced an importable full report. The real report came
  from the system generation (Developer options **错误报告 → 完整报告 → 报告**, and the
  protected `*#*#284#*#*` receiver): `bugreport-diting-AQ3A.250226.002-2026-11-16-23-40-46.zip`
  (192,210,522 B; 589 entries; 72.4 MB main text; `KERNEL LOG (dmesg)`; the boot
  properties; no `last_kmsg` because the pstore was empty). The 错误报告 picker in
  build 756 listed it; selecting it imported and auto-advanced to the export flow,
  and the saved bundle (1,774,959 B) carried `kernel-log.txt` (2,097,082 B),
  `boot-reason-history.txt`, `boot.txt`, and `index.txt`. The diting app was restored
  to build 750 (the APK was backed up before the test; its data persists).
- The latest review build is installed on the duchamp and OnePlus; the diting is back
  on 750.

### In-memory session import (2026-11-17)

- **Duchamp:** the first launch of the new build deleted the old
  `20261116-232117-import` directory; only the automatic `source=none` capture was
  left. 导出报告 now shows **附加系统错误报告？** instead of jumping to the save dialog.
- **OnePlus:** imported the 20.27 MB post-crash report through the picker. No
  `-import` directory appeared under `files/last-crash` (verified with `run-as`).
  The saved bundle `ghostlock-report-20261117-012418.zip` (4,286,326 B) carries
  `last-crash/20261117-012328-import/` with `source=bugreport`,
  `capture_boot_id=e18ded11-32b0-47bc-adee-a5309ebe07f5` (the app boot at import),
  `kernel-log.txt` 133,935 B, `crash-excerpts.txt` 100,292 B, `panic-record.txt`
  4,495 B, `report-sections.txt` 10,734 B, `boot-reason-history.txt` and `boot.txt`.
  Dismissing the sheet and re-entering 导出报告 showed **附加系统错误报告？** again —
  the session import was not stored and cannot skip the next export.
- The OnePlus import of the 101 MB main text took about a minute on-device; the UI
  shows **正在导入错误报告…** throughout. Progress/ETA remains an open tuning item.

## Device gates still to run

1. Duchamp: verify a Shizuku/root collected capture (source=shizuku/root with pstore
   files) and the direct-to-export entry when the kernel log exists. The current
   duchamp pstore is empty, so the automatic path cannot be exercised there without a
   crash that leaves one.
2. Diting: verify the root capture after a boot that produced a pstore record (the
   current pstore is empty), then restore the rig's build.
3. A real user bugreport (e.g. the Realme archive) through the importer.
