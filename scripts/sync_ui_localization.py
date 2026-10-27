#!/usr/bin/env python3
"""Validate and synchronize central QuantaEdge UI language catalogs.

Add a catalog file in localization/catalogs and metadata in localization/locales.json.
Running this script regenerates the static TypeScript locale registry, browser-app copies,
and an Android compatibility resource catalog derived from the same canonical messages.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "localization"
APPS = ("web", "admin", "people")
TOKEN = re.compile(r"\{([a-zA-Z][a-zA-Z0-9_]*)\}")


def load_locales() -> dict[str, dict[str, Any]]:
    path = SOURCE / "locales.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not payload:
        raise ValueError(f"{path}: expected a non-empty locale metadata object")
    required = {"label", "nativeLabel", "lang", "aliases", "fallback", "default"}
    for locale, metadata in payload.items():
        if not isinstance(locale, str) or not re.fullmatch(r"[a-z][a-z0-9-]*", locale):
            raise ValueError(f"{path}: invalid locale ID {locale!r}")
        if not isinstance(metadata, dict) or not required.issubset(metadata):
            raise ValueError(f"{path}: {locale} must define {sorted(required)}")
        for key in ("label", "nativeLabel", "lang", "fallback"):
            if not isinstance(metadata[key], str) or not metadata[key].strip():
                raise ValueError(f"{path}: {locale}.{key} must be a non-empty string")
        if not isinstance(metadata["aliases"], list) or any(not isinstance(alias, str) or not alias.strip() for alias in metadata["aliases"]):
            raise ValueError(f"{path}: {locale}.aliases must be an array of non-empty strings")
        if not isinstance(metadata["default"], bool):
            raise ValueError(f"{path}: {locale}.default must be boolean")
    defaults = [locale for locale, metadata in payload.items() if metadata["default"]]
    if len(defaults) != 1:
        raise ValueError(f"{path}: exactly one locale must set default=true")
    if "english" not in payload:
        raise ValueError(f"{path}: English catalog is required as the universal fallback")
    return payload


def load_catalogs(locales: dict[str, dict[str, Any]]) -> dict[str, dict[str, str]]:
    catalog_dir = SOURCE / "catalogs"
    paths = sorted(catalog_dir.glob("*.json"))
    catalogs: dict[str, dict[str, str]] = {}
    for path in paths:
        locale = path.stem
        payload = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or not payload:
            raise ValueError(f"{path}: expected a non-empty flat key/value object")
        invalid = [key for key, value in payload.items()
                   if not isinstance(key, str) or not isinstance(value, str) or not value.strip()]
        if invalid:
            raise ValueError(f"{path}: empty or non-string messages: {invalid[:5]}")
        catalogs[locale] = payload

    if set(catalogs) != set(locales):
        raise ValueError(
            "Locale registry/catalog mismatch. Missing catalog files="
            f"{sorted(set(locales) - set(catalogs))}; unregistered catalog files="
            f"{sorted(set(catalogs) - set(locales))}. Add both the catalog and metadata."
        )
    aliases: dict[str, str] = {}
    for locale, metadata in locales.items():
        if metadata["fallback"] not in catalogs:
            raise ValueError(f"{locale}: fallback {metadata['fallback']!r} is not registered")
        for alias in metadata["aliases"]:
            normalized = alias.strip().lower()
            if normalized in catalogs and normalized != locale:
                raise ValueError(f"{locale}: alias {alias!r} conflicts with a registered locale ID")
            previous = aliases.get(normalized)
            if previous and previous != locale:
                raise ValueError(f"Locale alias {alias!r} is shared by {previous} and {locale}")
            aliases[normalized] = locale

    english_keys = set(catalogs["english"])
    for locale, payload in catalogs.items():
        keys = set(payload)
        if keys != english_keys:
            raise ValueError(
                f"Catalog key mismatch for {locale}. English-only={sorted(english_keys - keys)[:10]}, "
                f"{locale}-only={sorted(keys - english_keys)[:10]}"
            )
        for key in sorted(english_keys):
            english_tokens = sorted(TOKEN.findall(catalogs["english"][key]))
            locale_tokens = sorted(TOKEN.findall(payload[key]))
            if english_tokens != locale_tokens:
                raise ValueError(f"Placeholder mismatch for {locale}.{key}: English={english_tokens}, {locale}={locale_tokens}")
    return catalogs


def render_registry(locales: dict[str, dict[str, Any]]) -> str:
    locale_ids = sorted(locales)
    imports = "\n".join(f"import {locale.replace('-', '_')} from './catalogs/{locale}.json';" for locale in locale_ids)
    entries = ",\n".join(f"  '{locale}': {locale.replace('-', '_')}" for locale in locale_ids)
    return (
        imports + "\nimport locales from './locales.json';\n\n"
        + "export const uiCatalogs = {\n" + entries + ",\n} as const;\n\n"
        + "export const uiLocaleMetadata = locales as {\n"
        + "  readonly [locale: string]: {\n"
        + "    readonly label: string;\n"
        + "    readonly nativeLabel: string;\n"
        + "    readonly lang: string;\n"
        + "    readonly aliases: readonly string[];\n"
        + "    readonly fallback: string;\n"
        + "    readonly default: boolean;\n"
        + "  };\n"
        + "};\n"
    )


def _xml_resource_text(value: str) -> str:
    # Android string resources treat apostrophes and backslashes specially in addition to XML.
    escaped = value.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    escaped = escaped.replace("\r", "").replace("\n", "\\n").replace("\t", "\\t")
    return html.escape(escaped, quote=False)


def render_android_catalog_resources(catalogs: dict[str, dict[str, str]]) -> dict[str, bytes]:
    """
    Generate Android string resources from unambiguous English -> Hinglish pairs.

    Exact English phrases that have multiple Hinglish translations are intentionally omitted:
    those need a semantic key/context-specific migration instead of guessing at runtime.
    Existing Android-specific phrase mappings remain as a compatibility fallback.
    """
    english = catalogs["english"]
    hinglish = catalogs["hinglish"]
    translations_by_source: dict[str, set[str]] = {}
    for key, source in english.items():
        translations_by_source.setdefault(source, set()).add(hinglish[key])

    entries: list[tuple[str, str, str]] = []
    seen_names: dict[str, str] = {}
    for source in sorted(translations_by_source):
        translated = translations_by_source[source]
        if len(translated) != 1:
            continue
        target = next(iter(translated))
        if not source.strip() or not target.strip() or source == target:
            continue
        # Newlines in UI copy need explicit context review and must not be silently normalized.
        if "\\n" in source or "\\r" in source or "\\n" in target or "\\r" in target:
            continue
        resource_name = "qe_ui_" + hashlib.sha256(source.encode("utf-8")).hexdigest()[:16]
        previous = seen_names.get(resource_name)
        if previous is not None and previous != source:
            raise ValueError(f"Android UI resource hash collision: {previous!r} and {source!r}")
        seen_names[resource_name] = source
        entries.append((resource_name, source, target))

    def render(values: bool) -> bytes:
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for resource_name, source, target in entries:
            value = source if values else target
            lines.append(
                f'    <string name="{resource_name}" formatted="false">{_xml_resource_text(value)}</string>'
            )
        lines.append("</resources>")
        return ("\\n".join(lines) + "\\n").encode("utf-8")

    return {
        "apps/android/app/src/main/res/values/qe_ui_catalog.xml": render(True),
        "apps/android/app/src/main/res/values-hi/qe_ui_catalog.xml": render(False),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if generated copies differ; never writes files")
    args = parser.parse_args()
    try:
        locales = load_locales()
        catalogs = load_catalogs(locales)
        expected_registry = render_registry(locales).encode("utf-8")
        registry_path = SOURCE / "registry.ts"
        differences = []
        if args.check:
            if not registry_path.is_file() or registry_path.read_bytes() != expected_registry:
                differences.append("localization/registry.ts (generated registry is stale)")
        else:
            registry_path.write_bytes(expected_registry)

        source_files = [(f"catalogs/{locale}.json", f"catalogs/{locale}.json") for locale in sorted(catalogs)]
        source_files.extend([("locales.json", "locales.json"), ("registry.ts", "registry.ts"), ("runtime.ts", "catalog.ts")])
        for app in APPS:
            target_root = ROOT / "apps" / app / "app" / "i18n"
            for source_relative, target_relative in source_files:
                source = SOURCE / source_relative
                target = target_root / target_relative
                if args.check:
                    if not target.is_file() or target.read_bytes() != source.read_bytes():
                        differences.append(str(target.relative_to(ROOT)))
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(source.read_bytes())

        for relative_path, expected_bytes in render_android_catalog_resources(catalogs).items():
            target = ROOT / relative_path
            if args.check:
                if not target.is_file() or target.read_bytes() != expected_bytes:
                    differences.append(relative_path + " (generated Android catalog is stale)")
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(expected_bytes)

        if differences:
            print("Out-of-sync generated localization files:\n- " + "\n- ".join(differences), file=sys.stderr)
            return 1
        status = "all browser and Android copies match" if args.check else "registry, browser copies and Android resources generated"
        print(f"Central localization valid: {len(catalogs['english'])} keys × {len(catalogs)} locale(s); {status}")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Localization validation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
