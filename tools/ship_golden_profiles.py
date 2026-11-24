#!/usr/bin/env python3
"""Ship every golden profile from the test-kernels store into the app bundle.

Rule (CLAUDE.md): every store entry that has an option superset and at least
one feasible route is shipped as a bundled profile. Entries with
``corpus = btf-only | codegen | excluded``, entries without a superset, entries
without a feasible route, and a hand-finished asset whose store entry cannot
produce a complete document are exempt.

The bundle asset is the store entry's cleaned ``document`` — the v3 profile
tree the extractor rendered, with every device pair already in
``document.devices`` — with the store-only composition applied: the measured
``kernel.heap.stride`` and the timer facts the store sweep already merged.
Nothing is converted and the extractor is not re-run: the store is the single
golden.

The document itself selects its syscall (the extractor's pick) and states the
fixed ladder through ``kernel.dirtyfrag`` / ``kernel.kdp`` / ``ghostlock.module``
/ ``timer``; there is no pipeline line and no route fallback. New assets carry
a SHIPPED GOLDEN header; ``--refresh-all`` rewrites every store-derived asset,
and a hand-finished asset (no store entry) is never touched.

Usage:
    tools/ship_golden_profiles.py --check        # report unbundled goldens, exit 1
    tools/ship_golden_profiles.py --apply        # render + index the missing ones
    tools/ship_golden_profiles.py --refresh-all  # rewrite every store-derived asset

Paths default to this checkout; override with --store/--assets.
"""
import argparse
import copy
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import migrate_profile_shape

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(os.path.dirname(APP))  # <repo>/external/ghostlock-app -> <repo>
DEFAULT_STORE = os.path.join(ROOT, "external", "test-kernels")
DEFAULT_ASSETS = os.path.join(APP, "app", "src", "main", "assets", "kernel_profiles")

SKIP_CORPUS = {"btf-only", "codegen", "excluded"}
# The AArch32 compat select carrier's route block and geometry are the
# extractor's own now (analysis::apply_compat_select + report::
# conf_compat_route_geometry): a 5.10 pre-136 image whose 64-bit select is
# infeasible reports select_stack feasible with the measured family geometry,
# and the tool just renders the chosen route.


class NotShippable(Exception):
    pass


