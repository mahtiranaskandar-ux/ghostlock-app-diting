//! Shared harness for the option-superset corpus.
//!
//! The store keeps one `options-<release>.json` per kernel; the tests run the
//! maximal extractor invocation (every sidecar, no route forced) and compare
//! the whole document. The corpus is meta-driven: every directory with a
//! `meta.txt` and an `Image-*` is a candidate, and `corpus = <kind>` takes it
//! out by policy (`excluded`, `btf-only`, `codegen`).

#![allow(dead_code)]

use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::Ordering;

#[derive(Clone, Debug)]
pub struct Meta {
    pub dir: PathBuf,
    pub release: String,
    pub corpus: Option<String>,
    pub phys: Option<u64>,
    /// Phone codename the entry's sidecars belong to; drives the --device cut.
    /// This is the primary device (the first of [devices]).
    pub device: Option<String>,
    /// Every device sidecar set under `devices/<codename>/`, in meta order.
    /// Empty on a legacy root-sidecar entry.
    pub devices: Vec<String>,
    pub image: Option<PathBuf>,
}

impl Meta {
    pub fn image(&self) -> &Path {
        self.image.as_deref().expect("corpus entry has an image")
    }
}

fn parse_phys(text: &str) -> Option<u64> {
    let text = text.trim();
    match text
        .strip_prefix("0x")
        .or_else(|| text.strip_prefix("0X"))
    {
        Some(hex) => u64::from_str_radix(hex, 16).ok(),
        None => text.parse().ok(),
    }
}

/// Parse one entry directory's `meta.txt`. Returns `None` when it carries no
/// release line (not a kernel entry).
pub fn parse_meta(dir: &Path) -> Option<Meta> {
    let text = std::fs::read_to_string(dir.join("meta.txt")).ok()?;
    let mut release = None;
    let mut corpus = None;
    let mut phys = None;
    let mut device = None;
    let mut devices: Vec<String> = Vec::new();
    let mut image = None;
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        let value = value.trim();
        match key.trim() {
            "release" => release = Some(value.trim_matches('"').to_string()),
            "corpus" => corpus = Some(value.to_string()),
            "phys" => phys = parse_phys(value),
            "device" => device = Some(value.trim_matches('"').to_string()),
            "devices" => devices.extend(
                value
                    .split(',')
                    .map(|part| part.trim().trim_matches('"').to_string())
                    .filter(|part| !part.is_empty()),
            ),
            "image" => image = Some(dir.join(value)),
            _ => {}
        }
    }
    let release = release?;
    if image.as_ref().is_none_or(|path| !path.is_file()) {
        image = None;
        if let Ok(entries) = std::fs::read_dir(dir) {
            let mut candidates: Vec<PathBuf> = entries
                .flatten()
                .map(|entry| entry.path())
                .filter(|path| {
                    path.file_name()
                        .and_then(|name| name.to_str())
                        .is_some_and(|name| name.starts_with("Image-"))
                })
                .collect();
            candidates.sort();
            if candidates.len() == 1 {
                image = candidates.pop();
            }
        }
    }
    /* The directory shape is authoritative too: an entry migrated to
     * `devices/<codename>/` but whose meta has not gained the `devices` line
     * still runs every device set. */
    if devices.is_empty() {
        if let Ok(entries) = std::fs::read_dir(dir.join("devices")) {
            let mut names: Vec<String> = entries
                .flatten()
                .filter(|entry| entry.path().is_dir())
                .filter_map(|entry| entry.file_name().to_str().map(str::to_string))
                .collect();
            names.sort();
            devices = names;
        }
    }
    if let Some(primary) = &device {
        if !devices.iter().any(|name| name.eq_ignore_ascii_case(primary)) {
            devices.insert(0, primary.clone());
        }
    }
    let device = device.or_else(|| devices.first().cloned());
    Some(Meta {
        dir: dir.to_path_buf(),
        release,
        corpus,
        phys,
        device,
        devices,
        image,
    })
}

/// Every image-bearing entry under `root`, minus the ones whose `meta.txt`
/// marks them out of the profile corpus. The marker is the single policy;
/// filename prefixes are not.
pub fn collect_all(root: &Path) -> Vec<Meta> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(root) else {
        return out;
    };
    let mut dirs: Vec<PathBuf> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.is_dir())
        .collect();
    dirs.sort();
    for dir in dirs {
        if !dir.join("meta.txt").is_file() {
            continue;
        }
        let Some(meta) = parse_meta(&dir) else {
            continue;
        };
        if let Some(kind) = &meta.corpus {
            eprintln!("skip (corpus {kind}): {}", dir.display());
            continue;
        }
        if meta.image.is_none() {
            eprintln!("skip (no image): {}", dir.display());
            continue;
        }
        out.push(meta);
    }
    out
}

