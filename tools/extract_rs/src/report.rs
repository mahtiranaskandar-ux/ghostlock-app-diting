//! Output rendering for extracted kernel metadata.

use serde_json::{Value, json};
use std::collections::BTreeMap;

use crate::derive::Cred5x;
use crate::document;
use crate::error::{ExtractError, Result};
use crate::fact::{Fact, Source};
use crate::layout::LayoutFacts;
use crate::symbols::{OPTIONAL_SYMBOLS, kernel_layout_verified};

pub fn pselect_waiter_shift_for(release: Option<&str>) -> Option<i64> {
    if !kernel_layout_verified(release) {
        return None;
    }
    match crate::symbols::kernel_struct_macro(release) {
        Some("STRUCT_OFFSETS_6_12") => Some(0),
        // android14-6.1 compiles its fd_set words one qword later than
        // 6.6; the committed tables all measure 1.
        Some("STRUCT_OFFSETS_6_1") => Some(1),
        Some("STRUCT_OFFSETS_6_6") => Some(-2),
        _ => None,
    }
}

pub fn build_report(
    release: Option<&str>,
    base: u64,
    phys: Option<u64>,
    symbols: &BTreeMap<String, Option<u64>>,
    structs: &BTreeMap<String, Option<u32>>,
    btf_size: usize,
    pselect_shift: Option<i64>,
) -> Value {
    let symbol_json: BTreeMap<String, Value> = symbols
        .iter()
        .map(|(key, value)| {
            (
                key.clone(),
                match value {
                    Some(v) => json!(v),
                    None => Value::Null,
                },
            )
        })
        .collect();
    let struct_json: BTreeMap<String, Value> = structs
        .iter()
        .map(|(key, value)| {
            (
                key.clone(),
                match value {
                    Some(v) => json!(v),
                    None => Value::Null,
                },
            )
        })
        .collect();
    let mut report = json!({
        "release": release,
        "kimage_text_base": base,
        "kernel_phys_load": phys,
        "pselect_waiter_shift": pselect_shift,
        "symbols": symbol_json,
        "struct_fields": struct_json,
        "btf_size": btf_size,
    });
    if kernel_layout_verified(release)
        && crate::symbols::kernel_struct_macro(release) == Some("STRUCT_OFFSETS_6_1")
    {
        report["compact_waiter"] = json!(1);
    }
    report
}

/// `task_struct` keys in the bundled profiles' order.
pub(crate) const CONF_TASK_FIELDS: &[(&str, &str)] = &[
    ("task_prio", "prio"),
    ("task_normal_prio", "normal_prio"),
    ("task_sched_task_group", "sched_task_group"),
    ("task_pi_lock", "pi_lock"),
    ("task_pi_waiters", "pi_waiters"),
    ("task_pi_top_task", "pi_top_task"),
    ("task_pi_blocked_on", "pi_blocked_on"),
    ("task_pid", "pid"),
    ("task_tgid", "tgid"),
    ("task_atomic_flags", "atomic_flags"),
    ("task_real_cred", "real_cred"),
    ("task_cred", "cred"),
    ("task_comm", "comm"),
    ("task_tasks", "tasks"),
    ("task_seccomp", "seccomp"),
];

/// Full route field universe per branch (authoritative: Kotlin
/// `RouteConfig.entries()` / native `kSections`). A missing value renders as
/// `null` so every generated profile carries every field of its route.
pub(crate) const CONF_ROUTE_FIELDS: &[(&str, &[&str])] = &[
    ("tcp_zerocopy", &["compact_waiter"]),
    ("select_stack", &["waiter_shift"]),
    ("sigreturn", &["waiter_off_sve", "waiter_off_plain"]),
    (
        "multicast_waiter",
        &[
            "waiter_off",
            "buffer_size",
            "task_offset",
            "lock_offset",
            "compact_waiter",
        ],
    ),
];

/// Full credential field universe (the document's `kernel.struct.cred` block).
pub(crate) const CONF_CRED_FIELDS: &[&str] = &[
    "copy_size",
    "usage_offset",
    "usage_value",
    "caps_offset",
    "caps_count",
    "caps_value",
    "ref_count",
    "ref0_offset",
    "ref1_offset",
    "ref2_offset",
    "ref3_offset",
    "ref0_image",
    "ref1_image",
    "ref2_image",
    "ref3_image",
];

/// Full offset field universe (native `kOffset`).
pub(crate) const CONF_OFFSET_FIELDS: &[&str] = &[
    "init_task",
    "init_cred",
    "empty_zero_page",
    "root_task_group",
    "selinux_enforcing",
    "selinux_blob_sizes",
    "security_hook_heads",
    "slide_nfulnl_logger",
    "slide_loggers_0_1",
    "slide_boot_id",
];


/* Stage 3: emit a layout fact only when a per-image source (BTF) supplied
 * it. The release template stays in `--explain` as the labelled fallback;
 * writing it into the profile would turn a heuristic into a shipped value,
 * and the 6.1 template is already known to be 6.6/6.12-shaped. */
pub(crate) fn btf_decimal(value: &Fact<String>) -> Option<String> {
    if value.source != Source::Btf {
        return None;
    }
    let raw = value.value.trim();
    let parsed = match raw.strip_prefix("0x") {
        Some(hex) => u32::from_str_radix(hex, 16).ok()?,
        None => raw.parse::<u32>().ok()?,
    };
    Some(parsed.to_string())
}

pub(crate) fn btf_u32(value: &Fact<u32>) -> Option<String> {
    (value.source == Source::Btf).then(|| value.value.to_string())
}

pub(crate) fn btf_flag(value: &Fact<bool>) -> Option<String> {
    (value.source == Source::Btf).then(|| u8::from(value.value).to_string())
}

/// One `key=value (source/confidence)` entry for the profile's provenance
/// header. This is the audit trail, not machine state: the app's HOCON parser
/// ignores comments.
fn push_fact<T: std::fmt::Display>(out: &mut Vec<String>, key: &str, fact: &Fact<T>) {
    out.push(format!(
        "{key}={} ({}/{})",
        fact.value,
        fact.source.label(),
        fact.confidence.label()
    ));
}

/// Extra symbol values the extractor resolves outside the `off_*` table
/// (kallsyms-only symbols), kept in their bundled-profile position.
#[derive(Debug, Clone, Default)]
pub struct ConfExtraOffsets {
    pub empty_zero_page: Option<u64>,
}

/// The shared 6.x credential template (`kernel.struct.cred`), in the bundled
/// order. The flatten rule inlines it instead of an include line; the values
/// stay pinned by `BuiltinProfilesTest`.
pub fn conf_cred_6x() -> Vec<(String, String)> {
    [
        ("caps_offset", 48),
        ("copy_size", 136),
        ("usage_value", 1),
        ("caps_count", 5),
        ("caps_value", -1),
    ]
    .into_iter()
    .map(|(key, value)| (key.to_string(), value.to_string()))
    .collect()
}

