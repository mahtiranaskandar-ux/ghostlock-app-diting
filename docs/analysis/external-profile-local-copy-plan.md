# External profile import copy — design plan (2026-11-19)

Status: implemented 2026-11-19 (host gates pass; device gate pending). See §12.

## 0. Scope rule

Implement exactly the steps in this plan. Anything not listed here is outside this change; if
something not listed turns out to be a real blocker, stop and raise it before implementing.

The change is app-side only: `ExternalProfileStore`, `AndroidProfileConfigController`, the
profile-source UI/state, their strings and tests. The parser's public behavior is unchanged; the
copy store owns the schema-version policy using `HoconSupport`/`LegacyProfileConverter`. The
merge, validators, native document, wire and attack routes are untouched.

## 1. The model

1. **Generation/export writes the profile document to the SD card** through SAF. The SD card is
   the durable, user-owned store and the interchange format.
2. **Import copies the document once** into one app-private file:

   ```
   filesDir/profiles/imported_profile.conf
   ```

   Every import overwrites that file.
3. **The imported copy is the external-profile selection.** Applicability is decided by the
   copy's entries: the external profile applies when an entry's `release` equals the running
   device release (a document may carry several entries). Its optional
   `# ghostlock-profile …` lineage header is preserved as ordinary text; the app no longer parses
   or generates one. The import reference (URI, name, SHA-256, parent, release) is transient:
   keep it only for the duration of the import.
4. **Every load reads the copy**, never the SAF URI. The picker URI is an argument to the
   import, not state.
5. **The source card shows only the kind**: 外部配置 (the imported copy exists), 内置配置
   (builtin/bundled), or 未加载配置.

## 2. Why: the MIUI provider

On MIUI/HyperOS the system document picker returns URIs from Xiaomi's system File Manager
provider (`content://com.mi.android.globalFileexplorer.myprovider/...`), not the AOSP
`com.android.externalstorage.documents`. That provider is reported (web sources; not measured on
the phone — see the report README) not to support persistable URI grants: the selection-time
read works from a transient grant, the picker activity finishes, and the next `loadSource()` read
fails. The reporter's GIF shows exactly that: the success toast **已加载配置** and the later
**无法读取所选文档** banner together.

The diting control (app build 756, Google DocumentsUI) picked the same profile from
`/sdcard/root` through the system picker and got
`content://com.android.externalstorage.documents/...`, `persistable=0x3`, `persisted=0x1`; the
app loaded it with no banner and it survived a restart. The CN MIUI provider is the
differentiator. Evidence and sources:
`external/port/external-profile-picker-2026-11-19/README.md` in the port repository.

Therefore: read the document once at import, write the copy, and load from the copy on every
resolution. This is the web-recommended workaround for providers that refuse persistence.

## 3. Import flow

1. Read the selected document (the one SAF read; the picker URI is a parameter).
2. Run `ProfileDocumentParser.entryForRelease(text, deviceRelease)`; refuse a document without
   the running release.
3. Write `imported_profile.conf` atomically: a same-directory temp file, `fsync`, `rename`.
4. Load from the copy.

Failure handling:

- If the copy write fails, fail the import and leave the previous copy and state in place.
- If the app dies during the write, the temp file is removed at startup or overwritten by the
  next import.
- If the app dies after the import, the copy is the selection; the next import overwrites it.

## 4. Load, migrate, clear

1. **Load** reads `imported_profile.conf` and selects the entry whose `release` equals the
   running device release; no entry means the copy does not apply to this kernel.
2. **One-time migration from the current app.** On the first run after the update, if any of the
   old `external_profile_*` keys exist (`_uri`, `_name`, `_release`, `_sha256`, `_parent`,
   `_parent_sha256`) and no copy does:
   read the old URI once; if it yields the running release, write the copy; then delete all
   `external_profile_*` keys either way. If the URI is unreadable, apply the ordinary
   no-profile state and let the user re-import. This migration is the only use of the old
   fields. If a copy already exists and old keys do too (a crash between the copy write and the
   key deletion), delete the old keys without reading; the copy wins.
3. **No copy** means no external profile: apply the builtin/bundled precedence as today, and
   show 需要加载配置 when neither is available.
