#!/usr/bin/env python3
"""Batch-verify ghostlock-extract across a set of kernel images.

Usage:
    python3 tools/verify_kernels.py <boot.img|payload.bin|Image>...

For each input it runs the release extractor (`--format conf`, the schema-v3
document) and prints one row per image: release, exit code, whether the CVE
primitive is present, the selected syscall, the multicast geometry it emitted
(waiter_off / waiter task+lock / compact), the physical load source, and any
missing sidecar-only device pair.

No device or root is involved: this is exactly the offline path a bootloader
locked user takes.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BIN = os.path.join(ROOT, "tools", "extract_rs", "target", "release", "ghostlock-extract")


def run(image):
    proc = subprocess.run(
        [BIN, image, "--format", "conf"],
        capture_output=True,
        text=True,
        timeout=1800,
    )
    return proc.returncode, proc.stdout, proc.stderr


def conf_fields(conf):
    """The v3 document flattened to dotted paths, plus the selected syscall."""
    fields = {}
    stack = []
    for line in conf.splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        if line.endswith("{"):
            stack.append(line[:-1].strip())
            continue
        if line == "}":
            if stack:
                stack.pop()
            continue
        if "=" in line:
            k, v = (p.strip() for p in line.split("=", 1))
            fields[".".join(stack + [k])] = v.strip('"')
    route = fields.get("ghostlock.syscall")
    if route in (None, "null"):
        route = None
    return route, fields


def numeric(value):
    if value is None or value == "null":
        return None
    try:
        return int(value, 0)
    except (TypeError, ValueError):
        return None


def classify(err):
    if "already fixed" in err or "rtmutex UAF fix is present" in err:
        return "fixed"
    if "primitive present" in err:
        return "vulnerable"
    return "?"


def waiter_off(err, fields):
    m = re.search(r"waiter_off derived = (0x[0-9a-f]+)", err)
    if m:
        return m.group(1) + " (derived)"
    for carrier in ("native", "compat"):
        value = numeric(
            fields.get(f"ghostlock.syscalls.multicast_waiter.{carrier}.waiter_off")
        )
        if value is not None:
            fallback = " (fallback)" if "waiter_off derivation failed" in err else ""
            return f"0x{value:x}{fallback}"
    return "-"


def phys(err, fields):
    loads = {
        key: value
        for key, value in fields.items()
        if key.startswith("devices.") and key.endswith(".phys.load")
    }
    if not loads:
        return "-"
    load = next(iter(loads.values()))
    if "(uefi memory map)" in err:
        return f"{load} (uefi)"
    if "(xbl_config FDT)" in err:
        return f"{load} (xbl)"
    if "(vendor_boot" in err:
        return f"{load} (vendor_boot)"
    if "(--phys" in err:
        return f"{load} (--phys)"
    return load


def main():
    if not os.path.isfile(BIN):
        sys.exit(f"missing {BIN}; run: cargo build --release --manifest-path tools/extract_rs/Cargo.toml")
    images = sys.argv[1:]
    if not images:
        sys.exit(__doc__)
    print(f"{'image':<34} {'release':<40} {'st':<4} {'prim':<10} {'syscall':<17} waiter_off          task/lock  phys            missing")
    for image in images:
        try:
            code, conf, err = run(image)
        except subprocess.TimeoutExpired:
            print(f"{os.path.basename(image):<34} TIMEOUT")
            continue
        route, fields = conf_fields(conf)
        rel = fields.get("release", "-")
        prim = classify(err)
        wo = waiter_off(err, fields)
        task = numeric(fields.get("kernel.struct.waiter.task"))
        lock = numeric(fields.get("kernel.struct.waiter.lock"))
        tl = f"{task}/{lock}" if task is not None and lock is not None else "-"
        ph = phys(err, fields)
        missing = "-" if any(
            key.startswith("devices.") and key.endswith(".phys.load")
            for key in fields
        ) else "devices.<id>.phys.load"
        print(
            f"{os.path.basename(image):<34} {rel:<40} {code:<4} {prim:<10} "
            f"{(route or '-'):<17} {wo:<19} {tl:<10} {ph:<15} {missing}"
        )


if __name__ == "__main__":
    main()