/// The 5.x credential template from the derived `init_cred` values, in the
/// bundled profile order. Reference images are pre-KASLR kernel VAs rendered
/// as signed decimals (the profile's spelling).
pub fn conf_cred_5x(cred: &Cred5x, copy_size: u32) -> Vec<(String, String)> {
    let mut entries: Vec<(String, String)> = vec![
        ("caps_offset".to_string(), cred.caps_offset.to_string()),
        ("copy_size".to_string(), copy_size.to_string()),
        (
            "usage_value".to_string(),
            crate::derive::CRED_5X_USAGE_VALUE.to_string(),
        ),
        ("caps_count".to_string(), cred.caps_count.to_string()),
        ("caps_value".to_string(), cred.caps_value.to_string()),
    ];
    for (index, (offset, _)) in cred.refs.iter().enumerate() {
        entries.push((format!("ref{index}_offset"), offset.to_string()));
    }
    entries.push(("ref_count".to_string(), cred.refs.len().to_string()));
    for (index, (_, image)) in cred.refs.iter().enumerate() {
        entries.push((format!("ref{index}_image"), (*image as i64).to_string()));
    }
    entries
}

/// The selected route's branch geometry, or an empty list when the extractor
/// cannot derive a layout for it (the built-in profile then supplies it).
pub fn conf_route_geometry(
    route: &str,
    release: &str,
    pselect_shift: Option<i64>,
    waiter_compact: bool,
) -> Vec<(&'static str, i64)> {
    conf_route_geometry_with_sigreturn(route, release, pselect_shift, None, waiter_compact)
}

/// The AArch32 compat select carrier's route block, from the image-derived
/// geometry: the compat strip home, the `_newselect` buffer depth and the
/// futex ABI this image's own chain produced. It replaces the 64-bit route's
/// `waiter_shift` branch (there is no 64-bit strip to shift). The ABI travels
/// because the runtime cannot recover it from a novel home.
pub fn conf_compat_route_geometry(
    waiter_home: i64,
    select_dst_depth: i64,
    futex_abi: i64,
) -> Vec<(&'static str, i64)> {
    vec![
        ("compact_waiter", crate::family::SELECT_COMPACT_WAITER),
        ("compat_carrier", 1),
        ("waiter_home", waiter_home),
        ("select_dst_depth", select_dst_depth),
        ("compat_futex_abi", futex_abi),
    ]
}

/// The multicast route's profile block from the route verdict's own numbers:
/// the setsockopt copy window (`buffer_size`), the strip home (`waiter_off`)
/// and the `rt_mutex_waiter` field offsets this image produced (BTF, or the
/// labelled 5.10 template on a BTF-less image). A key the verdict could not
/// produce falls back to the train-corroborated constants — never to the
/// release string. `compact_waiter` selects the native route
/// implementation's flat-strip encoder.
pub fn conf_multicast_route_geometry(
    numbers: &BTreeMap<String, i64>,
    structs: &BTreeMap<String, Option<u32>>,
) -> Vec<(&'static str, i64)> {
    let fallback = crate::derive::multicast_geometry_corroborated(structs);
    let mut geometry: Vec<(&'static str, i64)> = Vec::new();
    for key in ["waiter_off", "buffer_size", "task_offset", "lock_offset"] {
        let value = numbers.get(key).copied().or_else(|| {
            fallback
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| *value)
        });
        if let Some(value) = value {
            geometry.push((key, value));
        }
    }
    geometry.push(("compact_waiter", crate::derive::MULTICAST_COMPACT_WAITER));
    geometry
}

/// The AArch32 compat multicast carrier's route block, from the image-derived
/// geometry: the compat strip offset, the compat buffer size and the futex
/// ABI. The task/lock offsets are the same per-image BTF values the 64-bit
/// block uses; the anchor trio is added by the caller's policy plan.
pub fn conf_compat_multicast_route_geometry(
    numbers: &BTreeMap<String, i64>,
    structs: &BTreeMap<String, Option<u32>>,
) -> Vec<(&'static str, i64)> {
    let fallback = crate::derive::multicast_geometry_corroborated(structs);
    let mut geometry: Vec<(&'static str, i64)> = vec![("compat_carrier", 1)];
    for key in [
        "compat_futex_abi",
        "compat_waiter_off",
        "compat_buffer_size",
    ] {
        if let Some(value) = numbers.get(key) {
            geometry.push((key, *value));
        }
    }
    for key in ["task_offset", "lock_offset"] {
        let value = numbers.get(key).copied().or_else(|| {
            fallback
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| *value)
        });
        if let Some(value) = value {
            geometry.push((key, value));
        }
    }
    geometry.push(("compact_waiter", crate::derive::MULTICAST_COMPACT_WAITER));
    geometry
}

/// The route's branch geometry, with the sigreturn offsets the extractor
/// derived for this image and the image's waiter shape. `None` falls back to
/// the patch-range constants (a labelled heuristic in the caller's log).
pub fn conf_route_geometry_with_sigreturn(
    route: &str,
    release: &str,
    pselect_shift: Option<i64>,
    sigreturn_offsets: Option<(i64, i64)>,
    waiter_compact: bool,
) -> Vec<(&'static str, i64)> {
    // The offsets are image-derived when the verdict produced them; the
    // 5.10 patch-range constants are only the fallback for a 5.10 build whose
    // derivation did not run (the family function returns None elsewhere).
    if route == "sigreturn" {
        if let Some((sve, plain)) =
            sigreturn_offsets.or_else(|| crate::family::sigreturn_offsets(release))
        {
            return vec![("waiter_off_sve", sve), ("waiter_off_plain", plain)];
        }
    }
    match route {
        "select_stack" => pselect_shift
            .map(|shift| {
                let mut geometry = vec![("waiter_shift", shift)];
                /* The waiter shape is a per-image fact (`layout::
                 * waiter_compact`, read from the image's BTF or the labelled
                 * BTF-less template), not a release property: the compact strip
                 * fits the 15-word fd_set table at a positive shift, the
                 * rb_node shape does not (its last word lands two slots further
                 * out). The caller passes the fact; no release predicate is
                 * consulted here. */
                if waiter_compact {
                    geometry.push(("compact_waiter", crate::family::SELECT_COMPACT_WAITER));
                }
                geometry
            })
            .unwrap_or_default(),
        // `tcp_zerocopy`'s section carries only its stamp-shape flag; the
        // runtime reads it from the route branch (`misc.compact_waiter` on the
        // wire) and the app requires it, so the cut must emit the shared
        // waiter-layout fact rather than an empty branch.
        "tcp_zerocopy" if waiter_compact => {
            vec![("compact_waiter", crate::family::SELECT_COMPACT_WAITER)]
        }
        // `multicast_waiter` has no release-keyed branch: its block is built
        // from the route verdict's own numbers
        // (`conf_multicast_route_geometry`).
        _ => Vec::new(),
    }
}

