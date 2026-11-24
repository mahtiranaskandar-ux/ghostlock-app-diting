// SPDX-License-Identifier: Apache-2.0

//! `timer-extract`: check whether a kernel still carries CVE-2026-98260 and
//! derive its cleaned `timer` fragment (`kernel` facts + `timer`) from the
//! image (and, optionally, runtime facts measured on the same kernel).
//!
//! Exit codes: 0 vulnerable, 3 kernel carries the upstream fix, 1 error.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::process::ExitCode;

use clap::Parser;

use timer_extract::derive::{analyze, facts_from_analysis, Options, Verdict};
use timer_extract::facts::RuntimeFacts;
use timer_extract::kbuilds;
use timer_extract::report::{
    render_document, render_superset, render_text, render_timer_conf, Endgame,
};

#[derive(Parser, Debug)]
#[command(
    name = "timer-extract",
    about = "Derive CVE-2026-98260 per-build constants from an Android boot.img / arm64 Image",
    after_help = "examples:\n  \
        timer-extract boot.img                       # full analysis (BTF layout when present)\n  \
        timer-extract boot.img --detect-only         # the two vulnerability checks only\n  \
        timer-extract boot.img --no-layout           # sites + data + checks, no layout\n  \
        timer-extract boot.img --layout-template 5.10 --runtime-facts facts.txt\n  \
        timer-extract boot.img --emit-timer-conf --endgame real\n  \
        timer-extract boot.img --emit-superset --runtime-facts facts.txt\n  \
        timer-extract boot.img --emit-document --endgame real,fake\n  \
        timer-extract boot.img --add                 # register the lab profile row\n\n\
        exit codes: 0 vulnerable, 3 kernel patched, 1 error."
)]
struct Cli {
    /// boot.img, raw arm64 Image, or gzip Image
    image: PathBuf,
    /// insert (or refresh) the profile row in --kbuilds (lab corpus; needs BTF)
    #[arg(long)]
    add: bool,
    /// build registry to edit with --add
    #[arg(long, default_value = kbuilds::REGISTRY_PATH)]
    kbuilds: PathBuf,
    /// run only the two vulnerability checks (no BTF, no sites, no layout)
    #[arg(long, conflicts_with_all = ["add", "emit_timer_conf", "emit_superset", "emit_document"])]
    detect_only: bool,
    /// derive sites/data/checks without the struct layout (alias --sites)
    #[arg(long, alias = "sites")]
    no_layout: bool,
    /// labelled no-BTF layout template (5.10)
    #[arg(long, value_name = "NAME", conflicts_with = "no_layout")]
    layout_template: Option<String>,
    /// explicit layout override, repeatable: --layout k_itimer_node_off=0x78
    #[arg(long = "layout", value_name = "KEY=VALUE", conflicts_with = "no_layout")]
    layout: Vec<String>,
    /// runtime facts file (QEMU guest / device): slab lines, config, probe, leak
    #[arg(long, value_name = "FILE")]
    runtime_facts: Option<PathBuf>,
    /// constant set the emitted block carries: real (default), fake, real,fake
    #[arg(long, value_name = "SPEC")]
    endgame: Option<String>,
    /// print the cleaned `timer` fragment as HOCON (to stdout, or to PATH)
    #[arg(long, num_args = 0..=1, value_name = "PATH")]
    emit_timer_conf: Option<Option<PathBuf>>,
    /// print the timer evidence object for the store's superset (to stdout, or to PATH)
    #[arg(long, num_args = 0..=1, value_name = "PATH")]
    emit_superset: Option<Option<PathBuf>>,
    /// print the canonical backend document, timer-only projection (to stdout, or to PATH)
    #[arg(long, num_args = 0..=1, value_name = "PATH")]
    emit_document: Option<Option<PathBuf>>,
    /// force the human report even when an emission owns stdout
    #[arg(long)]
    report: bool,
}

