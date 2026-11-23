# timer-extract (vendored)

Derives the CVE-2026-98260 `timer_98260` profile block (real and fake endgames) from an
Android boot image / raw arm64 Image. This is the timer route's generation-side analyzer,
vendored into the app build so an on-device generated profile can carry the block.

Provenance: YuKongA/CVE-2026-98260, `tools/extract` (Apache-2.0 per its `Cargo.toml` and the
PoC README's license split: MIT for `timer/`, Apache-2.0 for `tools/extract/`), copied on
2026-11-22 from the absorb work tree's current (R1/R3b-patched) source. `NOTICE` is retained.
The app only uses `--emit-timer-conf`; `--add` (the lab registry under `timer/kbuilds.h`) is not
wired.

Consumed by `build.gradle.kts` (`buildTimerExtract` → `libtimerextract.so`) and
`AndroidGhostlockRepository.parseSource`, which merges the emitted block into the generated
document. Design: `docs/analysis/timer-block-generation-plan.md`.