/// Symbol keys in the bundled profiles' order; `empty_zero_page` comes from
/// kallsyms instead of an `off_*` symbol.
pub(crate) fn conf_offsets(
    symbols: &BTreeMap<String, Option<u64>>,
    extra: &ConfExtraOffsets,
) -> Vec<(String, String)> {
    let symbol = |key: &str| {
        symbols
            .get(key)
            .copied()
            .flatten()
            .map(|value| value.to_string())
    };
    [
        ("init_task", symbol("off_init_task")),
        ("init_cred", symbol("off_init_cred")),
        (
            "empty_zero_page",
            extra.empty_zero_page.map(|v| v.to_string()),
        ),
        ("root_task_group", symbol("off_root_task_group")),
        ("selinux_enforcing", symbol("off_selinux_enforcing")),
        ("selinux_blob_sizes", symbol("off_selinux_blob_sizes")),
        ("security_hook_heads", symbol("off_security_hook_heads")),
        ("slide_nfulnl_logger", symbol("off_slide_nfulnl_logger")),
        ("slide_boot_id", symbol("off_slide_boot_id")),
        ("slide_loggers_0_1", symbol("off_slide_loggers_0_1")),
    ]
    .into_iter()
    .filter_map(|(key, value)| value.map(|value| (key.to_string(), value)))
    .collect()
}


/// Everything `render_conf` writes, in one bundle.
#[derive(Debug, Clone)]
pub struct ConfInputs<'a> {
    pub release: &'a str,
    pub phys: Option<u64>,
    /// DRAM base (linear-map PHYS_OFFSET); normally supplied by hand, so the
    /// extractor writes an explicit `null` unless one is known.
    pub phys_offset: Option<u64>,
    /// The physical pair's source: a bootloader sidecar, `--phys`, or `unset`.
    pub phys_source: &'a str,
    /// The image's link-time base (`_text`); every symbol VA is relative to it.
    pub image_base: Option<u64>,
    pub symbols: &'a BTreeMap<String, Option<u64>>,
    pub structs: &'a BTreeMap<String, Option<u32>>,
    /// The ordered pipeline preference recorded in the analysis envelope.
    /// Omitted for the route-less common profile; the document's ladder derives
    /// from the facts.
    pub pipeline_order: &'a [String],
    /// The KDP fact. `Some(true)` emits `kdp = true`; the app's KDP guard pairs
    /// it with the DirtyFrag ladder, and a non-KDP document is unchanged.
    pub kdp: Option<bool>,
    pub route: Option<&'a str>,
    pub route_geometry: &'a [(&'static str, i64)],
    pub cred: &'a [(String, String)],
    pub extra_offsets: &'a ConfExtraOffsets,
    /// Stage-2 facts. Only `Source::Btf` values are emitted; `None` keeps the
    /// pre-stage-3 profile byte-identical.
    pub layouts: Option<&'a LayoutFacts>,
    /// Page-free Stage 1 blocks. Absent when their premises did not hold (no
    /// BTF/template for the selinux layout, or no valid scratch slot).
    pub stage1: Option<Stage1Conf>,
}

/// The two page-free Stage 1 profile blocks.
#[derive(Clone, Debug)]
pub struct Stage1Conf {
    pub selinux_enforcing: u32,
    pub selinux_initialized: u32,
    /// "btf" or the release template with its config premises, for the fact
    /// header (provenance, never a gate).
    pub selinux_source: String,
    pub w1_scratch: Option<crate::anchor::W1Scratch>,
}

pub fn render_conf(input: &ConfInputs<'_>) -> String {
    render_conf_with_device(input, None)
}

/// Renders the profile with the physical pair under `devices.<device>.phys`
/// (the v3 document). The option superset and every other render stay
/// device-neutral; this is the cut/runnable form, and the app resolves the map
/// for the running device.
pub fn render_conf_with_device(input: &ConfInputs<'_>, device: Option<&str>) -> String {
    let release = input.release;
    let device_label = device.unwrap_or("<device>");
    let mut fact_sources = vec![
        format!(
            "devices.{device_label}.phys.load={} ({})",
            input.phys.map(|value| value.to_string()).unwrap_or_else(|| "null".to_string()),
            input.phys_source
        ),
        format!(
            "devices.{device_label}.phys.offset={} ({})",
            input.phys_offset
                .map(|value| value.to_string())
                .unwrap_or_else(|| "null".to_string()),
            input.phys_source
        ),
    ];
    if input.kdp == Some(true) {
        fact_sources.push("kdp=1 (pipeline fact)".to_string());
    }
    if let Some(layouts) = input.layouts {
        push_fact(&mut fact_sources, "rt_mutex_size", &layouts.rt_mutex_size);
        push_fact(&mut fact_sources, "waiter.compact", &layouts.waiter_compact);
        push_fact(&mut fact_sources, "cred.usage_size", &layouts.cred_usage_size);
        push_fact(&mut fact_sources, "cred.uid_offset", &layouts.cred_uid_offset);
        push_fact(
            &mut fact_sources,
            "cred.security_offset",
            &layouts.cred_security_offset,
        );
    }
    if let Some(stage1) = input.stage1.as_ref() {
        fact_sources.push(format!(
            "selinux.enforcing={} ({})",
            stage1.selinux_enforcing, stage1.selinux_source
        ));
        fact_sources.push(format!(
            "selinux.initialized={} ({})",
            stage1.selinux_initialized, stage1.selinux_source
        ));
    }
    if let Some(name) = device {
        fact_sources.insert(0, format!("device={name}"));
    }

    let geometry = |key: &str| {
        input
            .route_geometry
            .iter()
            .find(|(name, _)| *name == key)
            .map(|(_, value)| *value)
    };
    let compat = input
        .route_geometry
        .iter()
        .any(|(key, value)| *key == "compat_carrier" && *value == 1);
    let carrier = match input.route {
        Some("select_stack") | Some("multicast_waiter") if compat => "compat",
        Some(_) => "native",
        None => "",
    };
    let routes = match input.route {
        Some(route) => vec![document::RouteDoc {
            name: route,
            carriers: vec![document::CarrierDoc {
                name: carrier,
                geometry: document::Geometry::Pairs(input.route_geometry),
            }],
        }],
        None => Vec::new(),
    };
    let anchor = geometry("lock_anchor_image").map(|image| document::AnchorDoc {
        image: image as u64,
        bytes: geometry("lock_anchor_bytes").map(|value| value as u64),
        stride: geometry("lock_anchor_stride").map(|value| value as u64),
        scratch: input
            .stage1
            .as_ref()
            .and_then(|stage1| stage1.w1_scratch)
            .map(|scratch| scratch.image),
    });
    let built = document::build(&document::DocumentInput {
        facts: input,
        devices: match (device, input.phys) {
            (Some(name), Some(load)) => Some((name, Some(load), input.phys_offset)),
            _ => None,
        },
        routes,
        selected: input.route,
        anchor,
        geometry: document::Geometry::Pairs(input.route_geometry),
    });
    format!(
        "# GhostLock kernel profile: {release} (HOCON, self-contained).\n# fact_sources: {}\n{}",
        fact_sources.join("; "),
        document::render_hocon(&built)
    )
}

