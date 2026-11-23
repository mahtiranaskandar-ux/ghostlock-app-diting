//! /proc/kallsyms text parsing.

use std::collections::{BTreeMap, BTreeSet};

use regex::Regex;

use crate::error::{ExtractError, Result};

#[derive(Debug, Default)]
pub struct Kallsyms {
    pub symbols: BTreeMap<String, BTreeSet<u64>>,
    pub types: BTreeMap<String, BTreeSet<char>>,
}

pub fn parse(text: &str) -> Result<Kallsyms> {
    let pattern = Regex::new(r"^([0-9a-fA-F]{8,16})\s+(\S)\s+(.+?)\s*$").unwrap();
    let mut symbols: BTreeMap<String, BTreeSet<u64>> = BTreeMap::new();
    let mut types: BTreeMap<String, BTreeSet<char>> = BTreeMap::new();
    for line in text.lines() {
        let Some(captures) = pattern.captures(line) else {
            continue;
        };
        let Ok(value) = u64::from_str_radix(&captures[1], 16) else {
            continue;
        };
        if value == 0 {
            continue;
        }
        let symbol_type = captures[2].chars().next().unwrap();
        let name = captures[3].to_string();
        symbols.entry(name.clone()).or_default().insert(value);
        types.entry(name).or_default().insert(symbol_type);
    }
    if !symbols.contains_key("_text") && !symbols.contains_key("_head") {
        return Err(ExtractError::new("kallsyms has no _text or _head symbol"));
    }
    Ok(Kallsyms { symbols, types })
}

pub fn unique(symbols: &BTreeMap<String, BTreeSet<u64>>, name: &str) -> Option<u64> {
    let values = symbols.get(name)?;
    if values.len() == 1 {
        values.iter().next().copied()
    } else {
        None
    }
}

pub fn unique_or_err(symbols: &BTreeMap<String, BTreeSet<u64>>, name: &str) -> Result<u64> {
    let values = symbols.get(name).cloned().unwrap_or_default();
    if values.len() != 1 {
        let hex: Vec<String> = values.iter().map(|v| format!("{v:#x}")).collect();
        return Err(ExtractError::new(format!(
            "kallsyms symbol {name:?} not unique: {hex:?}"
        )));
    }
    Ok(*values.iter().next().unwrap())
}

pub fn unique_offset_optional(
    symbols: &BTreeMap<String, BTreeSet<u64>>,
    name: &str,
) -> Option<u64> {
    unique_or_err(symbols, name).ok()
}

/// An LTO build internalizes a static function or datum under a suffixed
/// name: clang's `<name>.llvm.<hash>`, and the vendor 5.4 builds'
/// `<name>$<32-hex>` (measured on both 5.4.233 and 5.4.274: `esp_input` and
/// `esp6_input` exist only in that form). The suffix makes the name
/// unrecognizable to an exact lookup, so a caller that only needs "the one
/// function with this name" can accept a lone clone.
///
/// The CFI jump-table thunks (`<name>$<hash>.cfi_jt`) are different functions
/// and never count. `$` suffixes must be pure hex: a name that merely starts
/// with `<name>$` is somebody else's symbol.
fn is_lto_clone_of(symbol: &str, name: &str) -> bool {
    let Some(suffix) = symbol.strip_prefix(name) else {
        return false;
    };
    if let Some(rest) = suffix.strip_prefix(".llvm.") {
        return !rest.is_empty() && rest.chars().all(|c| c.is_ascii_alphanumeric());
    }
    let Some(rest) = suffix.strip_prefix('$') else {
        return false;
    };
    rest.len() >= 16 && rest.chars().all(|c| c.is_ascii_hexdigit())
}

/// Resolve a symbol by exact name, falling back to exactly one LTO clone.
/// An exact name always wins, even when clones exist. Two or more clones, or
/// a clone name carrying several addresses, fail closed — an ambiguous match
/// is not evidence about the function the caller asked for.
pub fn unique_with_lto_clones(
    symbols: &BTreeMap<String, BTreeSet<u64>>,
    name: &str,
) -> Result<u64> {
    if symbols.contains_key(name) {
        return unique_or_err(symbols, name);
    }
    let clones: Vec<&String> = symbols
        .keys()
        .filter(|symbol| is_lto_clone_of(symbol, name))
        .collect();
    let [clone] = clones.as_slice() else {
        let names: Vec<&str> = clones.iter().map(|s| s.as_str()).collect();
        return Err(ExtractError::new(format!(
            "kallsyms symbol {name:?} not found and its LTO clones are not \
             unique: {names:?}"
        )));
    };
    unique_or_err(symbols, clone)
}

