// SPDX-License-Identifier: Apache-2.0

//! Output rendering for the 98260 extractor: the human report, the cleaned
//! `timer` fragment, and the timer evidence object.

use std::fmt::Write as _;
use std::path::Path;

use serde_json::{json, Map, Value};

use crate::derive::{Analysis, TriggerCheck, TriggerShape, Verdict};
use crate::error::{ExtractError, Result};

/// The constant sets an emitted block may carry: the real five, the fake
/// group, or both.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Endgame {
    Real,
    Fake,
    Both,
}

impl Endgame {
    pub fn parse(text: &str) -> Option<Endgame> {
        match text {
            "real" => Some(Endgame::Real),
            "fake" => Some(Endgame::Fake),
            "real,fake" | "fake,real" => Some(Endgame::Both),
            _ => None,
        }
    }

    pub fn includes_fake(self) -> bool {
        matches!(self, Endgame::Fake | Endgame::Both)
    }
}

fn hex64(value: u64) -> String {
    format!("{value:#x}")
}

fn hex32(value: u32) -> String {
    format!("{value:#x}")
}

/// One line naming the read-lock shape the SIGKILL trigger depends on.
fn describe_trigger(trigger: &TriggerCheck) -> String {
    let lock = trigger
        .lock_call
        .as_ref()
        .map(|(name, at)| format!("{name} at +0x{at:x}"));
    match (trigger.shape, lock.as_deref()) {
        (TriggerShape::Killable, Some(lock)) => {
            format!("killable read lock in {} ({lock})", trigger.function)
        }
        (TriggerShape::CoreState, Some(lock)) => {
            let test = trigger
                .core_state_test
                .map(|at| format!(", core_state test at +0x{at:x}"))
                .unwrap_or_default();
            format!("core_state path in {} ({lock}{test})", trigger.function)
        }
        (_, Some(lock)) => format!(
            "unknown (non-killable lock in {}: {lock}, no core_state test)",
            trigger.function
        ),
        (_, None) => format!(
            "unknown (no read-lock shape in {})",
            trigger.function
        ),
    }
}

