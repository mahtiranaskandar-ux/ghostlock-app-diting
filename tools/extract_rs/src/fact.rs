//! Fact provenance model: domain, source, confidence, and the `--explain` table.
//!
//! Stage 1 of `docs/analysis/fact-sources-and-precedence-plan.md`: a fact carries
//! the domain it lives on, where it came from and how strongly it is grounded.
//! Nothing here changes what the extractor emits; `--explain` prints the table
//! for the same values the profile carries (constant header keys excepted).

use std::collections::BTreeMap;
use std::fmt::{self, Display};

use crate::layout::LayoutFacts;
use crate::report::{
    conf_offsets, ConfInputs, CONF_CRED_FIELDS, CONF_OFFSET_FIELDS, CONF_ROUTE_FIELDS,
    CONF_TASK_FIELDS,
};
use crate::symbols::kernel_layout_verified;

/// The axis a value lives on. Two values are comparable only in the same
/// domain; a cross-domain comparison is a bug, not a mismatch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Domain {
    ImageOffset,
    SymbolAddress,
    PhysicalAddress,
    RuntimeVa,
    FieldOffset,
    SizeOf,
    SlabStride,
    FrameDepth,
    EnumValue,
}

impl Domain {
    pub fn label(self) -> &'static str {
        match self {
            Domain::ImageOffset => "image-offset",
            Domain::SymbolAddress => "symbol-address",
            Domain::PhysicalAddress => "physical",
            Domain::RuntimeVa => "runtime-va",
            Domain::FieldOffset => "field-offset",
            Domain::SizeOf => "sizeof",
            Domain::SlabStride => "slab-stride",
            Domain::FrameDepth => "frame-depth",
            Domain::EnumValue => "enum",
        }
    }
}

impl Display for Domain {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// Where a value came from. `Unknown` is used for a value the profile leaves
/// out (it is not a source).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    Btf,
    Kallsyms,
    Disassembly,
    ReleaseTemplate,
    CompiledDefault,
    RuntimeMeasurement,
    Operator,
    Bootloader,
    Unknown,
}

impl Source {
    pub fn label(self) -> &'static str {
        match self {
            Source::Btf => "btf",
            Source::Kallsyms => "kallsyms",
            Source::Disassembly => "disassembly",
            Source::ReleaseTemplate => "release-template",
            Source::CompiledDefault => "compiled-default",
            Source::RuntimeMeasurement => "measurement",
            Source::Operator => "operator",
            Source::Bootloader => "bootloader",
            Source::Unknown => "unknown",
        }
    }
}

impl Display for Source {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// Confidence ladder, highest first: verified > derived > measured > heuristic
/// > default. `Suspect` is outside the ladder and refuses; `Unknown` means no
/// value was produced.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Confidence {
    Verified,
    Derived,
    Measured,
    Heuristic,
    Default,
    Suspect,
    Unknown,
}

impl Confidence {
    /// Ladder position; `None` for `Suspect`, which is not on the ladder.
    pub fn rank(self) -> Option<u8> {
        match self {
            Confidence::Verified => Some(5),
            Confidence::Derived => Some(4),
            Confidence::Measured => Some(3),
            Confidence::Heuristic => Some(2),
            Confidence::Default => Some(1),
            Confidence::Unknown => Some(0),
            Confidence::Suspect => None,
        }
    }

    pub fn is_verified(self) -> bool {
        matches!(self, Confidence::Verified)
    }

    pub fn label(self) -> &'static str {
        match self {
            Confidence::Verified => "verified",
            Confidence::Derived => "derived",
            Confidence::Measured => "measured",
            Confidence::Heuristic => "heuristic",
            Confidence::Default => "default",
            Confidence::Suspect => "suspect",
            Confidence::Unknown => "unknown",
        }
    }
}

impl Display for Confidence {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// A value with its provenance. Domain, source and confidence always travel
/// together; a bare value is not a fact.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Fact<T> {
    pub value: T,
    pub domain: Domain,
    pub source: Source,
    pub confidence: Confidence,
}