pub fn find_data_symbol(
    symbols: &BTreeMap<String, BTreeSet<u64>>,
    types: &BTreeMap<String, BTreeSet<char>>,
    exact: &str,
    fragments: &[&str],
) -> Option<u64> {
    if let Some(address) = unique(symbols, exact) {
        return Some(address);
    }
    let mut matches: BTreeSet<u64> = BTreeSet::new();
    for (name, values) in symbols {
        if fragments.is_empty() {
            continue;
        }
        if !fragments.iter().all(|fragment| {
            name.to_ascii_lowercase()
                .contains(&fragment.to_ascii_lowercase())
        }) {
            continue;
        }
        let is_data = types
            .get(name)
            .map(|set| set.iter().any(|c| "dDbB".contains(*c)))
            .unwrap_or(false);
        if !is_data {
            continue;
        }
        matches.extend(values.iter().copied());
    }
    if matches.len() == 1 {
        matches.iter().next().copied()
    } else {
        None
    }
}

pub fn find_function(
    symbols: &BTreeMap<String, BTreeSet<u64>>,
    exact: &str,
    fragments: &[&str],
) -> Option<u64> {
    if let Some(address) = unique(symbols, exact) {
        return Some(address);
    }
    let mut matches: BTreeSet<u64> = BTreeSet::new();
    for (name, values) in symbols {
        if fragments.iter().all(|fragment| {
            name.to_ascii_lowercase()
                .contains(&fragment.to_ascii_lowercase())
        }) {
            matches.extend(values.iter().copied());
        }
    }
    if matches.len() == 1 {
        matches.iter().next().copied()
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::unique_with_lto_clones;
    use std::collections::{BTreeMap, BTreeSet};

    fn table(entries: &[(&str, u64)]) -> BTreeMap<String, BTreeSet<u64>> {
        let mut symbols: BTreeMap<String, BTreeSet<u64>> = BTreeMap::new();
        for (name, value) in entries {
            symbols
                .entry((*name).to_string())
                .or_default()
                .insert(*value);
        }
        symbols
    }

    const HASH: &str = "ee9972e53b2a47f73fa94ea47f11d354";

    fn clone_name(name: &str) -> String {
        format!("{name}${HASH}")
    }

    #[test]
    fn a_lone_vendor_clone_resolves() {
        /* The measured 5.4 shape: no exact name, one `$<32-hex>` clone and
         * its CFI jump-table thunk. */
        let symbols = table(&[
            (&clone_name("esp_input"), 0x11d2568),
            (&format!("{}.cfi_jt", clone_name("esp_input")), 0x11d2578),
            ("esp_input_done2", 0x1113263c),
        ]);
        assert_eq!(
            unique_with_lto_clones(&symbols, "esp_input").ok(),
            Some(0x11d2568)
        );
    }

    #[test]
    fn an_exact_name_wins_over_clones() {
        let symbols = table(&[("esp_input", 0x1000), (&clone_name("esp_input"), 0x2000)]);
        assert_eq!(
            unique_with_lto_clones(&symbols, "esp_input").ok(),
            Some(0x1000)
        );
    }

    #[test]
    fn an_llvm_clone_still_resolves() {
        let symbols = table(&[("serial8250_ports.llvm.12345", 0x1000)]);
        assert_eq!(
            unique_with_lto_clones(&symbols, "serial8250_ports").ok(),
            Some(0x1000)
        );
    }

    #[test]
    fn two_clones_fail_closed() {
        let symbols = table(&[("esp_input.llvm.1", 0x1000), ("esp_input.llvm.2", 0x2000)]);
        assert!(unique_with_lto_clones(&symbols, "esp_input").is_err());
    }

    #[test]
    fn a_cfi_thunk_alone_is_not_the_function() {
        let symbols = table(&[("esp_input.cfi_jt", 0x1000)]);
        assert!(unique_with_lto_clones(&symbols, "esp_input").is_err());
    }

    #[test]
    fn a_non_hex_dollar_suffix_is_someone_else() {
        let symbols = table(&[("esp_input$constprop", 0x1000)]);
        assert!(unique_with_lto_clones(&symbols, "esp_input").is_err());
    }

    #[test]
    fn an_ambiguous_exact_name_does_not_fall_back() {
        let mut symbols = table(&[(&clone_name("esp_input"), 0x2000)]);
        symbols
            .entry("esp_input".to_string())
            .or_default()
            .extend([0x1000, 0x1800]);
        assert!(unique_with_lto_clones(&symbols, "esp_input").is_err());
    }
}