4. **Clear external / select builtin** deletes the copy. The builtin selection keeps its
   `builtin_release` pref.
5. **New import** overwrites the copy.
6. **Uninstall** removes app data.

## 5. UI

1. **Keep the read-failure reporting, repurposed for the copy**: `kernel_external_unreadable`
   (`_summary`) and `run_blocked_external_unreadable` stay for an app-private copy that cannot be
   read. Remove the SAF-reference UI: `external_profile_missing`, `layer_external_not_applied`,
   the reference detail strings (`external_profile_label/_sha/_parent/_other_release`), and the
   name/URI/SHA/parent fields of `ProfileLayers`/`ExternalProfileInfo`.
2. **Show only the kind** in the source card: 外部配置 / 内置配置 / 未加载配置. Add the two plain
   kind strings (`profile_kind_external`, `profile_kind_builtin`); `profile_not_loaded` exists.
3. **Keep the existing clear action** (取消外部配置) visible on the 加载配置 screen whenever
   `imported_profile.conf` exists, applied or not. The condition is the file's existence
   (`File.isFile`), not a parsed profile and not a persisted field.
4. **Use the existing status surfaces**: 配置需要修正 for a readable copy that does not apply (no
   entry for the running release, invalid fields, schema too new), 配置无法读取 for an unreadable
   or unparseable copy, and the disabled run gate. One new summary string
   (`imported_profile_not_applicable_summary`) is the only new failure text.
5. **Apply the states**:

   | copy | resolution for this kernel | home card | source card | clear action |
   |---|---|---|---|---|
   | absent | builtin/bundled applies | existing 配置已就绪 / 配置需要修正 | 内置配置 | hidden |
   | absent | nothing applies | existing 不支持 / 需要加载配置 | 未加载配置 | hidden |
   | present | entry for the release, valid | 配置已就绪 | 外部配置 | shown |
   | present | entry, invalid fields | existing 配置需要修正 (red fields) | 外部配置 | shown |
   | present | readable, no entry for this release | 配置需要修正 (early branch) | 外部配置 | shown |
   | present | unreadable / parse error | 配置无法读取 (early branch) | 外部配置 | shown |
   | present | schema newer than 2 | 配置需要修正 (same early branch) | 外部配置 | shown |

   The early branch is today's `externalUnreadable` check, moved before the `missing` branch
   (`GhostlockUI.kt:1643-1660`), so a selected copy is never reported as an unsupported kernel.
   `ProfileLayers` keeps `externalApplied` and a read-failure boolean plus the reason string;
   `releaseCoveredByStoredSource()` counts the copy's **existence**, not its release, so
   `snapshot()` still resolves (and reports the reason) when the copy names another kernel.
   Gate `devicePairMissing` on `!copyBlocked` as well: it is `kernelKnown && !usable`, and with a
   mismatched copy `pairKnown` is false, so without the gate `runExploit()`'s log would blame the
   device pair instead of the copy.
   Exports no longer prepend a `# ghostlock-profile` parent header: with the parent fields gone,
   nothing reads it. The copy is kept verbatim, so a header the source document already carries
   survives as text.

6. **Keep the SD-card source as the user's document.** The app shows only the kind; the user
   re-imports after editing the source.

## 6. Upgrade: added keys and schema versions

**What `schema_version` means here.** The field is a format discriminator, not a generation
counter: `1` when `kernel_phys_load`/`kernel_phys_offset` are scalars or absent, `2` when they
are per-device maps (required by `PerDevicePhys`; old builds reject 2 explicitly). Every writer
already follows the rule — `renderMerged` → `injectPhysMaps`, `UserProfileStore`, the legacy
export, and the extractor (`device.is_some() ? 2 : 1`) — and `EditorPhysMapTest` pins the export.
There is no single "current" number to stamp.

1. **Added optional key with a safe default** → the merge/tuning defaults apply; old copies
   load unchanged.
2. **Added required key with a safe default** → the merge fills it; document and test the
   default against an old copy.
3. **Added required key without a safe default, or a key that changes behaviour** → bump the
   profile schema and ship a converter that sets it. A copy that has no converter reports
   配置需要修正.