/// Short report: the vulnerability verdict plus the profile facts with sources.
pub fn render_text(analysis: &Analysis, image: &Path, profile: &str) -> String {
    let mut out = String::new();
    let _ = writeln!(out, "image      {}", image.display());
    let _ = writeln!(
        out,
        "release    {}",
        analysis.release.as_deref().unwrap_or("<none>")
    );
    let _ = writeln!(
        out,
        "symbols    {} recovered (kallsyms)",
        analysis.symbol_count
    );
    let _ = writeln!(
        out,
        "btf        {}",
        if analysis.has_btf { "yes" } else { "no" }
    );
    let _ = writeln!(
        out,
        "ikconfig   {}",
        if analysis.has_ikconfig { "yes" } else { "no" }
    );
    match &analysis.verdict {
        Verdict::Vulnerable => {
            let _ = writeln!(out, "vulnerable YES");
            if let Some(order) = &analysis.order {
                let before: Vec<&str> = order
                    .calls
                    .iter()
                    .take_while(|(name, _)| !name.starts_with("posix_cpu_timers_exit"))
                    .map(|(name, _)| name.as_str())
                    .collect();
                let _ = writeln!(
                    out,
                    "  begin_new_exec: {} run before posix_cpu_timers_exit",
                    if before.is_empty() {
                        "(nothing)".to_string()
                    } else {
                        before.join(", ")
                    }
                );
            }
            if let Some(del) = &analysis.del {
                let _ = writeln!(
                    out,
                    "  posix_cpu_timer_del: pid_task()==NULL at +0x{:x}, timerqueue_del {} ({})",
                    del.null_path,
                    if del.dequeues { "called" } else { "not called" },
                    if del.leaves_node_queued {
                        "stale node left in the timerqueue"
                    } else {
                        "node dequeued"
                    }
                );
            }
        }
        Verdict::Patched => {
            let _ = writeln!(out, "vulnerable NO (upstream fix present: POSIX timers are");
            let _ = writeln!(out, "  cleaned right after de_thread(); no exploit target)");
        }
        Verdict::Unknown(reason) => {
            let _ = writeln!(out, "vulnerable UNKNOWN ({reason})");
            let _ = writeln!(out, "  a check could not be performed; never reported patched");
        }
    }

    if let Some(trigger) = &analysis.trigger {
        let _ = writeln!(out, "trigger    {}", describe_trigger(trigger));
    }

    // --- the profile fields ------------------------------------------------
    let fake = analysis.check_fake_path();
    let real = analysis.check_real_path();
    let _ = writeln!(
        out,
        "profile    {}",
        if real.is_empty() {
            "real path derived".to_string()
        } else {
            format!("real path partial: missing {}", real.join(", "))
        }
    );
    let _ = writeln!(
        out,
        "           {}",
        if fake.is_empty() {
            "fake path derived".to_string()
        } else {
            format!("fake path partial: missing {}", fake.join(", "))
        }
    );
    if let Some(sites) = analysis.sites {
        let _ = writeln!(
            out,
            "  timer_site         {:#x}  low21 {:#x}  image",
            sites.timer_site,
            sites.timer_low21()
        );
        let _ = writeln!(
            out,
            "  copy_process_site  {:#x}  low21 {:#x}  image",
            sites.copy_process_site,
            sites.copy_process_low21()
        );
        if let Some(static_site) = analysis.site("static_timer_site") {
            let _ = writeln!(out, "  static_timer_site  {static_site:#x}  image");
        }
    }
    for (field, getter) in profile_fields(analysis) {
        if let Some((value, source)) = getter {
            let _ = writeln!(out, "  {field:<18} {value:<12} {source}");
        }
    }
    for (macro_name, value) in &analysis.data_symbols {
        match value {
            Some(value) => {
                let _ = writeln!(out, "  {macro_name:<18} {value:#x}  image");
            }
            None => {
                let _ = writeln!(out, "  {macro_name:<18} absent");
            }
        }
    }
    if let Some(cred) = analysis.cred {
        let _ = writeln!(
            out,
            "  cred overlay       usage {} caps {:#x}x{} security {:#x} user {:#x} user_ns {:?} ucounts {:?} group_info {:#x} sid {:?}  {}",
            cred.usage_size,
            cred.caps_offset,
            cred.caps_count,
            cred.security_offset,
            cred.user_offset,
            cred.user_ns_offset,
            cred.ucounts_offset,
            cred.group_info_offset,
            cred.sid_offset,
            analysis
                .source("cred_caps_offset")
                .map(|source| source.as_str())
                .unwrap_or("?"),
        );
    }
    if let Some(node) = analysis.timer_node_off {
        let _ = writeln!(out, "  timer_node_off     {node:#x}");
    }

    // --- runtime facts -----------------------------------------------------
    if let Some(runtime) = &analysis.runtime {
        let _ = writeln!(
            out,
            "runtime    {} {}",
            runtime.origin.as_deref().unwrap_or("?"),
            runtime.uname.as_deref().unwrap_or("?")
        );
        for (cache, line) in &runtime.slab {
            let _ = writeln!(
                out,
                "  slab {cache:<20} objsize {} objects {} objperslab {} pagesperslab {}",
                line.objsize, line.objects, line.objperslab, line.pagesperslab
            );
        }
    }

    for warning in &analysis.warnings {
        let _ = writeln!(out, "warning    {warning}");
    }
    for note in &analysis.notes {
        let _ = writeln!(out, "note       {note}");
    }
    let _ = writeln!(out, "profile    {profile}");
    out
}

