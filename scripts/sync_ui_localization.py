#!/usr/bin/env python3
"""Synchronize the canonical QuantaEdge UI message catalogs into the standalone web apps.

The app builds use apps/{web,admin,people} as independent Docker contexts, so each app
receives a generated, byte-identical runtime copy. Edit localization/catalogs only.
"""
from __future__ import annotations
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "localization"
APPS = ("web", "admin", "people")
FILES = ("catalogs/english.json", "catalogs/hinglish.json", "runtime.ts")


def validate() -> None:
    catalogs = {}
    for locale in ("english", "hinglish"):
        path = SOURCE / "catalogs" / f"{locale}.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or not payload:
            raise ValueError(f"{path}: expected a non-empty flat key/value object")
        invalid = [key for key, value in payload.items() if not isinstance(key, str) or not isinstance(value, str) or not value.strip()]
        if invalid:
            raise ValueError(f"{path}: empty or non-string messages: {invalid[:5]}")
        catalogs[locale] = payload
    if set(catalogs["english"]) != set(catalogs["hinglish"]):
        only_en = sorted(set(catalogs["english"]) - set(catalogs["hinglish"]))
        only_hi = sorted(set(catalogs["hinglish"]) - set(catalogs["english"]))
        raise ValueError(f"Catalog key mismatch. English-only={only_en[:10]}, Hinglish-only={only_hi[:10]}")
    token = re.compile(r"\\{([a-zA-Z][a-zA-Z0-9_]*)\\}")
    for key in catalogs["english"]:
        en_tokens = sorted(token.findall(catalogs["english"][key]))
        hi_tokens = sorted(token.findall(catalogs["hinglish"][key]))
        if en_tokens != hi_tokens:
            raise ValueError(f"Placeholder mismatch for {key}: English={en_tokens}, Hinglish={hi_tokens}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if an app copy differs; never writes files")
    args = parser.parse_args()
    try:
        validate()
        differences = []
        for app in APPS:
            target_root = ROOT / "apps" / app / "app" / "i18n"
            for relative in FILES:
                source = SOURCE / relative
                target = target_root / relative
                if args.check:
                    if not target.is_file() or target.read_bytes() != source.read_bytes():
                        differences.append(str(target.relative_to(ROOT)))
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(source.read_bytes())
        if differences:
            print("Out-of-sync generated localization files:", *differences, sep="\\n- ", file=sys.stderr)
            return 1
        print(f"Central localization valid: {len(catalogs_keys())} keys × 2 locales; " + ("all app copies match" if args.check else "all three app copies synchronized"))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Localization validation failed: {exc}", file=sys.stderr)
        return 1


def catalogs_keys() -> list[str]:
    return list(json.loads((SOURCE / "catalogs" / "english.json").read_text(encoding="utf-8")).keys())

if __name__ == "__main__":
    raise SystemExit(main())