4. **Copy unversioned, or schema 1/2** → on the first load:
   - if the document carries no `schema_version`, add it by the map rule (maps → 2, else 1) and
     **rewrite `imported_profile.conf` in place**; if it carries 1 or 2, keep it;
   - preserve the lineage header (`ProfileLineage.header`, because a render drops comments);
   - apply the rule per entry for a multi-entry document and preserve the object-vs-list shape;
   - write atomically; keep the SD-card source unchanged.

   If the rewrite fails, use the in-memory conversion and retry on the next load; if the
   conversion fails, report 配置需要修正 and keep the old copy.
5. **Copy schema newer than 2** → refuse with an unsupported-schema error and keep the copy; the
   app has to be updated.
6. **Validation failure after conversion** → keep the copy, report 配置需要修正; the user
   re-exports a current-schema document to the SD card and re-imports.
7. **Load every schema the app has emitted** (1 and 2, and the unversioned legacy form), or
   ship a converter with a schema bump. The rewrite is idempotent: the second load sees a
   versioned copy and does nothing.

## 7. Storage-model amendment

`measured-db-plan.md` §1 says app data keeps no profile documents and only the selection
reference. Amend it:

> The app keeps exactly one **imported copy** of the selected external document
> (`filesDir/profiles/imported_profile.conf`). It is the active profile, overwritten on every
> import, schema-upgraded in place when the app's profile schema advances, deleted when the
> selection is cleared or replaced, and never a precedence layer or a document library. The SD
> card remains the document store. The copy is the whole external-profile state: an entry whose
> `release` matches the running kernel decides applicability, and the import reference
> (URI/name/SHA-256/parent) stays transient. The old reference keys are migrated once and
> deleted.

Also mark its export-lineage references superseded (the "Editor" bullet and the
`# ghostlock-profile parent=…` header format): exports no longer generate the header (§11.8).

`external-profile-unreadable-reporting.md` records the 2026-11-12 SAF inode-swap failure; its
SAF read-failure reporting is retired, and the read-failure state is repurposed for the
app-private copy. The CN MIUI provider finding is in
`external/port/external-profile-picker-2026-11-19/README.md` and is a web inference, not measured
on the phone.

## 8. Touch points

| file | change |
|---|---|
| `app/.../data/ExternalProfileStore.kt` | `read(uri)` for the one import read; `writeImportedProfile(text)` (atomic), `readImportedProfile()`, `clearImportedProfile()`, `importedProfileExists()` under `filesDir/profiles/`; remove `persistReadPermission`, `displayName`, and `ProfileLineage` (object + tests — the copy preserves any header as text) |
| `app/.../data/AndroidProfileConfigController.kt` | import flow in `selectExternalProfile`; `loadSource` reads the copy; entry selection by release; the one-time migration; the unversioned-copy schema upgrade; clear in `clearExternalProfile`/`selectBuiltin`; remove `storedExternalSelection`, the `ProfileLineage.parse` call, and the `ProfileSelection.External` name/URI/SHA/parent fields |
| `app/.../data/LegacyProfileConverter.kt` | comment/naming only: say "unversioned" vs "versioned", not "v1/v2" (no behavioral change) |
| `app/.../domain/repository/ProfileConfigController.kt`, `GhostlockRepository.kt` | replace `activeExternalProfile(): ExternalProfileInfo?` with the copy-exists/read-failure surface |
| `app/.../ui/AdvancedUI.kt` | remove the SAF-error banner and its trigger; the source card shows only the kind; the editor's source banner (`AdvancedUI.kt:572`) uses the kind; the clear action keys on `importedProfileExists()`; replace the builtin picker's `activeUserProfile` checks with `profileLayers.externalApplied` |
| `app/.../ui/GhostlockUI.kt`, `GhostlockModels.kt`, `GhostlockViewModel.kt`, `AndroidGhostlockRepository.kt` | add the early blocked branch in the status card; keep a read-failure boolean for the copy; remove the URI/name/SHA/parent fields and `ExternalProfileInfo`; the support predicate uses copy existence; update `layerNoticeBody` (external clause) and `ProfileLayers.describe()`; remove `withLineage` from the three export paths and use the fixed copy name for the editor target label |
| `app/src/main/res/values{,-zh}/strings.xml` | remove the strings listed in §5; add `profile_kind_external`, `profile_kind_builtin`, `imported_profile_not_applicable_summary` |
| `app/src/test/...` | the tests in §9; update `FakeExternalProfileStore`, `ProfileLayerPolicyTest`, `ExternalProfileStoreTest`; add `ImportedProfileStoreTest` |
| `docs/kernel_profiles/PROFILE_SCHEMA.md`, `PROFILE_SCHEMA_ZH.md` | document `schema_version` 1 vs 2 (the table still says "Always 1") |
| `docs/analysis/measured-db-plan.md` | the §7 amendment; mark the export-lineage comment superseded |
| `docs/analysis/external-profile-unreadable-reporting.md` | mark its SAF reporting superseded; the read-failure state is repurposed for the copy |

