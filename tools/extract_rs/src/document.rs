//! The cleaned v3 profile document: one tree builder and one HOCON renderer.
//!
//! Every artifact that carries profile facts goes through this module — the
//! runnable cut (`report::render_conf_with_device`), the route superset the
//! CLI prints and the store records (`options::build`). The tree keeps its own
//! insertion order: the document's key order is part of its human-facing form
//! (the Parameters screen and the stored asset diff), and `serde_json`'s map
//! would sort it.

use std::collections::BTreeMap;

use serde::ser::{Serialize, SerializeMap, Serializer};

use crate::report::{ConfInputs, CONF_TASK_FIELDS};

/// An ordered profile-document node.
#[derive(Debug, Clone, PartialEq)]
pub enum Node {
    Null,
    Bool(bool),
    Int(i64),
    Str(String),
    Obj(Vec<(String, Node)>),
}

impl Serialize for Node {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            Node::Null => serializer.serialize_unit(),
            Node::Bool(value) => serializer.serialize_bool(*value),
            Node::Int(value) => serializer.serialize_i64(*value),
            Node::Str(value) => serializer.serialize_str(value),
            Node::Obj(entries) => {
                let mut map = serializer.serialize_map(Some(entries.len()))?;
                for (key, value) in entries {
                    map.serialize_entry(key, value)?;
                }
                map.end()
            }
        }
    }
}

fn obj(entries: Vec<(&str, Node)>) -> Node {
    Node::Obj(entries.into_iter().map(|(key, value)| (key.to_string(), value)).collect())
}

fn hex(value: u64) -> Node {
    Node::Str(format!("0x{value:x}"))
}

fn hex_or_null(value: Option<u64>) -> Node {
    value.map(hex).unwrap_or(Node::Null)
}

/// A decimal (or `0x…`) text value as a quoted hex address; `null` stays null.
fn literal_hex(text: &str) -> Node {
    let text = text.trim();
    if text == "null" || text.is_empty() {
        return Node::Null;
    }
    text.parse::<i64>()
        .ok()
        .map(|value| hex(value as u64))
        .or_else(|| text.parse::<u64>().ok().map(hex))
        .unwrap_or(Node::Null)
}

/// A decimal text value as a number; `null` (or unparseable) stays null.
fn literal_number(text: &str) -> Node {
    let text = text.trim();
    if text == "null" || text.is_empty() {
        return Node::Null;
    }
    text.parse::<i64>().map(Node::Int).unwrap_or(Node::Null)
}

fn number_or_null(value: Option<i64>) -> Node {
    value.map(Node::Int).unwrap_or(Node::Null)
}

/// One carrier's geometry, already materialized by the caller.
pub enum Geometry<'a> {
    Map(&'a BTreeMap<String, i64>),
    /// Several maps, searched in order: the route-superset form falls back to
    /// the first chosen route that states the waiter layouts.
    Maps(Vec<&'a BTreeMap<String, i64>>),
    Pairs(&'a [(&'static str, i64)]),
}

impl Geometry<'_> {
    fn get(&self, key: &str) -> Option<i64> {
        match self {
            Geometry::Map(map) => map.get(key).copied(),
            Geometry::Maps(maps) => maps.iter().find_map(|map| map.get(key).copied()),
            Geometry::Pairs(pairs) => {
                pairs.iter().find(|(name, _)| *name == key).map(|(_, value)| *value)
            }
        }
    }
}

/// One carrier block a syscall declares.
pub struct CarrierDoc<'a> {
    pub name: &'a str,
    pub geometry: Geometry<'a>,
}

/// One syscall of the catalogue: every carrier the image can run.
pub struct RouteDoc<'a> {
    pub name: &'a str,
    pub carriers: Vec<CarrierDoc<'a>>,
}

#[derive(Clone, Copy)]
pub struct AnchorDoc {
    pub image: u64,
    pub bytes: Option<u64>,
    pub stride: Option<u64>,
    pub scratch: Option<u64>,
}

pub struct DocumentInput<'a> {
    pub facts: &'a ConfInputs<'a>,
    /// The device entry to state: `(name, load, offset)`; `None` is null.
    pub devices: Option<(&'a str, Option<u64>, Option<u64>)>,
    /// The syscalls to state, in catalogue order. A syscall absent is null.
    pub routes: Vec<RouteDoc<'a>>,
    /// The syscall the document selects; `None` states null.
    pub selected: Option<&'a str>,
    pub anchor: Option<AnchorDoc>,
    /// The selected route's geometry, used for the waiter-layout fallbacks.
    pub geometry: Geometry<'a>,
}