impl<T> Fact<T> {
    pub const fn new(value: T, domain: Domain, source: Source, confidence: Confidence) -> Self {
        Self {
            value,
            domain,
            source,
            confidence,
        }
    }

    /// The only legal way to compare two facts: the domains must match. A
    /// cross-domain comparison returns the mismatch instead of a verdict.
    pub fn expect_same_domain<U>(&self, other: &Fact<U>) -> Result<(), DomainMismatch> {
        if self.domain == other.domain {
            Ok(())
        } else {
            Err(DomainMismatch {
                left: self.domain,
                right: other.domain,
            })
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DomainMismatch {
    pub left: Domain,
    pub right: Domain,
}

impl Display for DomainMismatch {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "cross-domain comparison refused: {} vs {}",
            self.left, self.right
        )
    }
}

impl std::error::Error for DomainMismatch {}

#[derive(Debug, Clone)]
pub struct FactRow {
    pub key: String,
    pub value: String,
    pub domain: Domain,
    pub source: Source,
    pub confidence: Confidence,
}

/// The `--explain` table: one row per derived profile key.
#[derive(Debug, Default)]
pub struct FactTable {
    rows: Vec<FactRow>,
}

impl FactTable {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push<T: Display>(&mut self, key: impl Into<String>, fact: Fact<T>) {
        self.rows.push(FactRow {
            key: key.into(),
            value: fact.value.to_string(),
            domain: fact.domain,
            source: fact.source,
            confidence: fact.confidence,
        });
    }

    pub fn rows(&self) -> &[FactRow] {
        &self.rows
    }

    pub fn render(&self) -> String {
        const HEADERS: [&str; 5] = ["key", "value", "domain", "source", "confidence"];
        let mut widths = [
            HEADERS[0].len(),
            HEADERS[1].len(),
            HEADERS[2].len(),
            HEADERS[3].len(),
            HEADERS[4].len(),
        ];
        for row in &self.rows {
            widths[0] = widths[0].max(row.key.len());
            widths[1] = widths[1].max(row.value.len());
            widths[2] = widths[2].max(row.domain.label().len());
            widths[3] = widths[3].max(row.source.label().len());
            widths[4] = widths[4].max(row.confidence.label().len());
        }
        let mut out = String::from(
            "# fact table: value / domain / source / confidence (constant header keys omitted)\n",
        );
        out.push_str(&format!(
            "{:<w0$}  {:<w1$}  {:<w2$}  {:<w3$}  {}\n",
            HEADERS[0],
            HEADERS[1],
            HEADERS[2],
            HEADERS[3],
            HEADERS[4],
            w0 = widths[0],
            w1 = widths[1],
            w2 = widths[2],
            w3 = widths[3],
        ));
        for row in &self.rows {
            out.push_str(&format!(
                "{:<w0$}  {:<w1$}  {:<w2$}  {:<w3$}  {}\n",
                row.key,
                row.value,
                row.domain.label(),
                row.source.label(),
                row.confidence.label(),
                w0 = widths[0],
                w1 = widths[1],
                w2 = widths[2],
                w3 = widths[3],
            ));
        }
        out
    }
}

/// Where the profile's non-constant values came from, as the derivation already
/// knows it. `field_sources` is the struct/cred provenance map the extractor
/// logs as `info: field_source ...`.
#[derive(Debug, Clone, Copy)]
pub struct Provenance<'a> {
    pub btf_present: bool,
    pub phys_source: &'a str,
    pub route_forced: bool,
    pub field_sources: &'a BTreeMap<String, String>,
    pub release: &'a str,
    pub layouts: &'a LayoutFacts,
}