pub fn require_fields(
    values: &BTreeMap<String, Option<u64>>,
    optional: &BTreeSet<&str>,
) -> Result<()> {
    let missing: Vec<String> = values
        .iter()
        .filter(|(name, value)| value.is_none() && !optional.contains(name.as_str()))
        .map(|(name, _)| name.clone())
        .collect();
    if !missing.is_empty() {
        return Err(ExtractError::unsupported(format!(
            "missing required values: {}",
            missing.join(", ")
        )));
    }
    Ok(())
}

use std::collections::BTreeSet;

pub fn optional_symbols() -> BTreeSet<&'static str> {
    OPTIONAL_SYMBOLS.iter().copied().collect()
}

/// BTF struct fields a kernel may legitimately lack: the 5.15 GKI BTF has no
/// `slab` type, so `struct_slab_cache` is missing there. Reported as missing,
/// but not failing the extract.
const OPTIONAL_STRUCT_FIELDS: &[&str] = &["struct_slab_cache"];

pub fn optional_struct_fields() -> BTreeSet<&'static str> {
    OPTIONAL_STRUCT_FIELDS.iter().copied().collect()
}

#[cfg(test)]
mod tests {
    use super::{
        CONF_TASK_FIELDS, ConfExtraOffsets, ConfInputs, build_report, conf_cred_5x, conf_cred_6x,
        conf_multicast_route_geometry, conf_route_geometry, conf_route_geometry_with_sigreturn,
        pselect_waiter_shift_for, render_conf, render_conf_with_device,
    };
    use crate::derive::Cred5x;
    use crate::fact::{Confidence, Domain, Fact, Source};
    use crate::layout::LayoutFacts;
    use std::collections::BTreeMap;

    fn conf_fixture() -> (BTreeMap<String, Option<u64>>, BTreeMap<String, Option<u32>>) {
        let mut symbols: BTreeMap<String, Option<u64>> = BTreeMap::new();
        symbols.insert("off_init_task".to_string(), Some(34_595_456));
        symbols.insert("off_security_hook_heads".to_string(), Some(0));
        symbols.insert("off_absent".to_string(), None);
        let mut structs: BTreeMap<String, Option<u32>> = BTreeMap::new();
        structs.insert("task_prio".to_string(), Some(132));
        structs.insert("waiter_task".to_string(), Some(48));
        structs.insert("waiter_lock".to_string(), Some(56));
        (symbols, structs)
    }

    fn no_extra_offsets() -> ConfExtraOffsets {
        ConfExtraOffsets::default()
    }

    #[test]
    fn device_render_keys_the_physical_pair() {
        let (symbols, structs) = conf_fixture();
        let geometry: Vec<(&'static str, i64)> = vec![("waiter_shift", -2)];
        let inputs = ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "5.10.198-android12-9-00085-g226a9632f13d-ab11136126",
            phys: Some(0x4000_0000),
            phys_offset: Some(0x4000_0000),
            symbols: &symbols,
            structs: &structs,
            route: Some("select_stack"),
            route_geometry: &geometry,
            cred: &conf_cred_6x(),
            extra_offsets: &no_extra_offsets(),
            layouts: None,
            stage1: None,
            phys_source: "vendor_boot header (MediaTek)",
            image_base: None,
        };
        let device = render_conf_with_device(&inputs, Some("yunluo"));
        assert!(device.contains("schema_version = 3"));
        assert!(device.contains("devices {\n  yunluo {"));
        assert!(device.contains("load = \"0x40000000\""));
        assert!(device.contains("offset = \"0x40000000\""));
        assert!(device.contains("device=yunluo;"));
        let neutral = render_conf(&inputs);
        assert!(neutral.contains("schema_version = 3"));
        assert!(neutral.contains("devices = null"));
        assert!(!neutral.contains("device="));
    }

