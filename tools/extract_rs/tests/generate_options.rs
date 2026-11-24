//! Regenerate the store's option supersets: one JSON document per kernel.
//! Ignored by default; run explicitly:
//!
//! ```text
//! GHOSTLOCK_IMAGE_DIR=../../../../test-kernels \
//!     cargo test --test generate_options -- --ignored --nocapture
//! ```
//!
//! The invocation is the maximal one (every sidecar, both output flags, no
//! route forced), so ambiguous images still write the document and exit 7.

mod common;

use common::{collect_all, options_path, par_for_each, run_options};
use std::path::Path;

#[test]
#[ignore = "writes the store supersets; run explicitly with --ignored"]
fn generate_option_supersets() {
    let Some(root) = std::env::var_os("GHOSTLOCK_IMAGE_DIR") else {
        panic!("set GHOSTLOCK_IMAGE_DIR to the test-kernel store");
    };
    let binary = env!("CARGO_BIN_EXE_ghostlock-extract");
    let corpus = collect_all(Path::new(&root));
    assert!(
        !corpus.is_empty(),
        "no corpus entry under {}",
        Path::new(&root).display()
    );
    let workers = std::thread::available_parallelism()
        .map(|count| count.get())
        .unwrap_or(4)
        .min(16);
    par_for_each(&corpus, workers, |meta| {
        let out = options_path(meta);
        /* `timer_analysis` is the timer analyzer's overlay, not this
         * extractor's output. Preserve it across the regeneration and merge its
         * cleaned fragment back into the document, or a whole-store run
         * silently drops the timer facts (measured 2026-11-22). */
        let previous_timer = std::fs::read_to_string(&out).ok()
            .and_then(|text| serde_json::from_str::<serde_json::Value>(&text).ok())
            .and_then(|value| value.get("timer_analysis").cloned());
        let run = run_options(binary, meta, &out);
        let text = std::fs::read_to_string(&out).unwrap_or_else(|err| {
            panic!(
                "{}: no option superset: {err}\nstderr:\n{}",
                meta.release, run.stderr
            )
        });
        let mut value = serde_json::from_str::<serde_json::Value>(&text).unwrap_or_else(|err| {
            panic!("{}: invalid superset JSON: {err}", meta.release)
        });
        if let Some(timer) = previous_timer {
            if let Some(fragment) = timer.get("document").cloned() {
                common::merge_document(&mut value["document"], &fragment);
            }
            value["timer_analysis"] = timer;
            let merged = serde_json::to_string_pretty(&value).expect("serialize superset") + "\n";
            std::fs::write(&out, &merged).unwrap_or_else(|err| {
                panic!("{}: cannot preserve timer_analysis: {err}", meta.release)
            });
        }
        eprintln!(
            "generated {} ({}; {} bytes)",
            meta.release,
            if run.exit == Some(7) { "ambiguous" } else { "ok" },
            text.len()
        );
    });
}

