// SPDX-License-Identifier: Apache-2.0

//! Output rendering for the 98260 extractor: the human report, the
//! `timer_98260` HOCON block, and the store-superset JSON object.

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

/// `DATA_SYMBOLS` macro name -> the profile field name.
fn data_field_name(macro_name: &str) -> &str {
    match macro_name {
        "SELINUX_STATE_STATIC" => "selinux_state",
        "PANIC_ON_WARN_STATIC" => "panic_on_warn",
        "ROOT_USER_STATIC" => "root_user",
        "INIT_USER_NS_STATIC" => "init_user_ns",
        "INIT_UCOUNTS_STATIC" => "init_ucounts",
        "INIT_GROUPS_STATIC" => "init_groups",
        other => other,
    }
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

/// One field of the `timer_98260` block.
enum FieldValue {
    /// A plain integer (HOCON will read it as a number).
    Num(u64),
    /// A 64-bit kernel address: quoted hex in HOCON, `"0x..."` in JSON.
    Hex(u64),
    /// A quoted string (the trigger shape).
    Str(&'static str),
}

/// The block's fields, in display order, for both renderers.
fn block_fields(
    analysis: &Analysis,
    endgame: Endgame,
) -> Result<Vec<(&'static str, FieldValue)>> {
    if endgame.includes_fake() {
        require_fake(analysis)?;
    } else {
        require_real(analysis)?;
    }
    let sizes = analysis
        .sizes
        .ok_or_else(|| ExtractError::new("sizes not derived"))?;
    let layout = analysis
        .layout
        .ok_or_else(|| ExtractError::new("layout not derived"))?;
    let sites = analysis
        .sites
        .ok_or_else(|| ExtractError::new("trace sites not derived"))?;
    let static_site = analysis
        .site("static_timer_site")
        .ok_or_else(|| ExtractError::new("static_timer_site not derived"))?;
    let trigger = analysis
        .trigger
        .as_ref()
        .ok_or_else(|| ExtractError::new("exec trigger shape not derived"))?;
    let mut out: Vec<(&'static str, FieldValue)> = vec![
        ("version", FieldValue::Num(1)),
        ("trigger", FieldValue::Str(trigger.shape.as_str())),
        ("timer_site_lower_21bit", FieldValue::Num(sites.timer_low21())),
        ("timer_object_size", FieldValue::Num(sizes.timer_alloc)),
        ("cred_request_size", FieldValue::Num(sizes.cred_req)),
        ("cred_alloc_size", FieldValue::Num(sizes.cred_alloc)),
        ("cred_uid_offset", FieldValue::Num(layout.cred_uid as u64)),
    ];
    if endgame.includes_fake() {
        out.push(("static_timer_site", FieldValue::Hex(static_site)));
        out.push((
            "task_alloc_site_lower_21bit",
            FieldValue::Num(sites.copy_process_low21()),
        ));
        out.push(("task_alloc_size", FieldValue::Num(sizes.task_bytes)));
        out.push(("task_cred_offset", FieldValue::Hex(layout.task_cred as u64)));
        for (field, macro_name) in [
            ("selinux_state", "SELINUX_STATE_STATIC"),
            ("panic_on_warn", "PANIC_ON_WARN_STATIC"),
            ("root_user", "ROOT_USER_STATIC"),
            ("init_user_ns", "INIT_USER_NS_STATIC"),
            ("init_ucounts", "INIT_UCOUNTS_STATIC"),
            ("init_groups", "INIT_GROUPS_STATIC"),
        ] {
            if let Some(Some(value)) = analysis.data_symbols.get(macro_name) {
                out.push((field, FieldValue::Hex(*value)));
            }
        }
        if let Some(cred) = analysis.cred {
            out.push((
                "cred_usage_size",
                FieldValue::Num(cred.usage_size as u64),
            ));
            out.push((
                "cred_caps_offset",
                FieldValue::Num(cred.caps_offset as u64),
            ));
            out.push(("cred_caps_count", FieldValue::Num(cred.caps_count as u64)));
            out.push((
                "cred_security_offset",
                FieldValue::Num(cred.security_offset as u64),
            ));
            out.push(("cred_user_offset", FieldValue::Num(cred.user_offset as u64)));
            if let Some(offset) = cred.user_ns_offset {
                out.push(("cred_user_ns_offset", FieldValue::Num(offset as u64)));
            }
            if let Some(offset) = cred.ucounts_offset {
                out.push(("cred_ucounts_offset", FieldValue::Num(offset as u64)));
            }
            out.push((
                "cred_group_info_offset",
                FieldValue::Num(cred.group_info_offset as u64),
            ));
            if let Some(offset) = cred.sid_offset {
                out.push(("security_sid_offset", FieldValue::Num(offset as u64)));
            }
        }
    }
    if let Some(node) = analysis.timer_node_off {
        out.push(("timer_node_offset", FieldValue::Num(node)));
    }
    if let (Some(offset), Some(size)) = (analysis.trace_gfp_off, analysis.trace_gfp_size) {
        out.push(("page_alloc_gfp_offset", FieldValue::Num(offset as u64)));
        out.push(("page_alloc_gfp_size", FieldValue::Num(size as u64)));
    }
    Ok(out)
}

fn render_fields_hocon(
    out: &mut String,
    fields: &[(&'static str, FieldValue)],
    indent: usize,
) {
    let pad = "  ".repeat(indent);
    for (key, value) in fields {
        match value {
            FieldValue::Num(value) => {
                let _ = writeln!(out, "{pad}{key} = {value}");
            }
            FieldValue::Hex(value) => {
                let _ = writeln!(out, "{pad}{key} = \"{}\"", hex64(*value));
            }
            FieldValue::Str(value) => {
                let _ = writeln!(out, "{pad}{key} = \"{value}\"");
            }
        }
    }
}

fn fields_json(fields: &[(&'static str, FieldValue)]) -> Value {
    let mut map = Map::new();
    for (key, value) in fields {
        let json_value = match value {
            FieldValue::Num(value) => json!(*value),
            FieldValue::Hex(value) => json!(hex64(*value)),
            FieldValue::Str(value) => json!(*value),
        };
        map.insert((*key).to_string(), json_value);
    }
    Value::Object(map)
}

/// The `timer_98260` block, as a HOCON object body (two-space indent).
///
/// 64-bit kernel addresses are quoted hex strings: a HOCON number cannot
/// represent them (`0xffffffc008191af8` exceeds the signed 64-bit range and
/// the parser would silently turn it into a string anyway).  Plain integers
/// stay numbers, because the app's parser reads `0x...` as a string.
pub fn render_timer_conf(analysis: &Analysis, endgame: Endgame) -> Result<String> {
    let fields = block_fields(analysis, endgame)?;
    let mut out = String::new();
    let _ = writeln!(out, "timer_98260 {{");
    render_fields_hocon(&mut out, &fields, 1);
    let _ = writeln!(out, "}}");
    Ok(out)
}

/// The canonical backend document, timer-only projection: the app's full
/// renderer adds the `kernel` and `native` blocks; the timer tool ignores
/// them, so this document is a complete input for it.
pub fn render_document(analysis: &Analysis, endgame: Endgame) -> Result<Value> {
    let release = analysis
        .release
        .clone()
        .ok_or_else(|| ExtractError::new("image carries no kernel release string"))?;
    let kernel_major = release
        .split('.')
        .next()
        .and_then(|major| major.parse::<u64>().ok());
    let fields = block_fields(analysis, endgame)?;
    let mut document = Map::new();
    document.insert("release".to_string(), json!(release));
    document.insert("schema_version".to_string(), json!(2));
    if let Some(major) = kernel_major {
        document.insert("kernel_major".to_string(), json!(major));
    }
    document.insert("timer_98260".to_string(), fields_json(&fields));
    Ok(Value::Object(document))
}

/// The `timer_98260` object for the store's options superset.
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
    let mut profile = Map::new();
    if let Some(sizes) = analysis.sizes {
        profile.insert("timer_object_size".to_string(), json!(sizes.timer_alloc));
        profile.insert("cred_request_size".to_string(), json!(sizes.cred_req));
        profile.insert("cred_alloc_size".to_string(), json!(sizes.cred_alloc));
        profile.insert("task_alloc_size".to_string(), json!(sizes.task_bytes));
    }
    if let Some(layout) = analysis.layout {
        profile.insert("task_cred_offset".to_string(), json!(hex64(layout.task_cred as u64)));
        profile.insert("cred_uid_offset".to_string(), json!(layout.cred_uid));
    }
    if let Some(sites) = analysis.sites {
        profile.insert("timer_site_lower_21bit".to_string(), json!(sites.timer_low21()));
        profile.insert(
            "task_alloc_site_lower_21bit".to_string(),
            json!(sites.copy_process_low21()),
        );
    }
    for (macro_name, value) in &analysis.data_symbols {
        if let Some(value) = value {
            profile.insert(data_field_name(macro_name).to_string(), json!(hex64(*value)));
        }
    }
    object.insert("profile".to_string(), Value::Object(profile));

    let sources: Map<String, Value> = analysis
        .sources
        .iter()
        .map(|(field, source)| (field.clone(), json!(source.as_str())))
        .collect();
    object.insert("sources".to_string(), Value::Object(sources));

    /* The block the ship script renders into the profile asset (the real path
     * until the fake endgame is verified; the store record above carries what
     * is derivable). */
    if analysis.check_real_path().is_empty() {
        if let Ok(fields) = block_fields(analysis, Endgame::Real) {
            object.insert("block".to_string(), fields_json(&fields));
        }
    }
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