No native or wire files.

## 9. Tests and gates

Host unit tests:

1. Import copies the document; a later `load()` after the SAF URI becomes unreadable still
   succeeds (the CN MIUI sequence).
2. A new controller instance (restart) loads the copy.
3. `clearExternalProfile()` / `selectBuiltin()` delete the copy; the next load has no profile.
4. A new import overwrites the old copy; one file exists.
5. Migration: with the old keys and no copy, a readable URI writes the copy and deletes the
   keys; an unreadable URI deletes the keys and leaves 需要加载配置.
6. After import, no reference keys are written and the source card renders 外部配置 only. A
   readable copy with no entry for the release and a copy with invalid fields surface as
   配置需要修正; an unreadable copy surfaces as 配置无法读取; in both cases the clear action is
   visible.
7. §6: an unversioned copy gets `schema_version` by the map rule (maps → 2, else 1) and is
   rewritten in place on first load (lineage kept, second load a no-op); a v1/v2 copy is left
   alone; added keys default; a missing required key is reported and the old copy kept; a schema
   > 2 is refused; a rewrite failure uses the in-memory conversion and retries.
8. A release-mismatched copy blocks the run, shows the early blocked state (not 不支持), and
   keeps the existing cancel action; clearing it applies the builtin/bundled profile.
9. The existing external-selection, layer-policy and merge tests stay green with the retired
   assertions updated.

Device gate (any device; the copy path is provider-independent):

- Import and steady state: select a profile through the system picker (success toast, no
  unreadable banner); navigate away and back; force-stop and restart. The home reads 受支持 /
  配置已就绪 and the source card 外部配置. Then make the source unreadable — delete or rename
  the SD-card file, or revoke the grant — force-stop and restart again: the home still reads
  受支持 / 配置已就绪 from the copy. This is the sequence the CN MIUI provider fails today.
- Upgrade: install the new build over the current one with the selection present. If the old URI
  is still readable, the one-time migration writes the copy and the selection survives. If it is
  not (the CN MIUI case), the migration deletes the old keys and the app shows the re-import
  state (no copy, clear action hidden).
- Clear / select builtin: the copy is deleted and the builtin/bundled source applies.
- Diting regression: the existing selection migrates once or the user re-imports; a fresh import
  copies. The reporter's phone is not needed; the provider-specific observation stays in
  `external/port/external-profile-picker-2026-11-19/README.md`.

Gates: `:app:testDebugUnitTest`, `:profile-core:test`, `:app:assembleDebug`. No native gates.

## 10. Operational rules

1. Cover every copy deletion path: clear, builtin selection, new import (overwrite), uninstall.
2. Keep the imported copy app-private. The run log records only the source kind and the reason
   it was not applied; the run store's effective-profile copy (`profile.conf`/`profile.bin`) is
   the reproducible artifact.
3. Keep the schema contract: every shipped schema has a parser or a converter; added keys have
   safe defaults or a schema bump.
4. The user re-imports after editing the SD-card source; the source card shows only the kind.
5. The migration deletes the old reference keys; after it, the imported copy is the only
   external-profile state.

## 11. Decisions (resolved 2026-11-19)

1. **Migration:** read the old URI once; import if it yields the running release; delete all old
   keys either way (including `external_profile_parent_sha256`).
2. **File name:** `imported_profile.conf`, one fixed name.
3. **Missing copy:** the ordinary 需要加载配置 state.
4. **Old UI/state:** keep the read-failure state (`kernel_external_unreadable`,
   `run_blocked_external_unreadable`) for the app-private copy; remove the SAF-reference strings
   and the name/URI/SHA/parent fields; a log line may remain.