/// Build the document tree.
pub fn build(input: &DocumentInput<'_>) -> Node {
    let mut entries = vec![
        ("schema_version", Node::Int(3)),
        ("release", Node::Str(input.facts.release.to_string())),
        (
            "devices",
            match input.devices {
                Some((name, load, offset)) => obj(vec![(
                    name,
                    obj(vec![(
                        "phys",
                        obj(vec![
                            ("load", hex_or_null(load)),
                            ("offset", hex_or_null(offset)),
                        ]),
                    )]),
                )]),
                None => Node::Null,
            },
        ),
        ("kernel", kernel(input)),
        ("ghostlock", ghostlock(input)),
        ("timer", Node::Null),
    ];
    entries.shrink_to_fit();
    Node::Obj(entries.into_iter().map(|(key, value)| (key.to_string(), value)).collect())
}

fn kernel(input: &DocumentInput<'_>) -> Node {
    let facts = input.facts;
    let offsets = crate::report::conf_offsets(facts.symbols, facts.extra_offsets);
    let offset_value = |key: &str| {
        offsets
            .iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value.as_str())
            .unwrap_or("null")
    };
    let mut symbols = Vec::new();
    for (key, source) in [
        ("init_task", "init_task"),
        ("init_cred", "init_cred"),
        ("root_task_group", "root_task_group"),
        ("empty_zero_page", "empty_zero_page"),
        ("selinux_state", "selinux_enforcing"),
        ("selinux_blob_sizes", "selinux_blob_sizes"),
        ("security_hook_heads", "security_hook_heads"),
        ("nf_logger", "slide_nfulnl_logger"),
        ("loggers_0_1", "slide_loggers_0_1"),
        ("boot_id", "slide_boot_id"),
    ] {
        symbols.push((key, literal_hex(offset_value(source))));
    }
    for key in [
        "panic_on_warn",
        "root_user",
        "init_user_ns",
        "init_ucounts",
        "init_groups",
    ] {
        symbols.push((key, Node::Null));
    }

    let mut task = Vec::new();
    for (macro_name, key) in CONF_TASK_FIELDS {
        task.push((
            *key,
            number_or_null(
                facts
                    .structs
                    .get(*macro_name)
                    .copied()
                    .flatten()
                    .map(|value| value as i64),
            ),
        ));
    }

    let mut cred_map: BTreeMap<String, String> = facts.cred.iter().cloned().collect();
    if let Some(layouts) = facts.layouts {
        for (key, fact) in [
            ("usage_size", &layouts.cred_usage_size),
            ("uid_offset", &layouts.cred_uid_offset),
            ("security_offset", &layouts.cred_security_offset),
        ] {
            if let Some(value) = crate::report::btf_decimal(fact) {
                cred_map.insert(key.to_string(), value);
            }
        }
        /* The template's cap set is 6.x-shaped; an image that states its
         * offset in BTF wins (5.15.194 is 40/5 where the template says 48/3).
         * The copy size stays on the 6.x rule: on 5.15 `security + 8` is not
         * `sizeof(cred)` (the fields follow `security`), so the 5.x block's
         * own copy size is already right. */
        if let Some(value) = crate::report::btf_decimal(&layouts.cred_caps_offset) {
            cred_map.insert("caps_offset".to_string(), value);
        }
        if facts
            .release
            .split('.')
            .next()
            .and_then(|part| part.parse::<u32>().ok())
            != Some(5)
        {
            if let Some(value) = crate::report::btf_decimal(&layouts.cred_copy_size) {
                cred_map.insert("copy_size".to_string(), value);
            }
        }
    }
    let cred_value = |key: &str| cred_map.get(key).map(String::as_str).unwrap_or("null");

    let cred = {
        let ref_count = cred_value("ref_count").trim().parse::<u32>().ok();
        /* A 5.x cred carries three references (the 6.x `ucounts` member does
         * not exist there); the slots are named by the layout, not by
         * position. */
        let slots: [(&str, Option<usize>); 4] = match ref_count {
            Some(3) => [
                ("user", Some(0)),
                ("user_ns", Some(1)),
                ("ucounts", None),
                ("group_info", Some(2)),
            ],
            Some(4) => [
                ("user", Some(0)),
                ("user_ns", Some(1)),
                ("ucounts", Some(2)),
                ("group_info", Some(3)),
            ],
            _ => [
                ("user", None),
                ("user_ns", None),
                ("ucounts", None),
                ("group_info", None),
            ],
        };
        let mut refs = Vec::new();
        for (name, index) in slots {
            let (offset, image) = match index {
                Some(index) => (
                    literal_number(cred_value(&format!("ref{index}_offset"))),
                    literal_hex(cred_value(&format!("ref{index}_image"))),
                ),
                None => (Node::Null, Node::Null),
            };
            refs.push((name, obj(vec![("offset", offset), ("image", image)])));
        }
        obj(vec![
            ("copy_size", literal_number(cred_value("copy_size"))),
            (
                "usage",
                obj(vec![
                    ("offset", literal_number(cred_value("usage_offset"))),
                    ("size", literal_number(cred_value("usage_size"))),
                    ("value", literal_number(cred_value("usage_value"))),
                ]),
            ),
            ("uid", literal_number(cred_value("uid_offset"))),
            ("security", literal_number(cred_value("security_offset"))),
            ("security_sid", Node::Null),
            (
                "caps",
                obj(vec![
                    ("offset", literal_number(cred_value("caps_offset"))),
                    ("count", literal_number(cred_value("caps_count"))),
                    ("mask", literal_hex(cred_value("caps_value"))),
                ]),
            ),
            ("refs", obj(refs)),
        ])
    };

    let geometry = &input.geometry;
    let compact = facts
        .layouts
        .and_then(|layouts| crate::report::btf_flag(&layouts.waiter_compact))
        .map(|value| if value == "1" { 1 } else { 0 })
        .or_else(|| geometry.get("compact_waiter"));
    let shape = match compact {
        Some(1) => Node::Str("compact".to_string()),
        Some(0) => Node::Str("node".to_string()),
        _ => Node::Null,
    };
    let waiter_value = |source: &crate::fact::Fact<String>| {
        crate::report::btf_decimal(source)
            .map(|value| literal_number(&value))
            .unwrap_or(Node::Null)
    };
    let waiter = match facts.layouts {
        Some(layouts) => {
            let task = match waiter_value(&layouts.waiter_task) {
                Node::Null => number_or_null(geometry.get("task_offset")),
                value => value,
            };
            let lock = match waiter_value(&layouts.waiter_lock) {
                Node::Null => number_or_null(geometry.get("lock_offset")),
                value => value,
            };
            obj(vec![
                ("shape", shape),
                ("pi_tree", waiter_value(&layouts.waiter_pi_tree)),
                ("task", task),
                ("lock", lock),
                ("wake_state", waiter_value(&layouts.waiter_wake_state)),
            ])
        }
        None => obj(vec![
            ("shape", shape),
            ("pi_tree", Node::Null),
            ("task", number_or_null(geometry.get("task_offset"))),
            ("lock", number_or_null(geometry.get("lock_offset"))),
            ("wake_state", Node::Null),
        ]),
    };
    let rt_mutex_size = facts
        .layouts
        .and_then(|layouts| crate::report::btf_u32(&layouts.rt_mutex_size))
        .map(|value| literal_number(&value))
        .unwrap_or(Node::Null);
    let selinux_state = match facts.stage1.as_ref() {
        Some(stage1) => obj(vec![
            ("enforcing", Node::Int(stage1.selinux_enforcing as i64)),
            ("initialized", Node::Int(stage1.selinux_initialized as i64)),
        ]),
        None => obj(vec![("enforcing", Node::Null), ("initialized", Node::Null)]),
    };
    let major = facts
        .release
        .split('.')
        .next()
        .and_then(|part| part.parse::<u32>().ok());
    let collisions = match major {
        Some(5) => Node::Int(8),
        Some(6) if crate::symbols::kernel_layout_verified(Some(facts.release)) => Node::Int(4),
        _ => Node::Null,
    };
    let dirtyfrag = if facts
        .pipeline_order
        .iter()
        .any(|name| name.eq_ignore_ascii_case("dirtyfrag"))
    {
        "feasible"
    } else {
        "unknown"
    };

    obj(vec![
        ("image_base", hex_or_null(facts.image_base)),
        ("symbols", obj(symbols)),
        (
            "struct",
            obj(vec![
                ("task", obj(task)),
                ("cred", cred),
                ("waiter", waiter),
                ("rt_mutex", obj(vec![("size", rt_mutex_size)])),
                ("selinux_state", selinux_state),
            ]),
        ),
        (
            "heap",
            obj(vec![
                ("stride", Node::Null),
                ("collisions", collisions),
            ]),
        ),
        ("dirtyfrag", Node::Str(dirtyfrag.to_string())),
        ("kdp", Node::Bool(facts.kdp == Some(true))),
    ])
}

