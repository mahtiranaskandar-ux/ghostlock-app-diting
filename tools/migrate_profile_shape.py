#!/usr/bin/env python3
"""HOCON parser/renderer for the profile tools, plus the retired one-off converter.

The shipping path is the extractor and the store (`tools/extract_rs`,
`tools/sweep_timer_store.py`, `tools/ship_golden_profiles.py`); they use this
module's `Parser`/`render` only. The conversion half (`convert` and its CLI)
is the 2026-11-24 one-off that moved the bundled assets from the authored
schema to v3; it is kept as the migration record and is no longer called.

Usage:
    python3 tools/migrate_profile_shape.py <profilesDir> <outDir>
    python3 tools/migrate_profile_shape.py <profilesDir> <outDir> --check

--check converts in memory and compares with the documents already in
<outDir>, reporting differences instead of writing (exit 1 on any).

The mapping is the authored half of the transport conversion: symbols become
image-relative hex, image_base is recovered from the one symbol the old
document states twice (the timer's selinux_state VA and the ghostlock
selinux_enforcing offset), the timer's derived credential overlay fills the
shared `kernel.struct.cred`, and the credential references are named by their
derived offsets.
"""

import os
import re
import sys

SCHEMA_VERSION = 3
MATCH_BITS = 21
MASK21 = (1 << MATCH_BITS) - 1
BASE_ALIGNMENT = 0x200000
SYSCALLS = ("select_stack", "sigreturn", "multicast_waiter", "tcp_zerocopy")
SKIP_FILES = {"index.conf", "device_defaults.conf"}
SKIP_PREFIXES = ("execution-",)


# ---------------------------------------------------------------------------
# HOCON subset parser (comments, includes expanded by the caller, unquoted
# keys, quoted strings, integers, objects, arrays, null/true/false).
# ---------------------------------------------------------------------------

class ParseError(Exception):
    pass


class Parser:
    def __init__(self, text, source):
        self.text = text
        self.i = 0
        self.n = len(text)
        self.source = source

    def error(self, message):
        line = self.text.count("\n", 0, self.i) + 1
        raise ParseError(f"{self.source}:{line}: {message}")

    def skip(self):
        while self.i < self.n:
            c = self.text[self.i]
            if c in " \t\r\n,":
                self.i += 1
            elif c == "#" or (c == "/" and self.i + 1 < self.n and self.text[self.i + 1] == "/"):
                while self.i < self.n and self.text[self.i] != "\n":
                    self.i += 1
            elif c == "/" and self.i + 1 < self.n and self.text[self.i + 1] == "*":
                end = self.text.find("*/", self.i + 2)
                if end < 0:
                    self.error("unterminated block comment")
                self.i = end + 2
            else:
                break

    def peek(self):
        self.skip()
        return self.text[self.i] if self.i < self.n else ""

    def take(self, expected=None):
        c = self.peek()
        if expected and c != expected:
            self.error(f"expected {expected!r}, found {c!r}")
        self.i += 1
        return c

    def bare(self):
        start = self.i
        while self.i < self.n and (self.text[self.i].isalnum() or self.text[self.i] in "_.-"):
            self.i += 1
        if start == self.i:
            self.error("expected a key")
        return self.text[start:self.i]

    def key(self):
        if self.peek() == '"':
            return self.string()
        return self.bare()

    def string(self):
        self.take('"')
        out = []
        while self.i < self.n:
            c = self.text[self.i]
            self.i += 1
            if c == '"':
                return "".join(out)
            if c == "\\":
                if self.i >= self.n:
                    self.error("truncated escape")
                e = self.text[self.i]
                self.i += 1
                out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}.get(e, e))
            else:
                out.append(c)
        self.error("unterminated string")

    def value(self):
        c = self.peek()
        if c == "{":
            return self.obj()
        if c == "[":
            return self.array()
        if c == '"':
            return self.string()
        word = self.bare()
        if word == "true":
            return True
        if word == "false":
            return False
        if word == "null":
            return None
        try:
            return int(word, 10)
        except ValueError:
            self.error(f"unsupported bare value {word!r}")

    def obj(self):
        self.take("{")
        out = {}
        while True:
            c = self.peek()
            if c == "}":
                self.take("}")
                return out
            if c == "":
                self.error("unterminated object")
            key = self.key()
            if self.peek() == "=":
                self.take("=")
                merge_into(out, key, self.value())
            elif self.peek() == "{":
                merge_into(out, key, self.obj())
            elif self.peek() == ":":
                self.take(":")
                merge_into(out, key, self.value())
            else:
                self.error(f"expected '=' or '{{' after {key!r}")

    def array(self):
        self.take("[")
        out = []
        while True:
            c = self.peek()
            if c == "]":
                self.take("]")
                return out
            if c == "":
                self.error("unterminated array")
            out.append(self.value())

    def document(self):
        out = {}
        while True:
            c = self.peek()
            if c == "":
                return out
            key = self.key()
            if self.peek() == "=":
                self.take("=")
                merge_into(out, key, self.value())
            elif self.peek() == "{":
                merge_into(out, key, self.obj())
            else:
                self.error(f"expected '=' or '{{' after {key!r}")