def parse_meta(directory):
    fields = {}
    for line in open(os.path.join(directory, "meta.txt"), encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        fields.setdefault(key.strip(), value.strip())
    return fields


def split_devices(value):
    return [part.strip().strip('"') for part in value.split(",") if part.strip()]


def entry_devices(meta, directory):
    devices = split_devices(meta.get("devices", ""))
    if not devices:
        primary = meta.get("device", "").strip().strip('"')
        if primary:
            devices = [primary]
    if not devices:
        devices = sorted(
            os.path.basename(path)
            for path in glob.glob(os.path.join(directory, "devices", "*"))
            if os.path.isdir(path)
        )
    return devices


def read_options(directory):
    superset = None
    for path in sorted(glob.glob(os.path.join(directory, "options-*.json"))):
        superset = path
    if superset is None:
        raise NotShippable("no option superset")
    with open(superset, encoding="utf-8") as handle:
        return json.load(handle)


def require_feasible_route(options):
    """A store entry must have at least one feasible route; the document
    already states which one the run uses."""
    if not any(
        route.get("verdict") == "feasible" for route in options.get("routes", [])
    ):
        raise NotShippable("no feasible route")


PIPELINES = ["ghostlock", "dirtyfrag"]


class PipelineChoiceRequired(Exception):
    """Both pipelines are feasible and the entry records no choice."""


def feasible_pipelines(options):
    return [
        entry.get("pipeline")
        for entry in options.get("pipelines", [])
        if entry.get("verdict") == "feasible"
        and entry.get("pipeline") in PIPELINES
    ]


def pipeline_reason(options, name):
    for entry in options.get("pipelines", []):
        if entry.get("pipeline") == name:
            return entry.get("reason", "")
    return ""


def choose_pipeline_order(options, meta):
    """The entry's curation override, or the superset's derived order.

    The superset carries the extractor's `pipeline_order` (highest precedence
    first, only statically feasible entries). `meta.txt` may override it with
    one name or a comma-separated order; every entry of the result must be
    feasible on this kernel.
    """
    feasible = feasible_pipelines(options)
    if not feasible:
        reasons = "; ".join(
            f"{entry.get('pipeline')}: {entry.get('reason', '')}"
            for entry in options.get("pipelines", [])
        )
        raise NotShippable(f"no feasible pipeline ({reasons})")
    raw = (meta.get("pipeline") or "").strip().strip('"').lower()
    order = [name.strip() for name in raw.split(",") if name.strip()]
    if not order:
        order = [
            str(name).strip().lower()
            for name in options.get("pipeline_order", [])
            if str(name).strip()
        ]
    if not order:
        # A superset written before the ordered list carried only verdicts;
        # use the extractor's default ranking.
        order = (
            ["dirtyfrag", "ghostlock"]
            if "dirtyfrag" in feasible and "ghostlock" in feasible
            else feasible[:1]
        )
    seen = set()
    for name in order:
        if name not in PIPELINES:
            raise PipelineChoiceRequired(f"unknown pipeline {name}")
        if name not in feasible:
            raise PipelineChoiceRequired(
                f"pipeline {name} is not feasible: {pipeline_reason(options, name)}"
            )
        if name in seen:
            raise PipelineChoiceRequired(f"duplicate pipeline {name}")
        seen.add(name)
    return order


def _asset_document(path):
    """The asset parsed as a value tree (None when unreadable)."""
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        return None
    try:
        return migrate_profile_shape.Parser(text, path).document()
    except migrate_profile_shape.ParseError:
        return None


def _cleaned_asset(path):
    """The asset as the cleaned document (None when it is not one)."""
    document = _asset_document(path)
    if document is None or not isinstance(document.get("release"), str):
        return None
    if not isinstance(document.get("kernel"), dict):
        return None
    return document


def asset_pipeline_order(path):
    """The fixed ladder the cleaned asset's verdict derives, or None."""
    document = _cleaned_asset(path)
    if document is None:
        return None
    ghostlock = document.get("ghostlock")
    timer = document.get("timer")
    kernel = document.get("kernel") or {}
    if ghostlock is None:
        return ["timer"] if timer is not None else None
    if isinstance(ghostlock, dict) and ghostlock.get("module") is not None:
        return ["module"]
    if kernel.get("kdp") is True:
        return ["dirtyfrag"]
    if kernel.get("dirtyfrag") == "feasible":
        return ["dirtyfrag", "ghostlock"]
    return ["ghostlock"]

def asset_has_stride(path):
    """Whether the cleaned asset states `kernel.heap.stride`."""
    document = _cleaned_asset(path)
    if document is None:
        return False
    heap = (document.get("kernel") or {}).get("heap")
    return isinstance(heap, dict) and heap.get("stride") is not None

MISSING = object()

# `schema_version` and the addresses' key order are structural, so neither is
# compared as a fact.
FACT_SKIP = {
    "schema_version",
    # The engine selection is the app's default (the alphabetically first
    # viable syscall), not a store fact.
    "ghostlock.syscall", "ghostlock.carrier",
}


def flatten_facts(value, prefix=""):
    flat = {}
    for key, item in value.items():
        name = f"{prefix}.{key}" if prefix else key
        if isinstance(item, dict):
            flat.update(flatten_facts(item, name))
        else:
            flat[name] = item
    return flat


def compose_document(entry):
    """The store's cleaned document with the store-only composition: the
    measured mm struct stride. Every device pair is already in the document
    (`devices.<codename>.phys`); the timer facts are already merged by the
    store sweep. Nothing is converted here."""
    document = entry["options"].get("document")
    if not isinstance(document, dict):
        return None
    document = copy.deepcopy(document)
    kernel = document.get("kernel")
    if isinstance(kernel, dict) and entry.get("stride"):
        kernel.setdefault("heap", {})["stride"] = int(entry["stride"])
    return document


def fact_mismatches(entry, asset_path):
    """The asset's facts that are absent or differ from the store's document."""
    expected = compose_document(entry)
    actual = _cleaned_asset(asset_path)
    if expected is None or actual is None:
        return []
    expected = flatten_facts(expected)
    actual = flatten_facts(actual)
    mismatched = []
    for key in sorted(expected):
        if key in FACT_SKIP:
            continue
        wanted = expected[key]
        # A null in the store is not an opinion; presence is the fact the
        # store can assert.
        if wanted is None:
            continue
        got = actual.get(key, MISSING)
        if got is MISSING:
            mismatched.append((key, wanted, "<absent>"))
        elif got != wanted:
            mismatched.append((key, wanted, got))
    return mismatched


def mm_struct_stride(directory, meta=None):
    """The measured /proc/slabinfo mm_struct objsize, or None.

    The extractor deliberately emits no stride (a generated profile should
    measure it, not seed it); the store's QEMU runtime dump is the usual
    measurement, and a device-only entry records it in meta.txt (`stride`).
    """
    for path in sorted(glob.glob(os.path.join(directory, "runtime-*.txt"))):
        for line in open(path, encoding="utf-8", errors="replace"):
            parts = line.split()
            if len(parts) >= 5 and parts[0] == "SLAB" and parts[1] == "mm_struct":
                try:
                    return int(parts[4])
                except ValueError:
                    return None
    if meta:
        value = (meta.get("stride") or "").strip()
        if value.isdigit():
            return int(value)
    return None


def candidates(store):
    """Entries that are not excluded by corpus; no IO on the Image or superset yet."""
    found, exempt = [], []
    for meta_path in sorted(glob.glob(os.path.join(store, "*", "meta.txt"))):
        directory = os.path.dirname(meta_path)
        meta = parse_meta(directory)
        release = meta.get("release", "").strip().strip('"')
        if not release:
            continue
        corpus = meta.get("corpus", "")
        if corpus in SKIP_CORPUS:
            exempt.append((release, f"corpus = {corpus}"))
            continue
        found.append({"release": release, "directory": directory, "meta": meta})
    return found, exempt


def feasible_route_names(options):
    return sorted(
        route.get("route")
        for route in options.get("routes", [])
        if route.get("verdict") == "feasible"
    )


def asset_route_names(path):
    """The syscall branches the cleaned asset declares (non-null blocks)."""
    document = _cleaned_asset(path)
    if document is None:
        return []
    ghostlock = document.get("ghostlock")
    if not isinstance(ghostlock, dict):
        return []
    syscalls = ghostlock.get("syscalls")
    if not isinstance(syscalls, dict):
        return []
    return [name for name, branch in syscalls.items() if branch is not None]

def validate(entry):
    options = read_options(entry["directory"])
    devices = entry_devices(entry["meta"], entry["directory"])
    if not devices:
        raise NotShippable("no device sidecar set")
    require_feasible_route(options)
    entry.update(
        options=options,
        devices=devices,
        routes=feasible_route_names(options),
        stride=mm_struct_stride(entry["directory"], entry["meta"]),
    )
    entry["feasible_pipelines"] = feasible_pipelines(options)
    return entry


def index_releases(assets):
    text = open(os.path.join(assets, "index.conf"), encoding="utf-8").read()
    return set(re.findall(r'release\s*=\s*"([^"]+)"', text))


def add_index_entries(text, releases):
    if not releases:
        return text
    lines = text.splitlines(keepends=True)
    start = next(
        index for index, line in enumerate(lines) if line.strip().startswith("profiles = [")
    )
    end = next(
        index for index in range(start + 1, len(lines)) if lines[index].strip() == "]"
    )
    marker = "  # GOLDEN: every test-kernels golden is shipped (tools/ship_golden_profiles.py).\n"
    block = []
    if not any(line.strip() == marker.strip() for line in lines):
        block.append(marker)
    block += [f'  {{ release = "{release}", file = "{release}.conf" }}\n' for release in releases]
    if lines[end - 1] and not lines[end - 1].endswith("\n"):
        lines[end - 1] += "\n"
    lines[end:end] = block
    return "".join(lines)


def asset_text(entry):
    """The store document rendered as a bundled asset, with its header."""
    document = compose_document(entry)
    if document is None:
        raise NotShippable("the store entry has no cleaned document")
    body = migrate_profile_shape.render(document)
    route_label = ", ".join(entry["routes"])
    header = (
        "# SHIPPED GOLDEN -- test-kernels golden (QEMU tier); generated, do not edit.\n"
        f"# store: {entry['release']}; routes: {route_label}; "
        f"devices: {', '.join(entry['devices'])}\n"
        "# maintained by external/ghostlock-app/tools/ship_golden_profiles.py\n"
    )
    return header + body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report only, exit 1 on a missing or mismatched golden")
    parser.add_argument("--apply", action="store_true", help="render and index the missing goldens")
    parser.add_argument("--refresh", action="store_true", help="rewrite an existing SHIPPED GOLDEN asset that --apply would otherwise refuse")
    parser.add_argument("--refresh-all", action="store_true", help="re-render every shipped golden from the store as the route superset")
    parser.add_argument("--store", default=DEFAULT_STORE)
    parser.add_argument("--assets", default=DEFAULT_ASSETS)
    parser.add_argument(
        "--only", action="append", default=[],
        help="limit to releases matching any given substring (debugging)",
    )
    args = parser.parse_args()
    if args.check and (args.apply or args.refresh or args.refresh_all):
        parser.error("--check is mutually exclusive with --apply/--refresh/--refresh-all")

    found, exempt = candidates(args.store)
    index = index_releases(args.assets)
    assets = {
        os.path.basename(path)[:-5]
        for path in glob.glob(os.path.join(args.assets, "*.conf"))
    }

    if args.refresh_all:
        refreshed, failed = [], []
        for entry in found:
            release = entry["release"]
            if args.only and not any(needle in release for needle in args.only):
                continue
            asset_path = os.path.join(args.assets, release + ".conf")
            if not os.path.isfile(asset_path):
                continue
            try:
                validate(entry)
                entry["choice_pipeline_order"] = choose_pipeline_order(
                    entry["options"], entry["meta"]
                )
            except NotShippable:
                continue
            except PipelineChoiceRequired as error:
                failed.append((release, str(error)))
                continue
            try:
                text = asset_text(entry)
            except Exception as error:  # noqa: BLE001 - report each entry, keep going
                failed.append((release, str(error)))
                continue
            with open(asset_path, "w", encoding="utf-8") as handle:
                handle.write(text)
            refreshed.append(release)
        for release in refreshed:
            print(f"refreshed {release}")
        for release, error in failed:
            print(f"FAILED  {release}: {error}", file=sys.stderr)
        print(f"refreshed {len(refreshed)}, failed {len(failed)}")
        return 1 if failed else 0

    bundled = 0
    missing = []
    mismatched_entries = []
    for entry in found:
        release = entry["release"]
        if args.only and not any(needle in release for needle in args.only):
            continue
        asset_path = os.path.join(args.assets, release + ".conf")
        has_asset = os.path.isfile(asset_path)
        try:
            validate(entry)
        except NotShippable as error:
            reason = str(error)
            # A hand-finished asset is the documented exception when the store
            # entry has no superset / no sidecar set / mixed physical pairs.
            # A stale asset on a kernel with no feasible route or pipeline is
            # a real content failure.
            hand_finished = has_asset and not any(
                phrase in reason
                for phrase in ("no feasible route", "no feasible pipeline")
            )
            if hand_finished:
                bundled += 1
            elif has_asset:
                entry["reasons"] = [f"asset present but entry is not shippable: {reason}"]
                missing.append(entry)
            else:
                exempt.append((release, reason))
            continue
        feasible = entry["feasible_pipelines"]
        if has_asset:
            if not feasible:
                entry["reasons"] = ["asset present but no feasible pipeline"]
                missing.append(entry)
                continue
            try:
                expected = choose_pipeline_order(entry["options"], entry["meta"])
            except PipelineChoiceRequired as error:
                entry["reasons"] = [str(error)]
                missing.append(entry)
                continue
            chosen = asset_pipeline_order(asset_path)
            if chosen is None:
                entry["reasons"] = [
                    f"asset has no pipeline line (expected {', '.join(expected)})"
                ]
                missing.append(entry)
                continue
            if chosen != expected:
                entry["reasons"] = [
                    f"asset pipeline {chosen} != derived order {expected}"
                ]
                missing.append(entry)
                continue
            asset_routes = asset_route_names(asset_path)
            # The cleaned document has no route order: compare as a set.
            if sorted(asset_routes) != sorted(entry["routes"]):
                entry["reasons"] = [
                    f"asset routes {asset_routes} != derived {entry['routes']}"
                ]
                mismatched_entries.append(entry)
                continue
            if release not in index:
                entry["reasons"] = ["asset present but not in index.conf"]
                missing.append(entry)
                continue
            if entry.get("stride") and not asset_has_stride(asset_path):
                entry["reasons"] = [
                    f"asset has no mm_struct_sz (entry stride {entry['stride']}); "
                    "use --refresh"
                ]
                missing.append(entry)
                continue
            facts = fact_mismatches(entry, asset_path)
            if facts:
                entry["reasons"] = [
                    f"{key}: expected {wanted!r}, got {got!r}"
                    for key, wanted, got in facts[:6]
                ]
                if len(facts) > 6:
                    entry["reasons"].append(f"... {len(facts) - 6} more")
                mismatched_entries.append(entry)
                continue
            bundled += 1
            continue
        try:
            entry["choice_pipeline_order"] = choose_pipeline_order(
                entry["options"], entry["meta"]
            )
        except PipelineChoiceRequired as error:
            entry["reasons"] = [str(error)]
            missing.append(entry)
            continue
        except NotShippable as error:
            exempt.append((release, str(error)))
            continue
        entry["reasons"] = []
        if release not in index:
            entry["reasons"].append("not in index.conf")
        entry["reasons"].append("no asset file")
        missing.append(entry)

    if args.check:
        for entry in missing:
            print(f"MISSING {entry['release']}: {', '.join(entry['reasons'])}")
        for entry in mismatched_entries:
            print(f"MISMATCH {entry['release']}: {', '.join(entry['reasons'])}")
        for name in sorted(index):
            if name not in assets:
                print(f"ORPHAN index entry {name}: no asset file")
        print(
            f"{bundled} bundled, {len(missing)} missing, "
            f"{len(mismatched_entries)} mismatched, {len(exempt)} exempt"
        )
        return 1 if missing or mismatched_entries else 0

    if not missing:
        for entry in mismatched_entries:
            print(f"MISMATCH {entry['release']}: {', '.join(entry['reasons'])}")
        print(
            f"{bundled} bundled, 0 missing, "
            f"{len(mismatched_entries)} mismatched, {len(exempt)} exempt"
        )
        return 1 if mismatched_entries else 0
    shipped = []
    refreshed = []
    failures = []
    index_path = os.path.join(args.assets, "index.conf")
    for entry in missing:
        asset_path = os.path.join(args.assets, entry["release"] + ".conf")
        exists = os.path.isfile(asset_path)
        if exists and not args.refresh:
            failures.append((entry["release"], "asset exists; use --refresh to rewrite it"))
            continue
        if exists:
            first = open(asset_path, encoding="utf-8").readline()
            if not first.startswith("# SHIPPED GOLDEN"):
                failures.append((entry["release"], "asset is hand-finished; not overwritten"))
                continue
        if "choice_pipeline_order" not in entry:
            try:
                entry["choice_pipeline_order"] = choose_pipeline_order(
                    entry["options"], entry["meta"]
                )
            except (PipelineChoiceRequired, NotShippable) as error:
                failures.append((entry["release"], str(error)))
                continue
        try:
            text = asset_text(entry)
        except Exception as error:  # noqa: BLE001 - report each entry, keep going
            failures.append((entry["release"], str(error)))
            continue
        with open(asset_path, "w", encoding="utf-8") as handle:
            handle.write(text)
        if exists:
            refreshed.append(entry["release"])
        else:
            shipped.append(entry["release"])

    new_index = [release for release in sorted(shipped) if release not in index]
    if new_index:
        text = open(index_path, encoding="utf-8").read()
        text = add_index_entries(text, new_index)
        with open(index_path, "w", encoding="utf-8") as handle:
            handle.write(text)

    for release in shipped:
        print(f"shipped {release}")
    for release in refreshed:
        print(f"refreshed {release}")
    for release, reason in exempt:
        print(f"exempt  {release}: {reason}")
    for release, error in failures:
        print(f"FAILED  {release}: {error}", file=sys.stderr)
    for entry in mismatched_entries:
        print(f"MISMATCH {entry['release']}: {', '.join(entry['reasons'])}")
    print(
        f"shipped {len(shipped)}, refreshed {len(refreshed)}, exempt {len(exempt)}, "
        f"failed {len(failures)}, mismatched {len(mismatched_entries)}, "
        f"already bundled {bundled}"
    )
    return 1 if failures or mismatched_entries else 0


if __name__ == "__main__":
    sys.exit(main())