fn parse_layout(values: &[String]) -> anyhow::Result<BTreeMap<String, u64>> {
    let mut out = BTreeMap::new();
    for item in values {
        let (key, value) = item
            .split_once('=')
            .ok_or_else(|| anyhow::anyhow!("--layout {item}: expected key=value"))?;
        let trimmed = value.trim();
        let parsed = if let Some(hex) = trimmed
            .strip_prefix("0x")
            .or_else(|| trimmed.strip_prefix("0X"))
        {
            u64::from_str_radix(hex, 16)
        } else {
            trimmed.parse()
        }
        .map_err(|_| anyhow::anyhow!("--layout {item}: {value} is not a number"))?;
        out.insert(key.trim().to_string(), parsed);
    }
    Ok(out)
}

fn write_artifact(path: Option<&PathBuf>, text: &str) -> anyhow::Result<()> {
    match path {
        Some(path) => {
            std::fs::write(path, text)
                .map_err(|err| anyhow::anyhow!("cannot write {}: {err}", path.display()))?;
            eprintln!("wrote {}", path.display());
        }
        None => print!("{text}"),
    }
    Ok(())
}

fn run(cli: Cli) -> anyhow::Result<i32> {
    if !cli.image.is_file() {
        anyhow::bail!("no such image: {}", cli.image.display());
    }
    let endgame = match &cli.endgame {
        Some(spec) => Endgame::parse(spec)
            .ok_or_else(|| anyhow::anyhow!("--endgame {spec}: expected real, fake or real,fake"))?,
        None => Endgame::Real,
    };
    let runtime = match &cli.runtime_facts {
        Some(path) => Some(RuntimeFacts::parse(&std::fs::read_to_string(path).map_err(
            |err| anyhow::anyhow!("cannot read {}: {err}", path.display()),
        )?)?),
        None => None,
    };
    let options = Options {
        detect_only: cli.detect_only,
        no_layout: cli.no_layout,
        layout_template: cli.layout_template.clone(),
        overrides: parse_layout(&cli.layout)?,
        runtime,
    };
    let analysis = analyze(&cli.image, &options)?;

    // --- profile registration (the lab corpus) -----------------------------
    let profile_status;
    if cli.add {
        let facts = facts_from_analysis(&analysis)?;
        let done = kbuilds::apply(&cli.kbuilds, &facts)?;
        let mut status = format!("{} {}", done.file.label(), done.path.display());
        if done.added_to_registry {
            status.push_str(", registered");
        }
        profile_status = status;
    } else {
        let ident = analysis
            .release
            .as_deref()
            .map(kbuilds::identifier)
            .unwrap_or_default();
        let registry = std::fs::read_to_string(&cli.kbuilds).unwrap_or_default();
        profile_status = if !ident.is_empty() && kbuilds::is_registered(&registry, &ident) {
            "registered".to_string()
        } else {
            format!("missing from {} (re-run with --add)", cli.kbuilds.display())
        };
    }

    // --- emissions ---------------------------------------------------------
    let mut stdout_owned = false;
    if let Some(path) = &cli.emit_timer_conf {
        let block = render_timer_conf(&analysis, endgame)?;
        write_artifact(path.as_ref(), &block)?;
        stdout_owned |= path.is_none();
    }
    if let Some(path) = &cli.emit_superset {
        let object = render_superset(&analysis);
        let text = serde_json::to_string_pretty(&object)?;
        write_artifact(path.as_ref(), &format!("{text}\n"))?;
        stdout_owned |= path.is_none();
    }
    if let Some(path) = &cli.emit_document {
        let document = render_document(&analysis, endgame)?;
        let text = serde_json::to_string_pretty(&document)?;
        write_artifact(path.as_ref(), &format!("{text}\n"))?;
        stdout_owned |= path.is_none();
    }
    if !stdout_owned || cli.report {
        let text = render_text(&analysis, &cli.image, &profile_status);
        if !stdout_owned {
            print!("{text}");
        } else {
            eprintln!("{text}");
        }
    }

    Ok(match analysis.verdict {
        Verdict::Vulnerable => 0,
        Verdict::Patched => 3,
        Verdict::Unknown(_) => 1,
    })
}

fn main() -> ExitCode {
    match run(Cli::parse()) {
        Ok(code) => ExitCode::from(code as u8),
        Err(err) => {
            eprintln!("error: {err}");
            ExitCode::from(1)
        }
    }
}