5. **Grant:** remove `takePersistableUriPermission` and the URI prefs.
6. **Dormant copy:** keep the existing cancel action visible whenever the copy exists (file
   existence, no persisted field); show only the kind; use the existing status surfaces plus one
   new summary. No extra button, no automatic deletion. A copy that does not apply stays the live
   selection, fails verification and blocks the run until the user clears it; clearing applies the
   builtin/bundled profile.
7. **Schema:** the copy is schema 1 (scalar/absent phys) or 2 (per-device maps); no export is
   up-converted. An unversioned copy gets the key on first load by the map rule; schema > 2 is
   refused.
8. **Lineage:** exports carry no generated parent header; the imported copy is kept verbatim, so
   a header the source document already carries survives as text; the app does not parse it.

## 12. Implementation record (2026-11-19)

Implemented on `document` (uncommitted at first cut):

- **Store** — `ExternalProfileStore` now has `read(uri)` (the one import read),
  `readImportedProfile()`, `writeImportedProfile()` (same-directory temp + `fsync` + `rename`),
  `clearImportedProfile()` and `importedProfileExists()`. `ImportedProfileSchema` adds
  `schema_version` by the map rule on first read; `ProfileLineage` and the persisted grant are
  gone.
- **Controller** — the SAF document is read once in `selectExternalProfile` and written to
  `filesDir/profiles/imported_profile.conf`; `loadSource` reads the copy; the old
  `external_profile_*` keys are migrated once (copy wins if both exist); clear/select-builtin
  delete the copy. `ProfileSelection.External` is now a marker; `ProfileLayers` no longer carries
  name/URI/SHA/parent, and the read-failure boolean is kept for the copy.
- **UI** — the status card's early blocked branch before `missing` (配置需要修正 for a readable
  copy that does not apply, 配置无法读取 for an unreadable one); the source card shows only the
  kind; the Parameters top banner shows the copy kind before the no-profile branch; the clear
  action keys on file existence; exports no longer prepend a lineage header.
- **Docs** — `PROFILE_SCHEMA(_ZH)` documents `schema_version` 1/2; `measured-db-plan` is amended;
  the unreadable-reporting doc is marked superseded.
- **Gates** — `:app:testDebugUnitTest` 281 tests, 0 failures (1 skipped);
  `:profile-core:test`; `:app:assembleDebug` → `GhostLock-v1.2(759)-arm64-v8a-debug.apk`
  (final md5 `2cfbb6de…`; the gate's first cut was `72882e26…` before the Parameters-banner
  order fix). New tests: import survives the source disappearing, import writes no
  reference keys, migration (readable, unreadable, stale-key) cases, per-entry schema upgrade,
  lineage-header preservation, the copy store's atomic overwrite/clear, and the field-evolution
  cases: optional `pipeline` absent (loads, no invalid paths), `execution` block absent (the
  preset defaults, `route_wait_ms = 30`), and required `offset.init_task` absent (`invalidPaths`
  contains it — flagged, not defaulted).
- **Device gate — passed on the diting (2026-11-19).** Build 759 installed over 756 with the
  SAF selection present: the migration wrote the copy (SHA-256 `5e83cd93…`, byte-identical to
  the old selection), deleted the old keys, and the home read 受支持 / 配置已就绪. Renaming the
  source and force-stop/restarting still read the copy; the system-picker import, the clear
  action (copy deleted, builtin applies) and a re-import all behaved. The final APK also
  re-ran the migration from a seeded old selection (4 keys → 0, copy `5e83cd93…`) and the
  source-unreadable restart, and exercised three state cases: **migration fail** (dead old
  URI → 0 keys, no copy, bundled 内置配置), **schema newer than 2** (v3 copy kept, home
  配置需要修正 + the new summary, source 外部配置) and **kernel changed** (copy release
  `9.9.9-unknown`, same blocked state, copy untouched). A copy missing the required
  `offset.init_task` showed the invalid-fields branch (配置需要修正 / 请修正标记的配置项),
  distinct from the copy-blocked summary. Evidence:
  [`external/port/import-copy-gate-2026-11-19/`](../../../port/import-copy-gate-2026-11-19/README.md).