/// Builds the fact table for a `--format conf` profile. It mirrors the v3
/// document's key paths; it never changes them.
pub fn explain_conf(
    input: &ConfInputs<'_>,
    prov: &Provenance<'_>,
    device: Option<&str>,
) -> FactTable {
    let mut table = FactTable::new();
    let major = prov
        .release
        .split('.')
        .next()
        .and_then(|part| part.parse::<u32>().ok());
    let device_label = device.unwrap_or("<device>");
    /* The document states a physical pair only when both a device name and a
     * load are known (`document::build`); otherwise `devices = null`. */
    let phys_stated = device.is_some() && input.phys.is_some();

    let (phys_source, phys_confidence) = phys_provenance(prov.phys_source);
    table.push(
        format!("devices.{device_label}.phys.load"),
        Fact::new(
            if phys_stated {
                hex_or_null(input.phys)
            } else {
                "null".to_string()
            },
            Domain::PhysicalAddress,
            if phys_stated { phys_source } else { Source::Unknown },
            if phys_stated { phys_confidence } else { Confidence::Unknown },
        ),
    );
    let (offset_source, offset_confidence) = if input.phys_offset.is_some() {
        (Source::Bootloader, Confidence::Derived)
    } else {
        (Source::CompiledDefault, Confidence::Default)
    };
    table.push(
        format!("devices.{device_label}.phys.offset"),
        Fact::new(
            if phys_stated {
                hex_or_null(input.phys_offset)
            } else {
                "null".to_string()
            },
            Domain::PhysicalAddress,
            if phys_stated { offset_source } else { Source::Unknown },
            if phys_stated { offset_confidence } else { Confidence::Unknown },
        ),
    );

    if let Some(route) = input.route {
        let carrier = carrier_name(route, input.route_geometry);
        let (source, confidence) = if prov.route_forced {
            (Source::Operator, Confidence::Default)
        } else {
            (Source::Disassembly, Confidence::Derived)
        };
        table.push(
            "ghostlock.syscall",
            Fact::new(route, Domain::EnumValue, source, confidence),
        );
        if let Some((_, fields)) = CONF_ROUTE_FIELDS.iter().find(|(name, _)| *name == route) {
            for field in *fields {
                let value = input
                    .route_geometry
                    .iter()
                    .find(|(key, _)| key == field)
                    .map(|(_, value)| value.to_string());
                push_route_field(&mut table, route, carrier, field, value);
            }
        }
        for (key, value) in input.route_geometry {
            /* The carrier selector is an input flag, not a document key. */
            if *key == "compat_carrier" {
                continue;
            }
            let path = route_fact_path(route, carrier, key);
            if !table.rows().iter().any(|row| row.key == path) {
                push_route_field(&mut table, route, carrier, key, Some(value.to_string()));
            }
        }
    } else {
        table.push(
            "ghostlock.syscall",
            Fact::new("null", Domain::EnumValue, Source::Unknown, Confidence::Unknown),
        );
    }

    for (name, key) in CONF_TASK_FIELDS {
        let value = input.structs.get(*name).copied().flatten();
        let (source, confidence) = task_provenance(prov, name, value.is_some());
        table.push(
            format!("kernel.struct.task.{key}"),
            Fact::new(
                display_or_null(value),
                Domain::FieldOffset,
                source,
                confidence,
            ),
        );
    }

    let ref_count = input
        .cred
        .iter()
        .find(|(candidate, _)| candidate == "ref_count")
        .and_then(|(_, value)| value.trim().parse::<u32>().ok());
    for key in CONF_CRED_FIELDS {
        let value = input
            .cred
            .iter()
            .find(|(candidate, _)| candidate == key)
            .map(|(_, value)| value.clone())
            .unwrap_or_else(|| "null".to_string());
        let Some(path) = cred_fact_path(key, ref_count) else {
            continue;
        };
        let (source, confidence) = if value == "null" {
            (Source::Unknown, Confidence::Unknown)
        } else {
            cred_provenance(prov, key, major)
        };
        table.push(
            path,
            Fact::new(value, cred_domain(key), source, confidence),
        );
    }

    let offsets = conf_offsets(input.symbols, input.extra_offsets);
    for key in CONF_OFFSET_FIELDS {
        let value = offsets
            .iter()
            .find(|(candidate, _)| candidate == key)
            .map(|(_, value)| value.clone())
            .unwrap_or_else(|| "null".to_string());
        let (source, confidence) = if value == "null" {
            (Source::Unknown, Confidence::Unknown)
        } else {
            (Source::Kallsyms, Confidence::Derived)
        };
        let symbol = match *key {
            "selinux_enforcing" => "selinux_state",
            "slide_nfulnl_logger" => "nf_logger",
            "slide_loggers_0_1" => "loggers_0_1",
            "slide_boot_id" => "boot_id",
            other => other,
        };
        table.push(
            format!("kernel.symbols.{symbol}"),
            Fact::new(value, Domain::ImageOffset, source, confidence),
        );
    }

    let collisions = if kernel_layout_verified(Some(prov.release)) || major == Some(5) {
        match major {
            Some(6) => Some(4),
            Some(5) => Some(8),
            _ => None,
        }
    } else {
        None
    };
    table.push(
        "kernel.heap.collisions",
        Fact::new(
            display_or_null(collisions),
            Domain::EnumValue,
            Source::CompiledDefault,
            Confidence::Default,
        ),
    );

    prov.layouts.push_rows(&mut table);

    table
}