fn ghostlock(input: &DocumentInput<'_>) -> Node {
    let selected = input.selected;
    let selected_route = selected.and_then(|name| {
        input.routes.iter().find(|route| route.name == name)
    });
    let carrier = match selected_route {
        Some(route) => {
            let native = route.carriers.iter().any(|carrier| carrier.name == "native");
            let compat = route.carriers.iter().any(|carrier| carrier.name == "compat");
            match (native, compat) {
                (true, true) => Node::Str("native".to_string()),
                (false, true) => Node::Str("compat".to_string()),
                _ => Node::Null,
            }
        }
        None => Node::Null,
    };
    let names = ["select_stack", "sigreturn", "multicast_waiter", "tcp_zerocopy"];
    let mut syscalls = Vec::new();
    for name in names {
        match input.routes.iter().find(|route| route.name == name) {
            Some(route) => {
                let native = route.carriers.iter().find(|carrier| carrier.name == "native");
                let compat = route.carriers.iter().find(|carrier| carrier.name == "compat");
                syscalls.push((
                    name,
                    obj(vec![
                        ("native", native.map(|carrier| carrier_block(name, carrier)).unwrap_or(Node::Null)),
                        ("compat", compat.map(|carrier| compat_block(name, carrier)).unwrap_or(Node::Null)),
                    ]),
                ));
            }
            None => syscalls.push((name, Node::Null)),
        }
    }
    obj(vec![
        (
            "syscall",
            selected
                .map(|name| Node::Str(name.to_string()))
                .unwrap_or(Node::Null),
        ),
        ("carrier", carrier),
        (
            "anchor",
            match input.anchor {
                Some(anchor) => obj(vec![
                    ("image", hex(anchor.image)),
                    ("bytes", number_or_null(anchor.bytes.map(|value| value as i64))),
                    ("stride", number_or_null(anchor.stride.map(|value| value as i64))),
                    ("scratch", hex_or_null(anchor.scratch)),
                ]),
                None => Node::Null,
            },
        ),
        ("syscalls", obj(syscalls)),
        ("module", Node::Null),
        ("execution", Node::Null),
    ])
}