/// The sidecars the entry carries, in the extractor's resolution order. The
/// extractor picks the one that carries the map and warns about the rest, so
/// passing every present file also exercises the fallback order.
pub fn sidecar_args(dir: &Path) -> Vec<std::ffi::OsString> {
    let mut args = Vec::new();
    for (file, flag) in [
        ("vendor_boot.img", "--vendor-boot"),
        ("xbl_config.img", "--xbl-config"),
        ("xbl_config.elf", "--xbl-config"),
        ("uefi.img", "--uefi"),
        ("dtbo.img", "--dtbo"),
    ] {
        let path = dir.join(file);
        if path.is_file() {
            args.push(flag.into());
            args.push(path.into_os_string());
        }
    }
    args
}

pub struct Run {
    pub exit: Option<i32>,
    pub stdout: String,
    pub stderr: String,
    pub args: Vec<String>,
}

/// The sidecar directory for one device: `devices/<codename>/` when it exists,
/// else the entry root (a legacy or not-yet-migrated single set).
pub fn sidecar_dir_for(meta: &Meta, device: Option<&str>) -> PathBuf {
    if let Some(device) = device {
        let path = meta.dir.join("devices").join(device);
        if path.is_dir() {
            return path;
        }
    }
    meta.dir.clone()
}

fn build_command(bin: &str, meta: &Meta, sidecar_dir: &Path) -> Command {
    let mut command = Command::new(bin);
    command.arg(meta.image());
    let sidecars = sidecar_args(sidecar_dir);
    if sidecars.is_empty() {
        if let Some(phys) = meta.phys {
            command.arg("--phys").arg(format!("0x{phys:x}"));
        }
    } else {
        command.args(sidecars);
    }
    command
}

/// The store's single golden per kernel: the option superset document.
pub fn options_path(meta: &Meta) -> PathBuf {
    meta.dir.join(format!("options-{}.json", meta.release))
}

/// Run the maximal invocation: every sidecar, `--options-out <out>`, and a
/// temporary conf profile. The route is left unforced, so an ambiguous image
/// exits 7 after writing the document.
///
/// An entry with `devices/<codename>/` sets runs the extractor once per device
/// and merges the per-device derivations into one superset: the first device's
/// run supplies the shared sections, `document.devices` collects every pair in
/// the schema shape, and `device_analysis` collects the per-pair evidence.
pub fn run_options(bin: &str, meta: &Meta, out: &Path) -> Run {
    if meta.devices.is_empty() {
        return run_options_one(bin, meta, &meta.dir, None, out);
    }
    let mut base: Option<serde_json::Value> = None;
    let mut analysis: Vec<serde_json::Value> = Vec::new();
    /* The document is the one home of the physical pair, keyed by codename in
     * the schema shape. Each per-device run carries its own entry; the merged
     * document carries all of them. */
    let mut pairs = serde_json::Map::new();
    let mut last: Option<Run> = None;
    for device in &meta.devices {
        let dir = sidecar_dir_for(meta, Some(device));
        let stem = out
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("options.json");
        let tmp = out.with_file_name(format!("{stem}.{device}.tmp"));
        let run = run_options_one(bin, meta, &dir, Some(device), &tmp);
        let ok = matches!(run.exit, Some(0) | Some(7));
        if !ok || !tmp.is_file() {
            let _ = std::fs::remove_file(&tmp);
            return run;
        }
        let text = std::fs::read_to_string(&tmp).unwrap_or_else(|err| {
            panic!("{}: cannot read {device} superset: {err}", meta.release)
        });
        let _ = std::fs::remove_file(&tmp);
        let value: serde_json::Value = serde_json::from_str(&text).unwrap_or_else(|err| {
            panic!("{}: invalid {device} superset JSON: {err}", meta.release)
        });
        let entries = value
            .get("device_analysis")
            .and_then(|entries| entries.as_array())
            .cloned()
            .unwrap_or_default();
        analysis.extend(entries);
        /* A device with no derived pair still appears in the document map with
         * a null pair (the native's compiled default applies). */
        let doc_devices = value
            .get("document")
            .and_then(|document| document.get("devices"))
            .and_then(|devices| devices.as_object());
        let names: Vec<String> = value
            .get("device_analysis")
            .and_then(|entries| entries.as_array())
            .into_iter()
            .flatten()
            .filter_map(|entry| entry.get("device").and_then(|name| name.as_str()))
            .map(str::to_string)
            .collect();
        for name in names {
            let entry = doc_devices
                .and_then(|devices| devices.get(&name))
                .cloned()
                .unwrap_or_else(|| {
                    serde_json::json!({"phys": {"load": null, "offset": null}})
                });
            pairs.insert(name, entry);
        }
        match &base {
            None => base = Some(value),
            Some(base_value) => {
                for key in [
                    "release",
                    "kernel_major",
                    "routes",
                    "pipeline_facts",
                    "pipelines",
                ] {
                    assert_eq!(
                        base_value.get(key),
                        value.get(key),
                        "{}: superset section {key:?} differs between devices",
                        meta.release
                    );
                }
                /* The pair is the only per-device part of the document; every
                 * other block is image-level and must agree. */
                assert_eq!(
                    without_devices(base_value.get("document").unwrap_or(&base_value)),
                    without_devices(value.get("document").unwrap_or(&value)),
                    "{}: the document differs between devices outside `devices`",
                    meta.release
                );
            }
        }
        last = Some(run);
    }
    let mut base = base.expect("at least one device");
    base["device_analysis"] = serde_json::Value::Array(analysis);
    if !pairs.is_empty() {
        if let Some(document) = base.get_mut("document").and_then(|d| d.as_object_mut()) {
            document.insert("devices".to_string(), serde_json::Value::Object(pairs));
        }
    }
    let text = serde_json::to_string_pretty(&base).expect("serialize merged superset");
    std::fs::write(out, text + "\n").expect("write merged superset");
    last.expect("at least one device run")
}

