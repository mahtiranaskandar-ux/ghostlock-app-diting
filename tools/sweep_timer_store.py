#!/usr/bin/env python3
"""Merge the timer analyzer's cleaned fragment into every store document.

The timer engine's per-image facts — the five symbols, the credential security
sid and reference offsets, and the `timer` block — are derived from the image
by `timer-extract`. This tool runs it once per store entry and deep-merges the
fragment into `options-<release>.json`'s `document`, keeping the analyzer's
evidence under `timer_analysis` (with the same fragment, so a store
regeneration can merge it again).

    python3 tools/sweep_timer_store.py            # report only
    python3 tools/sweep_timer_store.py --write    # rewrite the store entries

Skips an entry with no image or no options document, a module-only profile
(the store's 5.4 entries ship a module, not an engine), and any entry whose
analyzer does not report the vulnerability as present.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import migrate_profile_shape

APP = Path(__file__).resolve().parent.parent
DEFAULT_STORE = APP.parent / "test-kernels"
DEFAULT_BIN = APP / "build" / "timer-extract" / "release" / "timer-extract"


def merge(base, patch):
    """Deep-merge `patch` into `base`; a null in the patch is 'no opinion'."""
    for key, value in patch.items():
        if isinstance(value, dict):
            current = base.get(key)
            if not isinstance(current, dict):
                current = {}
                base[key] = current
            merge(current, value)
        elif value is not None:
            base[key] = value
    return base


def module_only(document):
    ghostlock = document.get("ghostlock")
    return isinstance(ghostlock, dict) and ghostlock.get("module") is not None


def entries(store):
    for path in sorted(store.iterdir()):
        if not path.is_dir() or not (path / "meta.txt").is_file():
            continue
        release = path.name
        options = path / f"options-{release}.json"
        image = path / f"Image-{release}"
        yield release, path, options, image


def run(binary, image, out_dir, release):
    fragment_path = out_dir / f"{release}.timer.conf"
    evidence_path = out_dir / f"{release}.timer.json"
    command = [
        str(binary),
        str(image),
        "--emit-timer-conf", str(fragment_path),
        "--emit-superset", str(evidence_path),
        "--endgame", "real,fake",
    ]
    if release.startswith("5.10."):
        command += ["--layout-template", "5.10"]
    result = subprocess.run(command, capture_output=True, text=True, timeout=600)
    return result, fragment_path, evidence_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", default=str(DEFAULT_STORE))
    parser.add_argument("--bin", default=str(DEFAULT_BIN))
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    store = Path(args.store)
    binary = Path(args.bin)
    if not binary.is_file():
        parser.error(f"timer analyzer not found: {binary} (cargo build --release)")

    import tempfile

    results = []
    with tempfile.TemporaryDirectory(prefix="gl-timer-store-") as scratch:
        out_dir = Path(scratch)
        for release, directory, options_path, image in entries(store):
            if not options_path.is_file() or not image.is_file():
                results.append((release, "skip", "no options/image"))
                continue
            options = json.loads(options_path.read_text())
            document = options.get("document")
            if not isinstance(document, dict):
                results.append((release, "skip", "no cleaned document"))
                continue
            if module_only(document):
                results.append((release, "skip", "module-only profile"))
                continue
            result, fragment_path, evidence_path = run(binary, image, out_dir, release)
            if result.returncode != 0:
                results.append(
                    (release, "skip", f"analyzer exit {result.returncode}")
                )
                continue
            fragment = migrate_profile_shape.Parser(
                fragment_path.read_text(), str(fragment_path)
            ).document()
            evidence = json.loads(evidence_path.read_text())
            evidence["document"] = fragment
            document_merged = merge(document, fragment)
            options["document"] = document_merged
            options["timer_analysis"] = evidence
            if args.write:
                options_path.write_text(
                    json.dumps(options, indent=2, ensure_ascii=False) + "\n"
                )
            results.append((release, "ok", "timer facts merged"))

    for release, status, detail in results:
        print(f"{status:<5} {release}: {detail}")
    ok = sum(1 for result in results if result[1] == "ok")
    skipped = sum(1 for result in results if result[1] == "skip")
    print(f"{'wrote' if args.write else 'would write'} {ok}, skipped {skipped}")


if __name__ == "__main__":
    main()