def merge_into(target, key, value):
    """HOCON object merge: duplicate object keys merge, scalars replace."""
    if key in target and isinstance(target[key], dict) and isinstance(value, dict):
        for sub_key, sub_value in value.items():
            merge_into(target[key], sub_key, sub_value)
    else:
        target[key] = value


def expand_includes(path, base_dir, visiting=None):
    visiting = visiting or set()
    real = os.path.abspath(path)
    if real in visiting:
        return ""
    visiting.add(real)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
    except OSError:
        visiting.discard(real)
        return None
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("include "):
            target = stripped[len("include "):].strip().strip('"')
            if target:
                included = expand_includes(os.path.join(base_dir, target), base_dir, visiting)
                lines.append(included or "")
                continue
        lines.append(line)
    visiting.discard(real)
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def literal(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def render_value(out, value, indent):
    if isinstance(value, dict):
        out.append("{\n")
        for key, item in value.items():
            out.append("  " * (indent + 1) + key + " ")
            if isinstance(item, dict):
                render_value(out, item, indent + 1)
                out.append("\n")
            elif isinstance(item, list):
                out.append("= ")
                render_list(out, item, indent + 1)
                out.append("\n")
            else:
                out.append("= " + literal(item) + "\n")
        out.append("  " * indent + "}")
    elif isinstance(value, list):
        render_list(out, value, indent)
    else:
        out.append(literal(value))


def render_list(out, value, indent):
    if not value:
        out.append("[]")
        return
    if all(not isinstance(item, (dict, list)) for item in value):
        out.append("[ " + ", ".join(literal(item) for item in value) + " ]")
        return
    out.append("[\n")
    for item in value:
        out.append("  " * (indent + 1))
        render_value(out, item, indent + 1)
        out.append(",\n")
    out.append("  " * indent + "]")


def render(document):
    out = []
    for key, value in document.items():
        if isinstance(value, dict):
            out.append(key + " ")
            render_value(out, value, 0)
            out.append("\n")
        elif isinstance(value, list):
            out.append(key + " = ")
            render_list(out, value, 0)
            out.append("\n")
        else:
            out.append(key + " = " + literal(value) + "\n")
    return "".join(out)


# ---------------------------------------------------------------------------
# Conversion
# ---------------------------------------------------------------------------

def as_map(value):
    return value if isinstance(value, dict) else None


def num(mapping, key):
    value = (mapping or {}).get(key)
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def unsigned(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value & 0xFFFFFFFFFFFFFFFF
    if isinstance(value, str):
        text = value.strip()
        if text.lower().startswith("0x"):
            try:
                return int(text, 16)
            except ValueError:
                return None
    return None


def hex_string(value):
    return None if value is None else "0x" + format(value & 0xFFFFFFFFFFFFFFFF, "x")


def branches(document):
    out = {}
    route = as_map(document.get("route"))
    if route:
        for name, value in route.items():
            if isinstance(value, dict):
                out[name] = value
    fallback = as_map(document.get("fallback"))
    target = (fallback or {}).get("to")
    if isinstance(target, str) and target not in ("", "none") and target not in out:
        body = as_map(as_map((fallback or {}).get("route")) or {}).get(target)
        if isinstance(body, dict):
            out[target] = body
    return out


def image_base(document):
    timer = as_map(document.get("timer_98260")) or {}
    va = unsigned(timer.get("selinux_state"))
    offset = num(document.get("offset") or {}, "selinux_enforcing")
    if va is None or offset is None or va <= offset:
        return None
    base = va - offset
    if base % BASE_ALIGNMENT != 0 or base < 0x80000000:
        return None
    return base


def relative(timer, key, base):
    va = unsigned(timer.get(key))
    if va is None or base is None or va < base:
        return None
    return va - base


def carrier_flags(branch):
    carriers = branch.get("carriers")
    if isinstance(carriers, list):
        names = [str(name).lower() for name in carriers if isinstance(name, str)]
        return "native" in names, "compat" in names
    compat = num(branch, "compat_carrier") == 1
    return (not compat), compat


def futex_abi(branch):
    return "time64" if num(branch, "compat_futex_abi") == 1 else "time32"


def branch_blocks(name, branch):
    native, compat = carrier_flags(branch)
    if name == "select_stack":
        return {
            "native": {"waiter_shift": num(branch, "waiter_shift")} if native else None,
            "compat": {
                "waiter_home": num(branch, "waiter_home"),
                "dst_depth": num(branch, "select_dst_depth"),
                "futex_abi": futex_abi(branch),
            } if compat else None,
        }
    if name == "multicast_waiter":
        return {
            "native": {
                "waiter_off": num(branch, "waiter_off"),
                "buffer_size": num(branch, "buffer_size"),
            } if native else None,
            "compat": {
                "waiter_off": num(branch, "compat_waiter_off"),
                "buffer_size": num(branch, "compat_buffer_size"),
                "futex_abi": futex_abi(branch),
            } if compat else None,
        }
    if name == "sigreturn":
        return {
            "native": {
                "waiter_off": {
                    "plain": num(branch, "waiter_off_plain"),
                    "sve": num(branch, "waiter_off_sve"),
                },
            },
            "compat": None,
        }
    return {"native": {}, "compat": None}


def carrier_choice(branch):
    if branch is None:
        return None
    native, compat = carrier_flags(branch)
    if compat and not native:
        return "compat"
    if native and not compat:
        return None
    return "native"


def selected_carrier(branch):
    return carrier_choice(branch)


def named_refs(document):
    cred = as_map(document.get("cred")) or {}
    timer = as_map(document.get("timer_98260")) or {}
    overlay = {
        "user": num(timer, "cred_user_offset"),
        "user_ns": num(timer, "cred_user_ns_offset"),
        "ucounts": num(timer, "cred_ucounts_offset"),
        "group_info": num(timer, "cred_group_info_offset"),
    }
    overlay = {k: v for k, v in overlay.items() if v}
    positional = ["user", "user_ns", "ucounts", "group_info"]
    slots = [
        ("ref0_offset", "ref0_image"),
        ("ref1_offset", "ref1_image"),
        ("ref2_offset", "ref2_image"),
        ("ref3_offset", "ref3_image"),
    ]
    refs = {}
    for index, (offset_key, image_key) in enumerate(slots):
        offset = num(cred, offset_key)
        offset = offset if offset else None
        image = cred.get(image_key)
        image = image if isinstance(image, int) and not isinstance(image, bool) and image != 0 else None
        if offset is None and image is None:
            continue
        name = next((key for key, value in overlay.items() if value == offset), None)
        if name is None:
            name = positional[index]
        refs[name] = {"offset": offset, "image": hex_string(image)}
    for name, offset in overlay.items():
        if name not in refs:
            refs[name] = {"offset": offset, "image": None}
    return refs


def credential(document):
    cred = as_map(document.get("cred")) or {}
    timer = as_map(document.get("timer_98260")) or {}

    def overlay(key):
        value = num(timer, key)
        return value if value else None

    refs = named_refs(document)
    return {
        "copy_size": num(cred, "copy_size"),
        "usage": {
            "offset": num(cred, "usage_offset"),
            "size": overlay("cred_usage_size") or num(cred, "usage_size"),
            "value": num(cred, "usage_value"),
        },
        "uid": overlay("cred_uid_offset") or num(cred, "uid_offset"),
        "security": overlay("cred_security_offset") or num(cred, "security_offset"),
        "security_sid": overlay("security_sid_offset"),
        "caps": {
            "offset": overlay("cred_caps_offset") or num(cred, "caps_offset"),
            "count": overlay("cred_caps_count") or num(cred, "caps_count"),
            "mask": hex_string(cred.get("caps_value")),
        },
        "refs": {
            name: refs.get(name, {"offset": None, "image": None})
            for name in ("user", "user_ns", "ucounts", "group_info")
        },
    }


def kernel_block(document, base, selected_branch):
    offset = as_map(document.get("offset")) or {}
    timer = as_map(document.get("timer_98260")) or {}
    task = as_map(document.get("task_struct")) or {}
    layout = as_map(document.get("waiter_layout"))
    snitch = as_map(document.get("kernelsnitch")) or {}
    selinux = as_map(document.get("selinux")) or {}
    pipeline = document.get("pipeline")

    def number(mapping, key):
        return num(mapping, key)

    symbols = {
        "init_task": hex_string(number(offset, "init_task")),
        "init_cred": hex_string(number(offset, "init_cred")),
        "root_task_group": hex_string(number(offset, "root_task_group")),
        "empty_zero_page": hex_string(number(offset, "empty_zero_page")),
        "selinux_state": hex_string(number(offset, "selinux_enforcing")),
        "selinux_blob_sizes": hex_string(number(offset, "selinux_blob_sizes")),
        "security_hook_heads": hex_string(number(offset, "security_hook_heads")),
        "nf_logger": hex_string(number(offset, "slide_nfulnl_logger")),
        "loggers_0_1": hex_string(number(offset, "slide_loggers_0_1")),
        "boot_id": hex_string(number(offset, "slide_boot_id")),
        "panic_on_warn": hex_string(relative(timer, "panic_on_warn", base)),
        "root_user": hex_string(relative(timer, "root_user", base)),
        "init_user_ns": hex_string(relative(timer, "init_user_ns", base)),
        "init_ucounts": hex_string(relative(timer, "init_ucounts", base)),
        "init_groups": hex_string(relative(timer, "init_groups", base)),
    }
    shape_value = number(layout or {}, "compact")
    if shape_value is None and selected_branch is not None:
        shape_value = number(selected_branch, "compact_waiter")
    shape = {1: "compact", 0: "node"}.get(shape_value)
    task_offset = number(layout or {}, "task")
    if task_offset is None and selected_branch is not None:
        task_offset = number(selected_branch, "task_offset")
    lock_offset = number(layout or {}, "lock")
    if lock_offset is None and selected_branch is not None:
        lock_offset = number(selected_branch, "lock_offset")
    structure = {
        "task": {
            key: number(task, key)
            for key in (
                "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters",
                "pi_top_task", "pi_blocked_on", "pid", "tgid", "atomic_flags",
                "real_cred", "cred", "comm", "tasks", "seccomp",
            )
        },
        "cred": credential(document),
        "waiter": {
            "shape": shape,
            "pi_tree": number(layout or {}, "pi_tree"),
            "task": task_offset,
            "lock": lock_offset,
            "wake_state": number(layout or {}, "wake_state"),
        },
        "rt_mutex": {"size": number(snitch, "rt_mutex_size")},
        "selinux_state": {
            "enforcing": number(selinux, "enforcing"),
            "initialized": number(selinux, "initialized"),
        },
    }
    order = pipeline if isinstance(pipeline, list) else (
        [pipeline] if isinstance(pipeline, str) else []
    )
    dirtyfrag = "feasible" if (
        num(document, "kdp") == 1 or "dirtyfrag" in order
    ) else "unknown"
    return {
        "image_base": hex_string(base),
        "symbols": symbols,
        "struct": structure,
        "heap": {
            "stride": number(snitch, "mm_struct_sz"),
            "collisions": number(snitch, "collisions"),
        },
        "dirtyfrag": dirtyfrag,
        "kdp": num(document, "kdp") == 1,
    }


def ghostlock_block(document, all_branches, selected):
    module = as_map(document.get("module"))
    if not all_branches and module is None:
        return None
    syscalls = {name: (branch_blocks(name, all_branches[name]) if name in all_branches else None)
                for name in SYSCALLS}
    anchor = None
    if selected is not None:
        branch = all_branches[selected]
        image = hex_string(num(branch, "lock_anchor_image") if num(branch, "lock_anchor_image") else None)
        size = num(branch, "lock_anchor_bytes")
        stride = num(branch, "lock_anchor_stride")
        scratch = hex_string(num(document.get("w1_scratch") or {}, "image"))
        if image is not None or size is not None or stride is not None or scratch is not None:
            anchor = {"image": image, "bytes": size, "stride": stride, "scratch": scratch}
    return {
        "syscall": selected,
        "carrier": carrier_choice(all_branches.get(selected)) if selected else None,
        "anchor": anchor,
        "syscalls": syscalls if all_branches else None,
        "module": {
            "asset": module.get("asset"),
            "sha256": module.get("asset_sha256"),
            "vendor_files": module.get("vendor_files"),
        } if module else None,
        "execution": document.get("execution"),
    }


def timer_block(document, base):
    timer = as_map(document.get("timer_98260"))
    if timer is None:
        return None
    site_va = unsigned(timer.get("static_timer_site"))
    if site_va is None:
        site_offset = None
    elif base is not None and site_va >= base:
        site_offset = site_va - base
    else:
        site_offset = site_va
    low = num(timer, "task_alloc_site_lower_21bit")
    trigger = timer.get("trigger")
    if trigger not in ("killable", "core_state"):
        trigger = "killable"
    return {
        "trigger": trigger,
        "match_bits": MATCH_BITS,
        "sites": {
            "timer": {"offset": hex_string(site_offset)},
            "task_alloc": {
                "low_bits": hex_string(low & MASK21 if low is not None else None),
            },
        },
        "object_size": num(timer, "timer_object_size"),
        "task_alloc_size": num(timer, "task_alloc_size"),
        "node_offset": num(timer, "timer_node_offset"),
        "cred": {
            "request_size": num(timer, "cred_request_size"),
            "alloc_size": num(timer, "cred_alloc_size"),
        },
        "trace": {
            "gfp": {
                "offset": num(timer, "page_alloc_gfp_offset"),
                "size": num(timer, "page_alloc_gfp_size"),
            },
        },
    }


def devices_block(document):
    load = as_map(document.get("kernel_phys_load"))
    offset = as_map(document.get("kernel_phys_offset"))
    if load is None and offset is None:
        return None
    ids = []
    for mapping in (load, offset):
        for key in (mapping or {}):
            if key not in ids:
                ids.append(key)
    out = {}
    for device in ids:
        def entry(mapping):
            if not mapping:
                return None
            for key, value in mapping.items():
                if key.lower() == device.lower():
                    return hex_string(value & 0xFFFFFFFFFFFFFFFF if isinstance(value, int) else None)
            return None
        out[device] = {"phys": {"load": entry(load), "offset": entry(offset)}}
    return out


def convert(document):
    """The cleaned document (idempotent: a cleaned input is returned as is)."""
    if isinstance(document.get("kernel"), dict):
        return document
    base = image_base(document)
    all_branches = branches(document)
    selected = min((name for name in SYSCALLS if name in all_branches), default=None)
    return {
        "schema_version": SCHEMA_VERSION,
        "release": document.get("release"),
        "devices": devices_block(document),
        "kernel": kernel_block(document, base, all_branches.get(selected) if selected else None),
        "ghostlock": ghostlock_block(document, all_branches, selected),
        "timer": timer_block(document, base),
    }


# ---------------------------------------------------------------------------
# Verification and CLI
# ---------------------------------------------------------------------------

def equivalent(left, right):
    if isinstance(left, dict) and isinstance(right, dict):
        if {str(k) for k in left} != {str(k) for k in right}:
            return False
        return all(equivalent(left[k], next(v for kk, v in right.items() if str(kk) == str(k))) for k in left)
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(equivalent(a, b) for a, b in zip(left, right))
    if isinstance(left, bool) or isinstance(right, bool):
        return left is right
    if isinstance(left, int) and isinstance(right, int):
        return left == right
    return left == right


def main(argv):
    if len(argv) < 3:
        print(__doc__.strip())
        return 2
    source_dir = os.path.abspath(argv[1])
    out_dir = os.path.abspath(argv[2])
    check = "--check" in argv[3:]
    if not os.path.isdir(source_dir):
        print(f"profiles dir does not exist: {source_dir}")
        return 2
    os.makedirs(out_dir, exist_ok=True)
    converted = skipped = 0
    failures = []
    for name in sorted(os.listdir(source_dir)):
        if not name.endswith(".conf") or name in SKIP_FILES or name.startswith(SKIP_PREFIXES):
            skipped += 1
            continue
        path = os.path.join(source_dir, name)
        if not os.path.isfile(path):
            skipped += 1
            continue
        text = expand_includes(path, source_dir)
        if text is None:
            skipped += 1
            continue
        try:
            parsed = Parser(text, name).document()
        except ParseError as error:
            failures.append(f"{name}: {error}")
            continue
        if not isinstance(parsed.get("release"), str):
            skipped += 1  # include fragments are not documents
            continue
        document = convert(parsed)
        rendered = render(document)
        try:
            back = Parser(rendered, name).document()
        except ParseError as error:
            failures.append(f"{name}: rendered output does not parse: {error}")
            continue
        if not equivalent(back, document):
            failures.append(f"{name}: render/parse round trip differs")
            continue
        target = os.path.join(out_dir, name)
        if check:
            if not os.path.isfile(target):
                failures.append(f"{name}: no converted document to compare")
                continue
            existing = Parser(open(target, encoding="utf-8").read(), target).document()
            if not equivalent(existing, document):
                failures.append(f"{name}: converted document differs")
                continue
        else:
            with open(target, "w", encoding="utf-8") as handle:
                handle.write(rendered.rstrip("\n") + "\n")
        converted += 1
    for failure in failures:
        print("FAIL " + failure)
    print(f"migrated {converted} document(s), skipped {skipped}, failures {len(failures)} -> {out_dir}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