/// A document with its per-device pair map blanked, for the cross-device
/// equality check. A non-object document is returned unchanged.
fn without_devices(document: &serde_json::Value) -> serde_json::Value {
    let mut document = document.clone();
    if let Some(map) = document.as_object_mut() {
        map.insert("devices".to_string(), serde_json::Value::Null);
    }
    document
}

fn run_options_one(bin: &str, meta: &Meta, sidecars: &Path, device: Option<&str>, out: &Path) -> Run {
    let mut command = build_command(bin, meta, sidecars);
    let profile = out.with_extension("conf.tmp");
    command
        .arg("--format")
        .arg("conf")
        .arg("--out")
        .arg(&profile)
        .arg("--options-out")
        .arg(out);
    if let Some(device) = device {
        command.arg("--device").arg(device);
    }
    let args: Vec<String> = command
        .get_args()
        .map(|arg| arg.to_string_lossy().into_owned())
        .collect();
    let output = command.output().expect("run extractor");
    let _ = std::fs::remove_file(&profile);
    Run {
        exit: output.status.code(),
        stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
        args,
    }
}

/// The no-disassembly control for one route (negative test for a foreign
/// family constant leaking into another family's profile).
pub fn run_no_disasm(bin: &str, meta: &Meta, route: &str) -> Run {
    let dir = sidecar_dir_for(meta, meta.device.as_deref());
    let mut command = build_command(bin, meta, &dir);
    command
        .arg("--format")
        .arg("conf")
        .arg("--route")
        .arg(route)
        .arg("--no-disasm");
    let args: Vec<String> = command
        .get_args()
        .map(|arg| arg.to_string_lossy().into_owned())
        .collect();
    let output = command.output().expect("run extractor");
    Run {
        exit: output.status.code(),
        stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
        args,
    }
}

/// Run `f` over `items` on a small worker pool. Each entry's extractor
/// invocations are independent subprocesses, so the corpus tests parallelize
/// for the same reason the generator does. Panics propagate when the scope
/// joins, after the remaining items have finished.
pub fn par_for_each<T: Sync, F: Fn(&T) + Sync>(items: &[T], workers: usize, f: F) {
    use std::sync::atomic::AtomicUsize;
    let next = AtomicUsize::new(0);
    let workers = workers.min(items.len()).max(1);
    std::thread::scope(|scope| {
        for _ in 0..workers {
            scope.spawn(|| loop {
                let index = next.fetch_add(1, Ordering::Relaxed);
                let Some(item) = items.get(index) else {
                    break;
                };
                f(item);
            });
        }
    });
}

/// The value at a dotted path in the cleaned profile text. Blocks are
/// `name {` … `}`; leaves are `key = value`. Strings keep their quotes.
pub fn profile_value(text: &str, path: &str) -> Option<String> {
    let parts: Vec<&str> = path.split('.').collect();
    let mut stack: Vec<String> = Vec::new();
    for raw in text.lines() {
        let line = raw.split('#').next().unwrap_or("").trim();
        if line.is_empty() {
            continue;
        }
        if line == "}" {
            stack.pop();
            continue;
        }
        if line.ends_with("{}") {
            continue;
        }
        if let Some(name) = line.strip_suffix('{') {
            stack.push(name.trim().to_string());
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        if stack.len() + 1 == parts.len()
            && stack.iter().zip(parts.iter()).all(|(opened, wanted)| opened == wanted)
            && key.trim() == parts[parts.len() - 1]
        {
            return Some(value.trim().to_string());
        }
    }
    None
}

/// A dotted-path string value without its HOCON quotes.
pub fn profile_string(text: &str, path: &str) -> Option<String> {
    profile_value(text, path).map(|value| value.trim_matches('"').to_string())
}

/// Deep-merge a document fragment into a document; a null in the fragment is
/// "no opinion" and never overwrites, matching the app's `deepMergeValues`.
pub fn merge_document(base: &mut serde_json::Value, patch: &serde_json::Value) {
    let (Some(base), Some(patch)) = (base.as_object_mut(), patch.as_object()) else {
        return;
    };
    for (key, value) in patch {
        match base.get_mut(key) {
            Some(slot) => {
                if value.is_object() && slot.is_object() {
                    merge_document(slot, value);
                } else if !value.is_null() {
                    *slot = value.clone();
                }
            }
            None => {
                base.insert(key.clone(), value.clone());
            }
        }
    }
}