/// The `(field, Option<(display, source)>)` list the text report prints.
fn profile_fields(analysis: &Analysis) -> Vec<(&'static str, Option<(String, &'static str)>)> {
    let source = |field: &str| analysis.source(field).map(|s| s.as_str()).unwrap_or("?");
    let sizes = analysis.sizes;
    let layout = analysis.layout;
    vec![
        (
            "timer_object_size",
            sizes
                .filter(|sizes| sizes.timer_alloc != 0)
                .map(|sizes| (format!("{}", sizes.timer_alloc), source("timer_object_size"))),
        ),
        (
            "cred_request_size",
            sizes
                .filter(|sizes| sizes.cred_req != 0)
                .map(|sizes| (format!("{}", sizes.cred_req), source("cred_request_size"))),
        ),
        (
            "cred_alloc_size",
            sizes
                .filter(|sizes| sizes.cred_alloc != 0)
                .map(|sizes| (format!("{}", sizes.cred_alloc), source("cred_alloc_size"))),
        ),
        (
            "task_alloc_size",
            sizes
                .filter(|sizes| sizes.task_bytes != 0)
                .map(|sizes| (format!("{}", sizes.task_bytes), source("task_alloc_size"))),
        ),
        (
            "task_cred_offset",
            layout
                .filter(|layout| layout.task_cred != 0)
                .map(|layout| (format!("{:#x}", layout.task_cred), source("task_cred_offset"))),
        ),
        (
            "cred_uid_offset",
            layout
                .filter(|layout| layout.cred_uid != 0)
                .map(|layout| (format!("{:#x}", layout.cred_uid), source("cred_uid_offset"))),
        ),
    ]
}

fn require_real(analysis: &Analysis) -> Result<()> {
    let missing = analysis.check_real_path();
    if missing.is_empty() {
        Ok(())
    } else {
        Err(ExtractError::new(format!(
            "the real-path profile is incomplete; missing: {}",
            missing.join(", ")
        )))
    }
}

fn require_fake(analysis: &Analysis) -> Result<()> {
    let missing = analysis.check_fake_path();
    if missing.is_empty() {
        Ok(())
    } else {
        Err(ExtractError::new(format!(
            "the fake-path profile is incomplete; missing: {}",
            missing.join(", ")
        )))
    }
}