/// One carrier's branch body. `name` selects the block's keys, exactly as the
/// native parser expects them.
fn carrier_block(route: &str, carrier: &CarrierDoc<'_>) -> Node {
    let geometry = &carrier.geometry;
    let number = |key: &str| number_or_null(geometry.get(key));
    match route {
        "select_stack" => obj(vec![
            ("waiter_shift", number("waiter_shift")),
        ]),
        "sigreturn" => obj(vec![(
            "waiter_off",
            obj(vec![
                ("plain", number("waiter_off_plain")),
                ("sve", number("waiter_off_sve")),
            ]),
        )]),
        "multicast_waiter" => obj(vec![
            ("waiter_off", number("waiter_off")),
            ("buffer_size", number("buffer_size")),
        ]),
        _ => obj(Vec::new()),
    }
}

/// The compat variant's block. The compat geometry carries its own keys; the
/// `compat_*` spellings fall back to the 64-bit ones where the geometry does
/// not state them.
fn compat_block(route: &str, carrier: &CarrierDoc<'_>) -> Node {
    let geometry = &carrier.geometry;
    let number = |key: &str| number_or_null(geometry.get(key));
    let abi = match geometry.get("compat_futex_abi") {
        Some(1) => Node::Str("time64".to_string()),
        _ => Node::Str("time32".to_string()),
    };
    match route {
        "select_stack" => obj(vec![
            ("waiter_home", number("waiter_home")),
            ("dst_depth", number("select_dst_depth")),
            ("futex_abi", abi),
        ]),
        "multicast_waiter" => obj(vec![
            (
                "waiter_off",
                number_or_null(
                    geometry
                        .get("compat_waiter_off")
                        .or_else(|| geometry.get("waiter_off")),
                ),
            ),
            (
                "buffer_size",
                number_or_null(
                    geometry
                        .get("compat_buffer_size")
                        .or_else(|| geometry.get("buffer_size")),
                ),
            ),
            ("futex_abi", abi),
        ]),
        _ => obj(Vec::new()),
    }
}

/// Render a document tree as HOCON. Two-space indent; strings are quoted,
/// numbers bare; an empty block is `key {}`.
pub fn render_hocon(node: &Node) -> String {
    let Node::Obj(entries) = node else {
        return String::new();
    };
    let mut out = String::new();
    for (key, value) in entries {
        render_entry(&mut out, key, value, 0);
    }
    out
}

fn render_entry(out: &mut String, key: &str, value: &Node, indent: usize) {
    let pad = "  ".repeat(indent);
    match value {
        Node::Obj(entries) if entries.is_empty() => {
            out.push_str(&format!("{pad}{key} {{}}\n"));
        }
        Node::Obj(entries) => {
            out.push_str(&format!("{pad}{key} {{\n"));
            for (child, child_value) in entries {
                render_entry(out, child, child_value, indent + 1);
            }
            out.push_str(&format!("{pad}}}\n"));
        }
        Node::Null => out.push_str(&format!("{pad}{key} = null\n")),
        Node::Bool(value) => out.push_str(&format!("{pad}{key} = {value}\n")),
        Node::Int(value) => out.push_str(&format!("{pad}{key} = {value}\n")),
        Node::Str(value) => {
            let escaped = value.replace('\\', "\\\\").replace('"', "\\\"");
            out.push_str(&format!("{pad}{key} = \"{escaped}\"\n"));
        }
    }
}