    #[test]
    fn conf_is_flattened_and_inlines_the_6x_shared_constants() {
        let (symbols, structs) = conf_fixture();
        let geometry: Vec<(&'static str, i64)> = vec![("waiter_shift", -2)];
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "6.6.89-android15-8-g0889fe95bb10-ab14402178-4k",
            phys: Some(0x4000_0000),
            phys_offset: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("select_stack"),
            route_geometry: &geometry,
            cred: &conf_cred_6x(),
            extra_offsets: &no_extra_offsets(),

            layouts: None,
            stage1: None,

            phys_source: "test",

            image_base: None,
        });
        assert!(!out.contains("include"));
        assert!(out.contains("devices = null"));
        assert!(out.contains("ghostlock {\n  syscall = \"select_stack\""));
        assert!(out.contains("native {\n        waiter_shift = -2"));
        assert!(out.contains("collisions = 4"));
        assert!(!out.contains("mm_struct_sz"));
        assert!(!out.contains("task_prio"));
        assert!(out.contains("  prio = 132"));
        assert!(out.contains("cred {"));
        assert!(out.contains("copy_size = 136"));
        assert!(out.contains("caps {\n        offset = 48"));
        assert!(out.contains("mask = \"0xffffffffffffffff\""));
        assert!(out.contains("init_task = \"0x"));
        assert!(out.contains("security_hook_heads = \"0x0\""));
        assert!(!out.contains("off_absent"));
        /* No layout facts -> no stage-3 keys; the profile stays byte-identical. */
        assert!(!out.contains("usage_size"));
        assert!(!out.contains("rt_mutex_size"));
        assert!(!out.contains("waiter_layout"));
        assert!(out.contains("# fact_sources:"));
    }

    #[test]
    fn conf_61_writes_the_compact_waiter() {
        let (symbols, structs) = conf_fixture();
        let geometry: Vec<(&'static str, i64)> = vec![("compact_waiter", 1)];
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "6.1.118-android14-11-gca0ef6d17716-ab13624819",
            phys: None,
            phys_offset: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("tcp_zerocopy"),
            route_geometry: &geometry,
            cred: &conf_cred_6x(),
            extra_offsets: &no_extra_offsets(),

            layouts: None,
            stage1: None,

            phys_source: "test",

            image_base: None,
        });
        assert!(out.contains("tcp_zerocopy {\n      native {}"));
        assert!(out.contains("shape = \"compact\""));
        assert!(!out.contains("mm_struct_sz"));
        assert!(out.contains("devices = null"));
    }

    #[test]
    fn btf_layout_facts_are_emitted_as_optional_keys() {
        let (symbols, structs) = conf_fixture();
        let layouts = LayoutFacts {
            rt_mutex_size: Fact::new(32, Domain::SizeOf, Source::Btf, Confidence::Derived),
            waiter_compact: Fact::new(true, Domain::EnumValue, Source::Btf, Confidence::Derived),
            waiter_pi_tree: Fact::new(
                "0x18".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            waiter_task: Fact::new(
                "0x30".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            waiter_lock: Fact::new(
                "0x38".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            waiter_wake_state: Fact::new(
                "0x40".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_usage_size: Fact::new(
                "8".to_string(),
                Domain::SizeOf,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_uid_offset: Fact::new(
                "0x8".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_security_offset: Fact::new(
                "0x80".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_caps_offset: Fact::new(
                "0x30".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_copy_size: Fact::new(
                "136".to_string(),
                Domain::SizeOf,
                Source::Btf,
                Confidence::Derived,
            ),
        };
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "6.6.89-android15-8-g0889fe95bb10-ab14402178-4k",
            phys: None,
            phys_offset: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("select_stack"),
            route_geometry: &[],
            cred: &conf_cred_6x(),
            extra_offsets: &no_extra_offsets(),
            layouts: Some(&layouts),
            stage1: None,

            phys_source: "test",

            image_base: None,
        });
        assert!(out.contains("usage {"));
        assert!(out.contains("size = 8"));
        assert!(out.contains("uid = 8"));
        assert!(out.contains("security = 128"));
        assert!(out.contains("rt_mutex {\n      size = 32"));
        assert!(out.contains(
            "waiter {\n      shape = \"compact\"\n      pi_tree = 24\n      task = 48\n      lock = 56\n      wake_state = 64\n    }"
        ));
        /* The existing caps/copy template is unchanged in this slice; the
         * 6.1 template mismatch remains the documented bundle item. */
        assert!(out.contains("offset = 48"));
        assert!(out.contains("copy_size = 136"));
        assert!(out.contains("cred.usage_size=8 (btf/derived)"));
        assert!(out.contains("# fact_sources:"));
    }

    #[test]
    fn btf_cred_caps_and_copy_override_the_shared_6x_template() {
        let (symbols, structs) = conf_fixture();
        let layouts = LayoutFacts {
            rt_mutex_size: Fact::new(32, Domain::SizeOf, Source::Btf, Confidence::Derived),
            waiter_compact: Fact::new(true, Domain::EnumValue, Source::Btf, Confidence::Derived),
            waiter_pi_tree: Fact::new(
                "0x18".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            waiter_task: Fact::new(
                "0x30".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            waiter_lock: Fact::new(
                "0x38".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            waiter_wake_state: Fact::new(
                "0x40".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_usage_size: Fact::new(
                "4".to_string(),
                Domain::SizeOf,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_uid_offset: Fact::new(
                "0x4".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_security_offset: Fact::new(
                "0x78".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_caps_offset: Fact::new(
                "0x28".to_string(),
                Domain::FieldOffset,
                Source::Btf,
                Confidence::Derived,
            ),
            cred_copy_size: Fact::new(
                "128".to_string(),
                Domain::SizeOf,
                Source::Btf,
                Confidence::Derived,
            ),
        };
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "6.1.25-android14-11-g8744d1c0cf31-ab10888519",
            phys: None,
            phys_offset: None,
            phys_source: "test",
            image_base: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("select_stack"),
            route_geometry: &[],
            cred: &conf_cred_6x(),
            extra_offsets: &no_extra_offsets(),
            layouts: Some(&layouts),
            stage1: None,
        });
        assert!(out.contains("caps {\n        offset = 40"));
        assert!(out.contains("copy_size = 128"));
        assert!(!out.contains("caps {\n        offset = 48"));
        assert!(!out.contains("copy_size = 136"));
    }

    #[test]
    fn a_5x_credential_block_is_not_overridden_by_the_btf_prefix_facts() {
        let (symbols, structs) = conf_fixture();
        let btf = |value: &str, domain| {
            Fact::new(value.to_string(), domain, Source::Btf, Confidence::Derived)
        };
        let unknown = |value: &str| {
            Fact::new(
                value.to_string(),
                Domain::FieldOffset,
                Source::Unknown,
                Confidence::Unknown,
            )
        };
        let layouts = LayoutFacts {
            rt_mutex_size: Fact::new(0, Domain::SizeOf, Source::Unknown, Confidence::Unknown),
            waiter_compact: Fact::new(
                false,
                Domain::EnumValue,
                Source::Unknown,
                Confidence::Unknown,
            ),
            waiter_pi_tree: unknown("null"),
            waiter_task: unknown("null"),
            waiter_lock: unknown("null"),
            waiter_wake_state: unknown("null"),
            cred_usage_size: btf("4", Domain::SizeOf),
            cred_uid_offset: btf("0x4", Domain::FieldOffset),
            cred_security_offset: btf("0x78", Domain::FieldOffset),
            cred_caps_offset: btf("0x28", Domain::FieldOffset),
            cred_copy_size: btf("128", Domain::SizeOf),
        };
        let cred = Cred5x {
            caps_offset: 48,
            caps_count: 3,
            caps_value: 0x1ffffffffff,
            refs: vec![(0x80, 0xffffffc00ab23a80)],
        };
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "5.15.189-android13-8-00016-g51bba4309aac-ab14546557",
            phys: None,
            phys_offset: None,
            phys_source: "test",
            image_base: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("select_stack"),
            route_geometry: &[],
            cred: &conf_cred_5x(&cred, 176),
            extra_offsets: &no_extra_offsets(),
            layouts: Some(&layouts),
            stage1: None,
        });
        /* A 5.x block keeps its copy size: `security + 8` is not sizeof(cred)
         * there (the fields follow `security`). Its cap set is the image's
         * BTF answer — 5.15 is 40 where the 6.x-shaped template says 48; the
         * timer fragment supplies the count in the store document. */
        assert!(out.contains("copy_size = 176"));
        assert!(out.contains("caps {\n        offset = 40"));
        assert!(!out.contains("copy_size = 128"));
        assert!(!out.contains("caps {\n        offset = 48"));
        assert!(out.contains("security = 120"));
    }

    #[test]
    fn operator_physical_override_is_in_the_fact_sources_header() {
        let (symbols, structs) = conf_fixture();
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "6.6.89-android15-8-g0889fe95bb10-ab14402178-4k",
            phys: Some(0xa800_0000),
            phys_offset: None,
            phys_source: "--phys",
            image_base: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("select_stack"),
            route_geometry: &[("waiter_shift", -2)],
            cred: &conf_cred_6x(),
            extra_offsets: &no_extra_offsets(),
            layouts: None,
            stage1: None,
        });
        assert!(out.contains("devices.<device>.phys.load=2818572288 (--phys)"));
        assert!(out.contains("devices = null"));
        /* The comment is the only record of the operator value; no new key. */
        assert!(!out.contains("phys_source ="));
    }

    #[test]
    fn conf_5x_carries_the_derived_credential_and_multicast_geometry() {
        let (symbols, structs) = conf_fixture();
        let cred = Cred5x {
            caps_offset: 48,
            caps_count: 3,
            caps_value: 0x1ffffffffff,
            refs: vec![
                (0x80, 0xffffffc00ab23a80),
                (0x88, 0xffffffc00acce110),
                (0x90, 0xffffffc00ab23ff0),
                (0x98, 0xffffffc00ab23b28),
            ],
        };
        let numbers = BTreeMap::from([
            ("waiter_off".to_string(), 96),
            ("buffer_size".to_string(), 264),
            ("task_offset".to_string(), 48),
            ("lock_offset".to_string(), 56),
            ("buffer_source".to_string(), 0),
        ]);
        let geometry = conf_multicast_route_geometry(&numbers, &structs);
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "5.15.189-android13-8-00016-g51bba4309aac-ab14546557",
            phys: None,
            phys_offset: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("multicast_waiter"),
            route_geometry: &geometry,
            cred: &conf_cred_5x(&cred, 176),
            extra_offsets: &ConfExtraOffsets {
                empty_zero_page: Some(47_529_984),
            },

            layouts: None,
            stage1: None,

            phys_source: "test",

            image_base: None,
        });
        assert!(out.contains("multicast_waiter {\n      native {\n        waiter_off = 96"));
        assert!(out.contains("buffer_size = 264"));
        assert!(out.contains("waiter {"));
        assert!(out.contains("waiter {"));
        assert!(out.contains("waiter {"));
        assert!(out.contains("shape = \"compact\""));
        assert!(out.contains("collisions = 8"));
        assert!(!out.contains("mm_struct_sz"));
        assert!(out.contains("cred {"));
        assert!(out.contains("copy_size = 176"));
        assert!(out.contains("value = 256"));
        assert!(out.contains("count = 3"));
        assert!(out.contains("mask = \"0x1ffffffffff\""));
        assert!(out.contains("refs {"));
        assert!(out.contains("group_info {"));
        assert!(out.contains("empty_zero_page = \"0x2d54000\""));
    }

    #[test]
    fn conf_route_geometry_follows_the_measured_families() {
        // The 5.10 select_stack geometry follows the image-derived shift, not
        // the family constant: a delta=8 image emits -1, and no shift means the
        // caller (the verdict engine) must supply the labelled fallback.
        assert_eq!(
            conf_route_geometry("select_stack", "5.10.246-gki", Some(-1), true),
            vec![("waiter_shift", -1), ("compact_waiter", 1)]
        );
        assert!(conf_route_geometry("select_stack", "5.10.246-gki", None, true).is_empty());
        // sigreturn has no BTF derivation dependency; the patch-range fallback
        // differs by the strip home (0x1c0 vs 0x210).
        assert_eq!(
            conf_route_geometry("sigreturn", "5.10.246-gki", None, true),
            vec![("waiter_off_sve", 256), ("waiter_off_plain", 240)]
        );
        assert_eq!(
            conf_route_geometry("sigreturn", "5.10.81-android12-9", None, true),
            vec![("waiter_off_sve", 336), ("waiter_off_plain", 320)]
        );
        assert_eq!(
            conf_route_geometry_with_sigreturn(
                "sigreturn",
                "5.10.236-android12-9",
                None,
                Some((256, 240)),
                true
            ),
            vec![("waiter_off_sve", 256), ("waiter_off_plain", 240)]
        );
        // A 6.x derivation is image-derived the same way; no family constant
        // participates (the 6.1.134 handler copy gives 0xE8).
        assert_eq!(
            conf_route_geometry_with_sigreturn(
                "sigreturn",
                "6.1.134-android14-11-o-gd252f36df142",
                None,
                Some((232, 232)),
                true
            ),
            vec![("waiter_off_sve", 232), ("waiter_off_plain", 232)]
        );
        assert_eq!(
            conf_route_geometry("select_stack", "6.6.89-android15-8", Some(-2), false),
            vec![("waiter_shift", -2)]
        );
        assert!(conf_route_geometry("select_stack", "6.6.89-android15-8", None, false).is_empty());
        // 6.1 and 5.15 are compact; the flag must accompany the shift or the
        // route takes the rb_node branch and refuses at word 14 -> global 15.
        assert_eq!(
            conf_route_geometry(
                "select_stack",
                "6.1.138-android14-11-g44bda9e8f6e9-ab13792638",
                Some(1),
                true
            ),
            vec![("waiter_shift", 1), ("compact_waiter", 1)]
        );
        assert_eq!(
            conf_route_geometry("select_stack", "5.15.194-android13-8", Some(2), true),
            vec![("waiter_shift", 2), ("compact_waiter", 1)]
        );
        // tcp_zerocopy's stamp-shape flag comes from the same per-image waiter
        // layout fact; its branch must carry it or the app cannot resolve the
        // route (the native reads `misc.compact_waiter` from the branch).
        assert_eq!(
            conf_route_geometry("tcp_zerocopy", "6.1.118-android14-11", Some(1), true),
            vec![("compact_waiter", 1)]
        );
        assert!(
            conf_route_geometry("tcp_zerocopy", "6.6.89-android15-8", Some(-2), false).is_empty()
        );
        // `multicast_waiter` is not release-keyed: its block comes from the
        // route verdict's own numbers, so the release string alone yields
        // nothing -- including on a major-5 release.
        assert!(
            conf_route_geometry(
                "multicast_waiter",
                "5.15.189-android13-8-00016-g51bba4309aac-ab14546557",
                Some(-2),
                true
            )
            .is_empty()
        );
        assert!(
            conf_route_geometry("multicast_waiter", "6.6.89-android15-8", Some(-2), false)
                .is_empty()
        );
    }

    #[test]
    fn unverified_route_geometry_is_a_partial_candidate() {
        // No image-derived shift: the route branch stays empty rather than
        // borrowing the -2 family default.
        assert!(conf_route_geometry("select_stack", "6.7.1-generic", None, false).is_empty());
        // An image-derived shift is kept.
        assert_eq!(
            conf_route_geometry("select_stack", "6.7.1-generic", Some(-1), false),
            vec![("waiter_shift", -1)]
        );
    }

    #[test]
    fn select_waiter_shape_follows_the_image_fact() {
        // The flag follows `layout::waiter_compact` (BTF), not the release: a
        // 6.1 image whose BTF says non-compact omits it, and a 6.6 image whose
        // BTF says compact carries it.
        assert_eq!(
            conf_route_geometry("select_stack", "6.1.138-android14-11", Some(1), false),
            vec![("waiter_shift", 1)]
        );
        assert_eq!(
            conf_route_geometry("select_stack", "6.6.89-android15-8", Some(-2), true),
            vec![("waiter_shift", -2), ("compact_waiter", 1)]
        );
        // The BTF-less 5.10 template stays compact.
        assert_eq!(
            conf_route_geometry("select_stack", "5.10.236-android12-9", Some(-2), true),
            vec![("waiter_shift", -2), ("compact_waiter", 1)]
        );
    }

    #[test]
    fn multicast_route_geometry_comes_from_the_verdict_numbers() {
        let (_, structs) = conf_fixture();
        // The verdict's own values win over the train constants (96/264/48/56):
        // these are the numbers a hypothetical image's derivation produced.
        let numbers = BTreeMap::from([
            ("waiter_off".to_string(), 0x48),
            ("buffer_size".to_string(), 0x120),
            ("task_offset".to_string(), 0x28),
            ("lock_offset".to_string(), 0x30),
            ("buffer_source".to_string(), 0),
        ]);
        assert_eq!(
            conf_multicast_route_geometry(&numbers, &structs),
            vec![
                ("waiter_off", 0x48),
                ("buffer_size", 0x120),
                ("task_offset", 0x28),
                ("lock_offset", 0x30),
                ("compact_waiter", 1),
            ]
        );
        // A feasible verdict carries all four keys by construction; if one is
        // ever missing, the labelled train fallback fills it -- the release
        // string never supplies a value.
        let partial = BTreeMap::from([("waiter_off".to_string(), 0x48)]);
        assert_eq!(
            conf_multicast_route_geometry(&partial, &structs),
            vec![
                ("waiter_off", 0x48),
                ("buffer_size", 264),
                ("task_offset", 48),
                ("lock_offset", 56),
                ("compact_waiter", 1),
            ]
        );
        // The block never grows the removed device-placement keys.
        let geometry = conf_multicast_route_geometry(&numbers, &structs);
        assert!(
            !geometry
                .iter()
                .any(|(key, _)| key.starts_with("fake_") || key.starts_with("lock_slot")),
            "device-measured placement must not be inherited"
        );
    }

    #[test]
    fn candidate_conf_keeps_the_route_branch_when_geometry_is_empty() {
        let symbols: BTreeMap<String, Option<u64>> = BTreeMap::new();
        let structs: BTreeMap<String, Option<u32>> = BTreeMap::new();
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "5.15.178-g3575c47dc7ce-dirty",
            phys: None,
            phys_offset: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("multicast_waiter"),
            route_geometry: &[],
            cred: &[],
            extra_offsets: &ConfExtraOffsets {
                empty_zero_page: None,
            },

            layouts: None,
            stage1: None,

            phys_source: "test",

            image_base: None,
        });
        assert!(out.contains("ghostlock {"));
        assert!(out.contains("multicast_waiter {"));
        assert!(out.contains("release = \"5.15.178-g3575c47dc7ce-dirty\""));
    }

    #[test]
    fn unverified_release_omits_heap_defaults() {
        let (symbols, structs) = conf_fixture();
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "6.7.1-generic",
            phys: None,
            phys_offset: None,
            symbols: &symbols,
            structs: &structs,
            route: None,
            route_geometry: &[],
            cred: &[],
            extra_offsets: &no_extra_offsets(),

            layouts: None,
            stage1: None,

            phys_source: "test",

            image_base: None,
        });
        assert!(out.contains("heap {"));
        assert!(out.contains("collisions = null"));
        assert!(!out.contains("mm_struct_sz"));
    }

    #[test]
    fn unverified_5x_candidate_is_runnable() {
        let (symbols, structs) = conf_fixture();
        // The numbers a BTF-less 5.10 image's verdict produces: the derived
        // strip home, the UAPI copy size (buffer_source = 1), and the 5.10
        // waiter template the verdict applied under its own guard.
        let numbers = BTreeMap::from([
            ("waiter_off".to_string(), 96),
            ("buffer_size".to_string(), 264),
            ("buffer_source".to_string(), 1),
            ("task_offset".to_string(), 0x30),
            ("lock_offset".to_string(), 0x38),
        ]);
        let geometry = conf_multicast_route_geometry(&numbers, &structs);
        let out = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: "5.15.178-g3575c47dc7ce-dirty",
            phys: None,
            phys_offset: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("multicast_waiter"),
            route_geometry: &geometry,
            cred: &[],
            extra_offsets: &no_extra_offsets(),

            layouts: None,
            stage1: None,

            phys_source: "test",

            image_base: None,
        });
        assert!(out.contains("multicast_waiter {\n      native {\n        waiter_off = 96"));
        assert!(out.contains("buffer_size = 264"));
        assert!(out.contains("waiter {"));
        assert!(out.contains("waiter {"));
        assert!(out.contains("waiter {"));
        assert!(out.contains("shape = \"compact\""));
        // The 5.x KernelSnitch collisions value is required to run and is
        // emitted even without the "-android13-" train tag.
        assert!(out.contains("heap {"));
        assert!(out.contains("collisions = 8"));
        assert!(!out.contains("mm_struct_sz"));
    }

    #[test]
    fn pselect_waiter_shift_matches_the_committed_tables() {
        assert_eq!(
            pselect_waiter_shift_for(Some("6.1.118-android14-11-gca0ef6d17716-ab13624819")),
            Some(1)
        );
        assert_eq!(
            pselect_waiter_shift_for(Some("6.6.92-android15-8")),
            Some(-2)
        );
        assert_eq!(
            pselect_waiter_shift_for(Some("6.12.30-android16-0")),
            Some(0)
        );
        assert_eq!(pselect_waiter_shift_for(Some("6.7.1-android16-1")), None);
        assert_eq!(pselect_waiter_shift_for(None), None);
    }

    #[test]
    fn json_report_keeps_unverified_pselect_shift_null() {
        let symbols = BTreeMap::new();
        let structs = BTreeMap::new();
        let report = build_report(
            Some("6.7.1-generic"),
            0,
            None,
            &symbols,
            &structs,
            0,
            pselect_waiter_shift_for(Some("6.7.1-generic")),
        );
        assert!(report["pselect_waiter_shift"].is_null());
    }

    /// Flatten a HOCON-ish profile into `section.key` -> value, ignoring
    /// comments and one level of brace nesting.
    fn flatten_conf(text: &str) -> BTreeMap<String, String> {
        let mut out = BTreeMap::new();
        let mut stack: Vec<String> = Vec::new();
        for raw in text.lines() {
            let line = raw.split('#').next().unwrap_or("").trim();
            if line.is_empty() {
                continue;
            }
            if line.ends_with('{') {
                stack.push(line[..line.len() - 1].trim().to_string());
                continue;
            }
            if line == "}" {
                stack.pop();
                continue;
            }
            if let Some((key, value)) = line.split_once('=') {
                let key = key.trim();
                let value = value.trim();
                let full = if stack.is_empty() {
                    key.to_string()
                } else {
                    format!("{}.{}", stack.join("."), key)
                };
                out.insert(full, value.to_string());
            }
        }
        out
    }

    /// The values the real extractor produces for the A301SO `5.15.189` boot
    /// image, so the rendered conf can be compared field-for-field with the
    /// bundled, hardware-validated profile.
    fn a301so_inputs() -> (
        String,
        BTreeMap<String, Option<u64>>,
        BTreeMap<String, Option<u32>>,
        Vec<(String, String)>,
        ConfExtraOffsets,
    ) {
        let release = "5.15.189-android13-8-00016-g51bba4309aac-ab14546557";
        let mut symbols: BTreeMap<String, Option<u64>> = BTreeMap::new();
        for (key, value) in [
            ("off_init_task", 46_412_800u64),
            ("off_init_cred", 46_126_472),
            ("off_root_task_group", 47_549_120),
            ("off_selinux_enforcing", 47_885_704),
            ("off_selinux_blob_sizes", 35_027_656),
            ("off_security_hook_heads", 35_018_304),
            ("off_slide_nfulnl_logger", 45_096_488),
            ("off_slide_boot_id", 47_999_001),
            ("off_slide_loggers_0_1", 45_096_280),
        ] {
            symbols.insert(key.to_string(), Some(value));
        }
        let mut structs: BTreeMap<String, Option<u32>> = BTreeMap::new();
        for (key, _) in CONF_TASK_FIELDS {
            // Values from the A301SO image's BTF.
            let v = match *key {
                "task_prio" => 124,
                "task_normal_prio" => 132,
                "task_sched_task_group" => 1024,
                "task_pi_lock" => 2180,
                "task_pi_waiters" => 2200,
                "task_pi_top_task" => 2216,
                "task_pi_blocked_on" => 2224,
                "task_pid" => 1496,
                "task_tgid" => 1500,
                "task_atomic_flags" => 1432,
                "task_real_cred" => 1936,
                "task_cred" => 1944,
                "task_comm" => 1960,
                "task_tasks" => 1232,
                "task_seccomp" => 2144,
                _ => continue,
            };
            structs.insert(key.to_string(), Some(v));
        }
        structs.insert("waiter_task".to_string(), Some(48));
        structs.insert("waiter_lock".to_string(), Some(56));
        let cred5x = Cred5x {
            caps_offset: 48,
            caps_count: 3,
            caps_value: 2_199_023_255_551,
            refs: vec![
                (128, -274_698_454_400i64 as u64),
                (136, -274_696_707_824i64 as u64),
                (144, -274_698_453_008i64 as u64),
                (152, -274_698_454_232i64 as u64),
            ],
        };
        let cred = conf_cred_5x(&cred5x, 176);
        let extra = ConfExtraOffsets {
            empty_zero_page: Some(47_529_984),
        };
        (release.to_string(), symbols, structs, cred, extra)
    }

    #[test]
    fn a301so_generated_conf_matches_the_bundled_profile() {
        let (release, symbols, structs, cred, extra) = a301so_inputs();
        /* The values the route verdict derives for this image: the probe-matched
         * strip home, the BTF copy size, and the BTF waiter fields. */
        let numbers = BTreeMap::from([
            ("waiter_off".to_string(), 96),
            ("buffer_size".to_string(), 264),
            ("buffer_source".to_string(), 0),
            ("task_offset".to_string(), 48),
            ("lock_offset".to_string(), 56),
        ]);
        let geometry = conf_multicast_route_geometry(&numbers, &structs);
        let generated = render_conf(&ConfInputs {
            pipeline_order: &[],
            kdp: None,
            release: &release,
            phys: None,
            phys_offset: None,
            symbols: &symbols,
            structs: &structs,
            route: Some("multicast_waiter"),
            route_geometry: &geometry,
            cred: &cred,
            extra_offsets: &extra,

            layouts: None,
            stage1: None,

            phys_source: "test",

            image_base: None,
        });
        /* The r8 bundle dropped its 5.15 assets; this is the hardware-validated
         * profile as it shipped before the drop, kept as the rendering oracle. */
        let bundled = std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/fixtures/5.15.189-android13-8-00016-g51bba4309aac-ab14546557.conf"
        ))
        .expect("the 5.15.189 rendering fixture");
        let generated = flatten_conf(&generated);
        let bundled = flatten_conf(&bundled);

        assert!(
            generated.contains_key("ghostlock.syscalls.multicast_waiter.native.waiter_off"),
            "{:?}",
            generated.keys().collect::<Vec<_>>()
        );
        for key in bundled.keys() {
            /* Facts an image-only generation cannot supply: the slab stride
             * (a runtime measurement), the link base (this fixture predates
             * the field), the timer block's shared symbols and its derived
             * credential overlay (the store's timer block), the per-device
             * entries, and the app-side engine default. */
            let skip = key == "kernel.heap.stride"
                || key == "kernel.image_base"
                || key.starts_with("devices")
                || key.starts_with("timer")
                || key == "ghostlock.syscall"
                || key == "ghostlock.carrier"
                || key == "schema_version"
                || (key.starts_with("kernel.symbols.")
                    && !matches!(
                        key.trim_start_matches("kernel.symbols."),
                        "init_task"
                            | "init_cred"
                            | "root_task_group"
                            | "empty_zero_page"
                            | "selinux_state"
                            | "selinux_blob_sizes"
                            | "security_hook_heads"
                            | "nf_logger"
                            | "loggers_0_1"
                            | "boot_id"
                    ));
            if skip {
                continue;
            }
            assert_eq!(
                generated.get(key),
                bundled.get(key),
                "field {key} differs between generated and bundled profile"
            );
        }
        for key in generated.keys() {
            let skip = key == "kernel.heap.stride"
                || key == "kernel.image_base"
                || key.starts_with("devices")
                || key.starts_with("timer")
                || key == "ghostlock.syscall"
                || key == "ghostlock.carrier"
                || key == "schema_version";
            if skip {
                continue;
            }
            assert!(
                bundled.contains_key(key),
                "generated profile has unexpected field {key}"
            );
        }
    }
}