fn hex_or_null(value: Option<u64>) -> String {
    value
        .map(|value| format!("0x{value:x}"))
        .unwrap_or_else(|| "null".to_string())
}

fn display_or_null<T: Display>(value: Option<T>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "null".to_string())
}

fn phys_provenance(source: &str) -> (Source, Confidence) {
    match source {
        "xbl_config FDT" | "uefi memory map" | "vendor_boot header (MediaTek)"
        | "MediaTek DRAM base" => (Source::Bootloader, Confidence::Derived),
        "--phys" => (Source::Operator, Confidence::Default),
        _ => (Source::CompiledDefault, Confidence::Default),
    }
}

fn task_provenance(prov: &Provenance<'_>, name: &str, present: bool) -> (Source, Confidence) {
    if let Some(source) = prov.field_sources.get(name) {
        if source.starts_with("derived:") {
            return (Source::Disassembly, Confidence::Derived);
        }
        if source.starts_with("template:") {
            return (Source::ReleaseTemplate, Confidence::Heuristic);
        }
    }
    if prov.btf_present && present {
        (Source::Btf, Confidence::Derived)
    } else {
        (Source::Unknown, Confidence::Unknown)
    }
}

fn cred_group(key: &str) -> &'static str {
    match key {
        "caps_offset" | "caps_count" | "caps_value" => "cred_layout.caps",
        "ref_count" | "ref0_offset" | "ref1_offset" | "ref2_offset" | "ref3_offset"
        | "ref0_image" | "ref1_image" | "ref2_image" | "ref3_image" => "cred_layout.refs",
        "copy_size" => "cred_layout.copy_size",
        "usage_offset" | "usage_value" => "cred_layout.usage",
        _ => "",
    }
}

fn cred_provenance(prov: &Provenance<'_>, key: &str, major: Option<u32>) -> (Source, Confidence) {
    if let Some(source) = prov.field_sources.get(cred_group(key)) {
        if source.starts_with("derived:") {
            return (Source::Disassembly, Confidence::Derived);
        }
        if source.starts_with("template:") {
            return (Source::ReleaseTemplate, Confidence::Heuristic);
        }
    }
    if major == Some(5) && prov.btf_present {
        (Source::Btf, Confidence::Derived)
    } else {
        // The 6.x credential block is the hardcoded 6.x template; the 5.x
        // template fallback is equally a family default, not a per-image fact.
        (Source::ReleaseTemplate, Confidence::Heuristic)
    }
}

