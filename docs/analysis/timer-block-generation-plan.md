# Timer block in generated profiles — plan (2026-11-22)

**Status 2026-11-23 — implemented and device-checked (generation + picker, 6.1 and 5.10).** The
vendored analyzer is built by Gradle, run by the parse path, and its block is merged when it
validates. On the duChamp the generated document carried a `timer_98260` block field-for-field
identical to the shipped one and the home picker listed `timer_98260（通过 Shizuku）` beside the
four native routes. The timer *run* from a generated profile was not repeated (the block is the
device-gated one and the timer backend already ran from a profile). **On the diting (2026-11-23)
the 5.10 no-BTF path first failed**: the app's template predicate was `release.startsWith("5.10-")`
and no release spells it that way, so the analyzer refused ("the fake-path profile is incomplete")
and generation stayed native-only. Fixed with `timerLayoutTemplate()` (`5.10.` prefix, unit-tested);
re-parsing the diting's own `boot_a` then derived the block and it matched the shipped asset block.
Record: `external/port/timer-generation-5x-2026-11-23/`. The operator decision: "Add timer to
generated profiles" (2026-11-22). Parent:
[`timer-w3-app-delivery-plan.md`](timer-w3-app-delivery-plan.md) and
[`route-superset-plan.md`](route-superset-plan.md).

## 1. Problem

A profile generated on the device is native-only. The `timer_98260` block has so far come from
the store overlay (the timer extractor's `--emit-superset` object merged into
`options-*.json`), so only the bundled timer assets carry it. A user who generates a profile for
a kernel the timer analyzer supports never sees the timer route even when the tool can derive it.

## 2. Measured facts (2026-11-22)

- The timer extractor (`tools/extract` in the absorb work tree, R1/R3b-patched) cross-builds for
  `aarch64-linux-android` with the host Rust 1.99 + ONDK r30.1 clang: 1.74 MB, stripped, no
  ONDK `+ondk` toolchain needed.
- **On the duChamp**, run as the shell uid from `/data/local/tmp` on the store's 6.1.138 Image:
  **1.17 s**, exit 0, and `--emit-timer-conf --endgame real,fake` produced a block **byte-identical
  to the shipped, device-gated 6.1.138 asset block**.
- On the host, the same binary on the diting 5.10.236 Image with `--layout-template 5.10`
  (the labelled no-BTF template) reproduced the shipped 5.10.236 block **byte-for-byte**.
- The tool's `--emit-superset` object carries only the *real* block ("until the fake endgame is
  verified"); `--emit-timer-conf --endgame real,fake` is the way-2 block.

## 3. Design

- **Vendor the crate** at `tools/timer_extract/` (Apache-2.0, NOTICE retained; provenance in its
  README). No change to `tools/extract_rs`: the two analyzers stay separate, as the store's
  composition already assumes.
- **Build**: a Gradle `buildTimerExtract` Exec task mirroring `buildGhostlockExtract`, output
  `build/timer-extract/aarch64-linux-android/release/timer-extract`, copied to the jniLibs as
  `libtimerextract.so` (an executable packaged under the native-library dir, like `libextract.so`).
- **Invocation**: in `AndroidGhostlockRepository.parseSource`, inside the parse run-log context,
  after a main-extractor exit 0, on the same effective input:
  `libtimerextract.so <image> --emit-timer-conf <tmp> --endgame real,fake`, plus
  `--layout-template 5.10` when the document's release starts with `5.10.` (the tool applies the
  template only when the image has no BTF). Timeout 180 s; the tool's lines join the parse log;
  the emitted block is a parse sidecar (`timer-block.conf`).
- **Merge**: append the block to the generated document **only when the app's own
  `Timer98260.parse` returns no errors**. Any failure (missing binary, timeout, non-zero exit,
  unparsable block) logs a line and leaves the document native-only — generation never fails
  because of the timer.
- **Viability is unchanged**: the picker offers the timer only when the block parses with the
  complete fake group (`fakeSupported`); a real-only block is inert. A generated profile that
  carries the block therefore shows the timer exactly like the bundled assets do.

## 4. Safety and limits

- The generated block is **derived, not device-gated** for arbitrary kernels. The two shipped
  blocks were gated on their devices; for any other kernel the derivation is the tool's. The
  timer route's one-attempt-per-boot contract, the run-start log lines and the reboot-per-miss
  rules are the protection; the app never runs the timer silently.
- A 5.10 image without BTF uses the labelled template (the diting's exact path). A 5.15 no-BTF
  image gets no template and therefore no usable block (fail-closed).
- Only the *document* gains the block; the native wire and the native-document golden do not move
  (the wire never carries `timer_98260`).

## 5. Work items

1. Vendor + Gradle task + jniLibs copy.
2. Repository: run + merge + sidecar + non-fatal failures.
3. Unit test: the merge helper accepts a valid block and rejects a malformed one (pure Kotlin,
   no binary).
4. Device check: parse the duChamp image on the device → the home picker shows `timer_98260`;
   the block matches the shipped one. A timer *run* from a generated profile is optional (the
   device is rooted after the current session; it needs a fresh boot).
5. Docs: `PROFILE_SCHEMA` §the timer block note that generation emits it; the route-superset
   plan's "generated profiles carry no timer" line retires.

## 6. Non-goals

- Porting the timer analysis into `tools/extract_rs`.
- Changing the store/asset overlay or the timer tool's runtime profile loader.
- Emitting runtime facts (`--runtime-facts`) during generation; both measured targets derive
  fully from the image/BTF/template.
