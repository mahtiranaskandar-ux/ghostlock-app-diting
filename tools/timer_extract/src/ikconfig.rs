// SPDX-License-Identifier: Apache-2.0

//! The kernel's embedded `.config` (`IKCFG_ST` … `IKCFG_ED`, a gzip stream).
//!
//! The image's own config is a per-image fact: it decides whether the exploit
//! can run at all (POSIX timers, tracepoints, tracing, SLUB) without trusting
//! the device or a family guess. A kernel built without `CONFIG_IKCONFIG`
//! carries no blob; the runtime `/proc/config.gz` is the fallback there.

use std::collections::BTreeMap;
use std::io::Read;

use flate2::read::GzDecoder;

const START: &[u8; 8] = b"IKCFG_ST";
const END: &[u8; 8] = b"IKCFG_ED";

/// Raw gzip blob between the two markers.
pub fn embedded_blob(kernel: &[u8]) -> Option<&[u8]> {
    let start = find(kernel, START, 0)? + START.len();
    let end = find(kernel, END, start)?;
    Some(&kernel[start..end])
}

fn find(haystack: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    if from >= haystack.len() || needle.is_empty() {
        return None;
    }
    haystack[from..]
        .windows(needle.len())
        .position(|window| window == needle)
        .map(|offset| from + offset)
}

/// Decompress and parse the embedded config, if the image carries one.
pub fn embedded_config(kernel: &[u8]) -> Option<BTreeMap<String, String>> {
    let blob = embedded_blob(kernel)?;
    let mut text = String::new();
    GzDecoder::new(blob).read_to_string(&mut text).ok()?;
    let config = parse(&text);
    if config.is_empty() { None } else { Some(config) }
}

/// Parse `/proc/config.gz` text or the embedded blob's text form.
pub fn parse(text: &str) -> BTreeMap<String, String> {
    let mut out = BTreeMap::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Some(symbol) = line
            .strip_prefix("# ")
            .and_then(|rest| rest.strip_suffix(" is not set"))
        {
            out.insert(symbol.trim().to_string(), "n".to_string());
            continue;
        }
        if line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            out.insert(key.trim().to_string(), value.trim().trim_matches('"').to_string());
        }
    }
    out
}

/// The config symbols the timer route needs, with the value required.
pub const REQUIRED_CONFIG: &[(&str, &str)] = &[
    ("CONFIG_POSIX_TIMERS", "y"),
    ("CONFIG_TRACEPOINTS", "y"),
    ("CONFIG_FTRACE", "y"),
    ("CONFIG_TRACING", "y"),
    ("CONFIG_SLUB", "y"),
];

/// Config facts that matter but whose absence is not fatal.
pub const INTERESTING_CONFIG: &[&str] = &[
    "CONFIG_KALLSYMS",
    "CONFIG_DEBUG_INFO_BTF",
    "CONFIG_POSIX_CPU_TIMERS",
    "CONFIG_SECURITY_SELINUX",
    "CONFIG_SECURITY",
    "CONFIG_PREEMPT",
    "CONFIG_PREEMPT_NONE",
    "CONFIG_HZ",
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_handles_set_and_unset() {
        let text = "\
# Automatically generated file
CONFIG_POSIX_TIMERS=y
CONFIG_TRACING=y
# CONFIG_DEBUG_INFO_BTF is not set
CONFIG_LOCALVERSION=\"-gki\"
";
        let config = parse(text);
        assert_eq!(config["CONFIG_POSIX_TIMERS"], "y");
        assert_eq!(config["CONFIG_DEBUG_INFO_BTF"], "n");
        assert_eq!(config["CONFIG_LOCALVERSION"], "-gki");
        assert!(!config.contains_key("Automatically generated file"));
    }

    #[test]
    fn markers_gate_the_blob() {
        let mut kernel = vec![0u8; 64];
        kernel.extend_from_slice(START);
        kernel.extend_from_slice(&[0x1f, 0x8b, 0x08, 0x00]);
        kernel.extend_from_slice(END);
        assert_eq!(embedded_blob(&kernel), Some(&[0x1f, 0x8b, 0x08, 0x00][..]));
        assert!(embedded_blob(&kernel[..16]).is_none());
    }
}