fn cred_domain(key: &str) -> Domain {
    match key {
        "copy_size" => Domain::SizeOf,
        "caps_count" | "caps_value" | "ref_count" | "usage_value" => Domain::EnumValue,
        key if key.ends_with("_image") => Domain::ImageOffset,
        _ => Domain::FieldOffset,
    }
}

/// The named ref slot a `refN_*` index denotes for the image's ref count.
/// `None` for an index the layout does not carry.
fn ref_slot(index: usize, ref_count: Option<u32>) -> Option<&'static str> {
    match (ref_count, index) {
        (Some(3), 0) => Some("user"),
        (Some(3), 1) => Some("user_ns"),
        (Some(3), 2) => Some("group_info"),
        (Some(3), _) => None,
        (_, 0) => Some("user"),
        (_, 1) => Some("user_ns"),
        (_, 2) => Some("ucounts"),
        (_, 3) => Some("group_info"),
        _ => None,
    }
}

/// The v3 document path of one credential fact; `None` when the fact has no
/// document key (a ref slot the image's layout does not carry).
fn cred_fact_path(key: &str, ref_count: Option<u32>) -> Option<String> {
    match key {
        "copy_size" => Some("kernel.struct.cred.copy_size".to_string()),
        "usage_offset" => Some("kernel.struct.cred.usage.offset".to_string()),
        "usage_value" => Some("kernel.struct.cred.usage.value".to_string()),
        "caps_offset" => Some("kernel.struct.cred.caps.offset".to_string()),
        "caps_count" => Some("kernel.struct.cred.caps.count".to_string()),
        "caps_value" => Some("kernel.struct.cred.caps.mask".to_string()),
        "ref_count" => Some("kernel.struct.cred.refs".to_string()),
        key if key.starts_with("ref")
            && (key.ends_with("_offset") || key.ends_with("_image")) =>
        {
            let index = key
                .as_bytes()
                .get(3)
                .copied()
                .and_then(|digit| (b'0'..=b'9').contains(&digit).then(|| (digit - b'0') as usize))
                .unwrap_or(3);
            let field = if key.ends_with("_offset") { "offset" } else { "image" };
            ref_slot(index, ref_count)
                .map(|slot| format!("kernel.struct.cred.refs.{slot}.{field}"))
        }
        _ => Some(format!("kernel.struct.cred.{key}")),
    }
}

