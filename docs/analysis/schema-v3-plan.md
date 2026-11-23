# Profile schema v3 — one format, no v1/v2 (2026-11-22)

**Status 2026-11-22 — implemented and device-checked.** Operator decision: "it must be v3. And
for this time let's drop the historical burdens — drop v1/v2 support as a whole."

## 1. Decision

`schema_version = 3` is the only accepted profile document format. Everything the app writes
states `3`; a document that states `1`, `2`, or no version is refused on import and on load
(fail-closed, with a `schema_version` error). The v0 `remote/main`-era JSON converter and the
automatic upgrade of unversioned copies are removed with it.

What v3 carries (all of the current format):

- `route.<name>` branches with the `carriers` list (route-choice matrix);
- the ordered `pipeline` list;
- the optional `timer_98260` block;
- the physical pair as a **per-device map** (preferred), an **all-device scalar**, or `null`
  (native default). The old v1/v2 split ("scalar = 1, map = 2") is gone; the scalar form
  survives for the hand-finished, device-less profiles that need it (e.g. the Infinix 6.1.157
  entry).

The names that are **not** this version and did not move:

- the `index.conf` file's own `schema_version = 1` (the bundle index format);
- the extractor's `--options-out` report `schema_version` (still `2`);
- the binary wire version (GLK1 `2`, digest unchanged).

## 2. Emission points (all write 3)

- extractor: `report::render_conf_with_device` (the `--format conf` cut, device or neutral) and
  `options::render_superset`;
- app: `OptionSuperset.cut` (generation cut), `AndroidProfileConfigController.renderMerged` /
  `injectPhysMaps` (export/editor), `ProfileMerger.resolve` (the run's merged document);
- every bundled profile asset (72 files; `index.conf` excepted).

## 3. Removed with v1/v2

- `LegacyProfileConverter` (v0 flat-report conversion) and its test;
- `ImportedProfileSchema.ensureSchema` (the automatic version stamping/upgrade of imported
  copies) and its tests;
- the v0/v1 legs of `ProfileMigrationEquivalenceTest` (it now checks the bundled v3 documents
  through the external path only);
- `StarV1ProfileImportTest` (the 5.4 v1 fixture test);
- the per-device phys rule "maps require `schema_version = 2`" (v3 accepts maps or scalars).

## 4. Rejection

- `ProfileResolver.validateMerged` reports `schema_version: profile schema N is not supported;
  this build reads schema 3`.
- `PerDevicePhys.resolveFor` refuses a document whose schema is not 3 (it runs during
  resolution, so the home/import surfaces the error earlier).
- The UI shows the rejection dialog; the previous selection is restored (an import is
  transactional).

## 5. Gates

- extractor: 158/0 unit; the store corpus regenerated (`generate_options` now **preserves the
  `timer_98260` overlay** across a whole-store run — the second wipe this session is fixed at
  the harness); `options`, `cut`, `route_verdicts` store tests.
- Kotlin: profile-core 73/0 (1 skipped), app 264/0 (1 skipped), `assembleDebug`.
- ship: `--check` 61 bundled, 0 missing, 0 mismatched, 11 exempt.
- Device (duChamp, 6.1.138): the bundled v3 profile loads (`受支持 / 配置已就绪`, route +
  carrier rows); a generated profile exports `schema_version = 3` with the route `carriers`
  lists and the timer block; a v2 copy of that export is rejected with
  `unsupported profile schema 2; this build reads schema 3` and the previous selection is kept.
  Evidence: `external/port/schema-v3-2026-11-22/` (root repo).

## 6. Notes

- The historical analysis docs that describe the old v1/v2 rules are left as records; the
  authoritative description is `docs/kernel_profiles/PROFILE_SCHEMA.md` §2/§11 (EN + ZH).
- A v1/v2 profile can still be moved forward by regenerating it from its image
  (`ghostlock-extract --format conf`) or exporting it from a build that reads it.