/// The cleaned facts the timer engine owns. This is the document fragment the
/// app and the store merge into the v3 document — never a second profile
/// spelling: `kernel.symbols` (the five the timer tool needs),
/// `kernel.struct.cred` (the security sid and the reference offsets) and the
/// `timer` block, all image-relative.
struct FragFacts {
    symbols: Vec<(&'static str, Option<u64>)>,
    usage_size: Option<u32>,
    uid: Option<u32>,
    security: Option<u32>,
    caps_offset: Option<u32>,
    caps_count: Option<u32>,
    security_sid: Option<u32>,
    refs: Vec<(&'static str, Option<u32>)>,
    trigger: &'static str,
    timer_offset: u64,
    task_alloc_low_bits: u64,
    object_size: u64,
    task_alloc_size: u64,
    node_offset: Option<u64>,
    cred_request_size: u64,
    cred_alloc_size: u64,
    trace_gfp: Option<(u32, u32)>,
}

fn frag_facts(analysis: &Analysis, endgame: Endgame) -> Result<FragFacts> {
    if endgame.includes_fake() {
        require_fake(analysis)?;
    } else {
        require_real(analysis)?;
    }
    let sizes = analysis
        .sizes
        .ok_or_else(|| ExtractError::new("sizes not derived"))?;
    let sites = analysis
        .sites
        .ok_or_else(|| ExtractError::new("trace sites not derived"))?;
    let trigger = analysis
        .trigger
        .as_ref()
        .ok_or_else(|| ExtractError::new("exec trigger shape not derived"))?;
    let relative = |value: u64| analysis.text_base.and_then(|base| value.checked_sub(base));
    let symbols = [
        ("panic_on_warn", "PANIC_ON_WARN_STATIC"),
        ("root_user", "ROOT_USER_STATIC"),
        ("init_user_ns", "INIT_USER_NS_STATIC"),
        ("init_ucounts", "INIT_UCOUNTS_STATIC"),
        ("init_groups", "INIT_GROUPS_STATIC"),
    ]
    .into_iter()
    .map(|(field, macro_name)| {
        let value = analysis
            .data_symbols
            .get(macro_name)
            .copied()
            .flatten()
            .and_then(relative);
        (field, value)
    })
    .collect();
    let (usage_size, uid, security, caps_offset, caps_count, security_sid, refs) =
        match &analysis.cred {
        Some(cred) => (
            Some(cred.usage_size),
            analysis.layout.map(|layout| layout.cred_uid),
            Some(cred.security_offset),
            Some(cred.caps_offset),
            Some(cred.caps_count),
            cred.sid_offset,
            vec![
                ("user", Some(cred.user_offset)),
                ("user_ns", cred.user_ns_offset),
                ("ucounts", cred.ucounts_offset),
                ("group_info", Some(cred.group_info_offset)),
            ],
        ),
        None => (
            None,
            None,
            None,
            None,
            None,
            None,
            vec![
                ("user", None),
                ("user_ns", None),
                ("ucounts", None),
                ("group_info", None),
            ],
        ),
    };
    Ok(FragFacts {
        symbols,
        usage_size,
        uid,
        security,
        caps_offset,
        caps_count,
        security_sid,
        refs,
        trigger: trigger.shape.as_str(),
        timer_offset: sites.timer_site,
        task_alloc_low_bits: sites.copy_process_low21(),
        object_size: sizes.timer_alloc,
        task_alloc_size: sizes.task_bytes,
        node_offset: analysis.timer_node_off,
        cred_request_size: sizes.cred_req,
        cred_alloc_size: sizes.cred_alloc,
        trace_gfp: match (analysis.trace_gfp_off, analysis.trace_gfp_size) {
            (Some(offset), Some(size)) => Some((offset, size)),
            _ => None,
        },
    })
}

fn hex_or_null(value: Option<u64>) -> String {
    match value {
        Some(value) => format!("\"{}\"", hex64(value)),
        None => "null".to_string(),
    }
}

/// The cleaned fragment as HOCON (the app's `--emit-timer-conf` output): the
/// `kernel` facts the timer owns and the `timer` block, image-relative.
pub fn render_timer_conf(analysis: &Analysis, endgame: Endgame) -> Result<String> {
    let facts = frag_facts(analysis, endgame)?;
    let mut out = String::new();
    let _ = writeln!(out, "kernel {{");
    let _ = writeln!(out, "  symbols {{");
    for (name, value) in &facts.symbols {
        let _ = writeln!(out, "    {name} = {}", hex_or_null(*value));
    }
    let _ = writeln!(out, "  }}");
    let _ = writeln!(out, "  struct {{");
    let _ = writeln!(out, "    cred {{");
    let _ = writeln!(out, "      usage {{");
    let _ = writeln!(
        out,
        "        size = {}",
        facts.usage_size.map(|value| value.to_string()).unwrap_or_else(|| "null".to_string())
    );
    let _ = writeln!(out, "      }}");
    let _ = writeln!(
        out,
        "      uid = {}",
        facts.uid.map(|value| value.to_string()).unwrap_or_else(|| "null".to_string())
    );
    let _ = writeln!(
        out,
        "      security = {}",
        facts.security.map(|value| value.to_string()).unwrap_or_else(|| "null".to_string())
    );
    let _ = writeln!(out, "      caps {{");
    let _ = writeln!(
        out,
        "        offset = {}",
        facts.caps_offset.map(|value| value.to_string()).unwrap_or_else(|| "null".to_string())
    );
    let _ = writeln!(
        out,
        "        count = {}",
        facts.caps_count.map(|value| value.to_string()).unwrap_or_else(|| "null".to_string())
    );
    let _ = writeln!(out, "      }}");
    match facts.security_sid {
        Some(value) => {
            let _ = writeln!(out, "      security_sid = {value}");
        }
        None => {
            let _ = writeln!(out, "      security_sid = null");
        }
    }
    let _ = writeln!(out, "      refs {{");
    for (name, value) in &facts.refs {
        let _ = writeln!(out, "        {name} {{");
        match value {
            Some(value) => {
                let _ = writeln!(out, "          offset = {value}");
            }
            None => {
                let _ = writeln!(out, "          offset = null");
            }
        }
        let _ = writeln!(out, "        }}");
    }
    let _ = writeln!(out, "      }}");
    let _ = writeln!(out, "    }}");
    let _ = writeln!(out, "  }}");
    let _ = writeln!(out, "}}");
    let _ = writeln!(out, "timer {{");
    let _ = writeln!(out, "  trigger = \"{}\"", facts.trigger);
    let _ = writeln!(out, "  match_bits = 21");
    let _ = writeln!(out, "  sites {{");
    let _ = writeln!(out, "    timer {{");
    let _ = writeln!(out, "      offset = \"{}\"", hex64(facts.timer_offset));
    let _ = writeln!(out, "    }}");
    let _ = writeln!(out, "    task_alloc {{");
    let _ = writeln!(
        out,
        "      low_bits = \"{}\"",
        hex64(facts.task_alloc_low_bits)
    );
    let _ = writeln!(out, "    }}");
    let _ = writeln!(out, "  }}");
    let _ = writeln!(out, "  object_size = {}", facts.object_size);
    let _ = writeln!(out, "  task_alloc_size = {}", facts.task_alloc_size);
    match facts.node_offset {
        Some(value) => {
            let _ = writeln!(out, "  node_offset = {value}");
        }
        None => {
            let _ = writeln!(out, "  node_offset = null");
        }
    }
    let _ = writeln!(out, "  cred {{");
    let _ = writeln!(out, "    request_size = {}", facts.cred_request_size);
    let _ = writeln!(out, "    alloc_size = {}", facts.cred_alloc_size);
    let _ = writeln!(out, "  }}");
    let _ = writeln!(out, "  trace {{");
    let _ = writeln!(out, "    gfp {{");
    match facts.trace_gfp {
        Some((offset, size)) => {
            let _ = writeln!(out, "      offset = {offset}");
            let _ = writeln!(out, "      size = {size}");
        }
        None => {
            let _ = writeln!(out, "      offset = null");
            let _ = writeln!(out, "      size = null");
        }
    }
    let _ = writeln!(out, "    }}");
    let _ = writeln!(out, "  }}");
    let _ = writeln!(out, "}}");
    Ok(out)
}

/// The canonical backend document, timer-only projection: the cleaned fragment
/// plus the document's identity keys. The app's full renderer adds the rest;
/// the timer tool ignores them, so this is a complete input for it.
pub fn render_document(analysis: &Analysis, endgame: Endgame) -> Result<Value> {
    let release = analysis
        .release
        .clone()
        .ok_or_else(|| ExtractError::new("image carries no kernel release string"))?;
    let facts = frag_facts(analysis, endgame)?;
    let symbols: Map<String, Value> = facts
        .symbols
        .iter()
        .map(|(name, value)| {
            (
                (*name).to_string(),
                value.map(|value| json!(hex64(value))).unwrap_or(Value::Null),
            )
        })
        .collect();
    let refs: Map<String, Value> = facts
        .refs
        .iter()
        .map(|(name, value)| {
            (
                (*name).to_string(),
                json!({ "offset": value.map(|value| json!(value)).unwrap_or(Value::Null) }),
            )
        })
        .collect();
    let timer = json!({
        "trigger": facts.trigger,
        "match_bits": 21,
        "sites": {
            "timer": { "offset": hex64(facts.timer_offset) },
            "task_alloc": { "low_bits": hex64(facts.task_alloc_low_bits) },
        },
        "object_size": facts.object_size,
        "task_alloc_size": facts.task_alloc_size,
        "node_offset": facts.node_offset.map(|value| json!(value)).unwrap_or(Value::Null),
        "cred": {
            "request_size": facts.cred_request_size,
            "alloc_size": facts.cred_alloc_size,
        },
        "trace": {
            "gfp": match facts.trace_gfp {
                Some((offset, size)) => json!({ "offset": offset, "size": size }),
                None => json!({ "offset": Value::Null, "size": Value::Null }),
            },
        },
    });
    Ok(json!({
        "schema_version": 3,
        "release": release,
        "devices": Value::Null,
        "kernel": {
            "symbols": Value::Object(symbols),
            "struct": {
                "cred": {
                    "uid": facts.uid.map(|value| json!(value)).unwrap_or(Value::Null),
                    "security": facts.security.map(|value| json!(value)).unwrap_or(Value::Null),
                    "usage": {
                        "size": facts.usage_size.map(|value| json!(value)).unwrap_or(Value::Null),
                    },
                    "caps": {
                        "offset": facts.caps_offset.map(|value| json!(value)).unwrap_or(Value::Null),
                        "count": facts.caps_count.map(|value| json!(value)).unwrap_or(Value::Null),
                    },
                    "security_sid": facts.security_sid.map(|value| json!(value)).unwrap_or(Value::Null),
                    "refs": Value::Object(refs),
                },
            },
        },
        "ghostlock": Value::Null,
        "timer": timer,
    }))
}

/// The timer evidence object for the store's options superset.
pub fn render_superset(analysis: &Analysis) -> Value {
    let real = analysis.check_real_path();
    let fake = analysis.check_fake_path();
    let mut object = Map::new();
    object.insert("schema".to_string(), json!(1));
    if let Some(release) = &analysis.release {
        object.insert("release".to_string(), json!(release));
    }
    object.insert(
        "verdict".to_string(),
        json!(match &analysis.verdict {
            Verdict::Vulnerable => "vulnerable",
            Verdict::Patched => "patched",
            Verdict::Unknown(_) => "unknown",
        }),
    );
    if let Verdict::Unknown(reason) = &analysis.verdict {
        object.insert("verdict_reason".to_string(), json!(reason));
    } else {
        object.insert(
            "verdict_reason".to_string(),
            json!(match (&analysis.order, &analysis.del) {
                (Some(order), Some(del)) if order.cleanup_first => {
                    if del.leaves_node_queued {
                        "timer cleanup runs before the fallible exec steps, but the pid_task()==NULL path still skips timerqueue_del"
                    } else {
                        "timer cleanup runs before the fallible exec steps and the NULL path dequeues"
                    }
                }
                (Some(_), Some(del)) if del.leaves_node_queued => {
                    "timer cleanup runs after the fallible exec steps and the pid_task()==NULL path skips timerqueue_del"
                }
                (Some(_), Some(_)) => "timer cleanup runs after the fallible exec steps, but the NULL path dequeues",
                _ => "a check could not be performed",
            })
        );
    }
    object.insert(
        "derivable".to_string(),
        json!(if real.is_empty() { "derived" } else { "partial" }),
    );
    if !real.is_empty() {
        object.insert("missing".to_string(), json!(real));
    } else if !fake.is_empty() {
        object.insert("fake_missing".to_string(), json!(fake));
    }
    let mut endgames = Vec::new();
    if real.is_empty() {
        endgames.push("real");
    }
    if fake.is_empty() {
        endgames.push("fake");
    }
    object.insert("endgames".to_string(), json!(endgames));

    if let Some(sites) = analysis.sites {
        object.insert(
            "sites".to_string(),
            json!({
                "timer_site": hex64(sites.timer_site),
                "timer_site_lower_21bit": format!("{:#x}", sites.timer_low21()),
                "copy_process_site": hex64(sites.copy_process_site),
                "task_alloc_site_lower_21bit": format!("{:#x}", sites.copy_process_low21()),
                "static_timer_site": analysis.site("static_timer_site").map(hex64),
            }),
        );
    }
    let sources: Map<String, Value> = analysis
        .sources
        .iter()
        .map(|(field, source)| (field.clone(), json!(source.as_str())))
        .collect();
    object.insert("sources".to_string(), Value::Object(sources));

    if !analysis.corroborated.is_empty() {
        let corroborated: Map<String, Value> = analysis
            .corroborated
            .iter()
            .map(|(field, source)| (field.clone(), json!(source.as_str())))
            .collect();
        object.insert("corroborated".to_string(), Value::Object(corroborated));
    }

    if let Some(cred) = analysis.cred {
        object.insert(
            "cred_layout".to_string(),
            json!({
                "usage_size": cred.usage_size,
                "caps_offset": cred.caps_offset,
                "caps_count": cred.caps_count,
                "security_offset": cred.security_offset,
                "user_offset": cred.user_offset,
                "user_ns_offset": cred.user_ns_offset,
                "ucounts_offset": cred.ucounts_offset,
                "group_info_offset": cred.group_info_offset,
                "sid_offset": cred.sid_offset,
            }),
        );
    }
    if let Some(node) = analysis.timer_node_off {
        object.insert("timer_node_offset".to_string(), json!(hex32(node as u32)));
    }
    if let (Some(offset), Some(size)) = (analysis.trace_gfp_off, analysis.trace_gfp_size) {
        object.insert(
            "trace".to_string(),
            json!({
                "gfp_offset": offset,
                "gfp_size": size,
                "source": analysis
                    .source("trace.gfp_offset")
                    .map(|source| source.as_str())
                    .unwrap_or("?"),
            }),
        );
    }
    if let Some(order) = &analysis.order {
        object.insert(
            "exec_order".to_string(),
            json!({
                "known": order.known,
                "cleanup_first": order.cleanup_first,
                "calls": order.calls.iter().map(|(name, addr)| json!({
                    "call": name,
                    "at": hex64(*addr),
                })).collect::<Vec<_>>(),
            }),
        );
    }
    if let Some(trigger) = &analysis.trigger {
        object.insert(
            "trigger".to_string(),
            json!({
                "shape": trigger.shape.as_str(),
                "function": trigger.function,
                "lock_call": trigger.lock_call.as_ref().map(|(name, at)| json!({
                    "call": name,
                    "at": hex64(*at),
                })),
                "core_state_test": trigger.core_state_test.map(hex64),
            }),
        );
    }
    if let Some(del) = &analysis.del {
        object.insert(
            "timer_del".to_string(),
            json!({
                "null_path": hex64(del.null_path),
                "dequeues": del.dequeues,
                "leaves_node_queued": del.leaves_node_queued,
            }),
        );
    }
    if analysis.has_ikconfig {
        let config: Map<String, Value> = analysis
            .config
            .iter()
            .filter(|(key, _)| {
                crate::ikconfig::REQUIRED_CONFIG
                    .iter()
                    .any(|(symbol, _)| symbol == key)
                    || crate::ikconfig::INTERESTING_CONFIG.contains(&key.as_str())
            })
            .map(|(key, value)| (key.clone(), json!(value)))
            .collect();
        object.insert("config".to_string(), Value::Object(config));
    }
    if let Some(runtime) = &analysis.runtime {
        object.insert(
            "runtime".to_string(),
            json!({
                "origin": runtime.origin.clone(),
                "uname": runtime.uname.clone(),
                "slab": runtime.slab.iter().map(|(cache, line)| (cache.clone(), json!({
                    "objsize": line.objsize,
                    "objects": line.objects,
                    "objperslab": line.objperslab,
                    "pagesperslab": line.pagesperslab,
                }))).collect::<Map<String, Value>>(),
                "probe": runtime.probe.iter().map(|(key, value)| (key.clone(), json!(hex64(*value)))).collect::<Map<String, Value>>(),
            }),
        );
    }
    if !analysis.warnings.is_empty() {
        object.insert("warnings".to_string(), json!(analysis.warnings));
    }
    Value::Object(object)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endgame_parsing_is_strict() {
        assert_eq!(Endgame::parse("real"), Some(Endgame::Real));
        assert_eq!(Endgame::parse("fake"), Some(Endgame::Fake));
        assert_eq!(Endgame::parse("real,fake"), Some(Endgame::Both));
        assert_eq!(Endgame::parse("real, fake"), None);
        assert_eq!(Endgame::parse("both"), None);
        assert!(Endgame::Both.includes_fake());
        assert!(!Endgame::Real.includes_fake());
    }

    #[test]
    fn hex_strings_round_trip_the_64_bit_fields() {
        assert_eq!(hex64(0xffff_ffc0_0819_1af8), "0xffffffc008191af8");
        // The decimal form does not fit a signed 64-bit HOCON number.
        assert!(0xffff_ffc0_0819_1af8u64 > i64::MAX as u64);
    }
}