/// The carrier a route's geometry describes (the document's selection rule).
fn carrier_name(route: &str, geometry: &[(&'static str, i64)]) -> &'static str {
    let compat = geometry
        .iter()
        .any(|(key, value)| *key == "compat_carrier" && *value == 1);
    match route {
        "select_stack" | "multicast_waiter" if compat => "compat",
        _ => "native",
    }
}

/// The v3 document path of one route-geometry fact.
fn route_fact_path(route: &str, carrier: &str, key: &str) -> String {
    match key {
        "lock_anchor_image" => "ghostlock.anchor.image".to_string(),
        "lock_anchor_bytes" => "ghostlock.anchor.bytes".to_string(),
        "lock_anchor_stride" => "ghostlock.anchor.stride".to_string(),
        "task_offset" => "kernel.struct.waiter.task".to_string(),
        "lock_offset" => "kernel.struct.waiter.lock".to_string(),
        "compact_waiter" => "kernel.struct.waiter.shape".to_string(),
        "compat_futex_abi" => format!("ghostlock.syscalls.{route}.compat.futex_abi"),
        "select_dst_depth" => format!("ghostlock.syscalls.{route}.compat.dst_depth"),
        "compat_waiter_off" => format!("ghostlock.syscalls.{route}.compat.waiter_off"),
        "compat_buffer_size" => format!("ghostlock.syscalls.{route}.compat.buffer_size"),
        "waiter_off_plain" => "ghostlock.syscalls.sigreturn.native.waiter_off.plain".to_string(),
        "waiter_off_sve" => "ghostlock.syscalls.sigreturn.native.waiter_off.sve".to_string(),
        _ => format!("ghostlock.syscalls.{route}.{carrier}.{key}"),
    }
}

fn route_field_domain(key: &str) -> Domain {
    match key {
        "lock_anchor_image" => Domain::ImageOffset,
        "lock_anchor_bytes" | "lock_anchor_stride" | "buffer_size" | "compat_buffer_size" => {
            Domain::SizeOf
        }
        "compact_waiter" | "compat_futex_abi" | "futex_abi" => Domain::EnumValue,
        "task_offset" | "lock_offset" => Domain::FieldOffset,
        _ => Domain::FrameDepth,
    }
}

fn push_route_field(
    table: &mut FactTable,
    route: &str,
    carrier: &str,
    key: &str,
    value: Option<String>,
) {
    let (source, confidence) = if value.is_some() {
        (Source::Disassembly, Confidence::Derived)
    } else {
        (Source::Unknown, Confidence::Unknown)
    };
    table.push(
        route_fact_path(route, carrier, key),
        Fact::new(
            value.unwrap_or_else(|| "null".to_string()),
            route_field_domain(key),
            source,
            confidence,
        ),
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::report::ConfExtraOffsets;

    fn inputs<'a>(
        symbols: &'a BTreeMap<String, Option<u64>>,
        structs: &'a BTreeMap<String, Option<u32>>,
        cred: &'a [(String, String)],
        geometry: &'a [(&'static str, i64)],
        extra: &'a ConfExtraOffsets,
    ) -> ConfInputs<'a> {
        ConfInputs {
            image_base: None,
            pipeline_order: &[],
            kdp: None,
            release: "6.1.25",
            phys: Some(0xa800_0000),
            phys_offset: None,
            symbols,
            structs,
            route: Some("select_stack"),
            route_geometry: geometry,
            cred,
            extra_offsets: extra,
            layouts: None,
            stage1: None,
            phys_source: "test",
        }
    }

    fn row<'a>(table: &'a FactTable, key: &str) -> &'a FactRow {
        table
            .rows()
            .iter()
            .find(|row| row.key == key)
            .unwrap_or_else(|| panic!("missing fact {key}"))
    }

    #[test]
    fn confidence_ladder_ranks_and_suspect_is_off_it() {
        assert!(Confidence::Verified.rank() > Confidence::Derived.rank());
        assert!(Confidence::Derived.rank() > Confidence::Measured.rank());
        assert!(Confidence::Measured.rank() > Confidence::Heuristic.rank());
        assert!(Confidence::Heuristic.rank() > Confidence::Default.rank());
        assert_eq!(Confidence::Suspect.rank(), None);
        assert!(Confidence::Verified.is_verified());
        assert!(!Confidence::Derived.is_verified());
    }

    #[test]
    fn cross_domain_comparison_is_refused() {
        let size = Fact::new(0x4c0u32, Domain::SizeOf, Source::Btf, Confidence::Derived);
        let stride = Fact::new(
            960u32,
            Domain::SlabStride,
            Source::RuntimeMeasurement,
            Confidence::Measured,
        );
        assert_eq!(
            size.expect_same_domain(&stride),
            Err(DomainMismatch {
                left: Domain::SizeOf,
                right: Domain::SlabStride,
            })
        );
        let other_size = Fact::new(0x4c0u32, Domain::SizeOf, Source::Btf, Confidence::Derived);
        assert!(size.expect_same_domain(&other_size).is_ok());
    }

    #[test]
    fn explain_marks_bootloader_btf_and_template_sources() {
        let mut symbols = BTreeMap::new();
        symbols.insert("off_init_task".to_string(), Some(0x20f_0000u64));
        let mut structs = BTreeMap::new();
        structs.insert("task_cred".to_string(), Some(0x768u32));
        structs.insert("task_prio".to_string(), Some(0x84u32));
        let cred = vec![
            ("copy_size".to_string(), "136".to_string()),
            ("caps_offset".to_string(), "48".to_string()),
        ];
        let geometry = vec![("waiter_shift", -2i64)];
        let extra = ConfExtraOffsets::default();
        let inputs = inputs(&symbols, &structs, &cred, &geometry, &extra);

        let field_sources = BTreeMap::new();
        // Fixture release, shaped like the corpus release: only the fallback
        // predicates read it. The layout fixture is template-based because a
        // unit test carries no BTF blob; this test asserts the struct/cred/phys
        // sources, not the layout rows.
        let layouts = crate::layout::derive(None, "6.1.25-android14-11-g8744d1c0cf31");
        let prov = Provenance {
            btf_present: true,
            phys_source: "xbl_config FDT",
            route_forced: false,
            field_sources: &field_sources,
            release: "6.1.25-android14-11-g8744d1c0cf31",
            layouts: &layouts,
        };
        let table = explain_conf(&inputs, &prov, Some("test"));

        let phys = row(&table, "devices.test.phys.load");
        assert_eq!(phys.value, "0xa8000000");
        assert_eq!(phys.source, Source::Bootloader);
        assert_eq!(phys.confidence, Confidence::Derived);

        let cred_field = row(&table, "kernel.struct.task.cred");
        assert_eq!(cred_field.source, Source::Btf);
        assert_eq!(cred_field.confidence, Confidence::Derived);

        // No BTF-derived source string for cred: on a BTF image the 5.x path
        // is BTF; this fixture is 6.1, where the template is the source.
        let copy_size = row(&table, "kernel.struct.cred.copy_size");
        assert_eq!(copy_size.source, Source::ReleaseTemplate);
        assert_eq!(copy_size.domain, Domain::SizeOf);

        let offset = row(&table, "kernel.symbols.init_task");
        assert_eq!(offset.source, Source::Kallsyms);
        assert_eq!(offset.domain.label(), "image-offset");

        let shift = row(&table, "ghostlock.syscalls.select_stack.native.waiter_shift");
        assert_eq!(shift.value, "-2");
        assert_eq!(shift.domain, Domain::FrameDepth);

        let rendered = table.render();
        assert!(rendered.contains("devices.test.phys.load"));
        assert!(rendered.contains("bootloader"));
        assert!(rendered.contains("btf"));
    }

    #[test]
    fn template_and_missing_fields_are_labelled() {
        let symbols = BTreeMap::new();
        let structs = BTreeMap::new();
        let cred: Vec<(String, String)> = Vec::new();
        let geometry: Vec<(&'static str, i64)> = Vec::new();
        let extra = ConfExtraOffsets::default();
        let inputs = inputs(&symbols, &structs, &cred, &geometry, &extra);
        let mut field_sources = BTreeMap::new();
        field_sources.insert("task_prio".to_string(), "template:5.10.236".to_string());
        // Full 5.10 android12-9 shape so the family fallback is exercised; a
        // bare "5.10.236" does not match `family::matches()`.
        let layouts = crate::layout::derive(None, "5.10.236-android12-9-00003-gfb24cf99ad97");
        let prov = Provenance {
            btf_present: false,
            phys_source: "unset",
            route_forced: true,
            field_sources: &field_sources,
            release: "5.10.236-android12-9-00003-gfb24cf99ad97",
            layouts: &layouts,
        };
        let table = explain_conf(&inputs, &prov, Some("test"));

        let phys = row(&table, "devices.test.phys.load");
        assert_eq!(phys.source, Source::CompiledDefault);
        assert_eq!(phys.confidence, Confidence::Default);
        let prio = row(&table, "kernel.struct.task.prio");
        assert_eq!(prio.source, Source::ReleaseTemplate);
        assert_eq!(prio.confidence, Confidence::Heuristic);
        let absent = row(&table, "kernel.struct.task.cred");
        assert_eq!(absent.source, Source::Unknown);
        assert_eq!(absent.confidence, Confidence::Unknown);
        let route = row(&table, "ghostlock.syscall");
        assert_eq!(route.source, Source::Operator);
    }
}
