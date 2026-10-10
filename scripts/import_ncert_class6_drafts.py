#!/usr/bin/env python3
"""Validate and import the QuantaEdge NCERT Class 6 Hindi pack as DRAFT records only.

Default mode is a no-write dry run. For an actual write, sign in to QuantaEdge Admin,
export the QE_SESSION cookie to QUANTAEDGE_ADMIN_SESSION, and pass --apply.
This script never publishes chapters, lessons, or questions.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any

# Run from a checkout of the QuantaEdge repository.
REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "scripts"))

try:
    from import_site_content_draft import validate_site_bundle, dry_run_summary, run_import, AdminApi
except ImportError as exc:
    raise SystemExit("Missing scripts/import_site_content_draft.py; run this from the QuantaEdge repo.") from exc


def load_bundles(package: Path, temp_dir: Path) -> list[tuple[str, dict[str, Any]]]:
    source = package
    if package.is_file():
        with zipfile.ZipFile(package) as archive:
            members = [
                m for m in archive.namelist()
                if "/content/bundles/" in m and m.endswith(".json")
                and ("/maths/" in m or "/science/" in m)
            ]
            if not members:
                raise ValueError("Package ZIP contains no content/bundles/{maths,science} JSON files.")
            for name in members:
                destination = temp_dir / Path(name).name
                destination.write_bytes(archive.read(name))
        paths = sorted(temp_dir.glob("*.json"))
    elif package.is_dir():
        paths = sorted((package / "content" / "bundles").glob("*/*.json"))
        if not paths:
            paths = sorted(package.glob("content/bundles/*/*.json"))
    else:
        raise ValueError(f"Package does not exist: {package}")
    if not paths:
        raise ValueError("No authoring bundles found.")
    loaded = [(str(p), json.loads(p.read_text(encoding="utf-8"))) for p in paths]
    return sorted(loaded, key=lambda pair: (
        str(pair[1].get("curriculum", {}).get("subject_code", "")),
        int(pair[1].get("chapter", {}).get("sort_order", 0)),
    ))


def make_codes_unique(bundle: dict[str, Any]) -> dict[str, Any]:
    """Namespace lesson codes by chapter: the CMS requires lesson codes unique per track."""
    result = json.loads(json.dumps(bundle, ensure_ascii=False))
    chapter_code = str(result["chapter"]["code"])
    mapping: dict[str, str] = {}
    for lesson in result["lessons"]:
        old = str(lesson["code"])
        new = f"{chapter_code}-{old}"
        if len(new) > 100:
            new = f"{chapter_code[:55]}-{old[-40:]}"
        mapping[old] = new
        lesson["code"] = new
    for question in result.get("online_question_bank", []):
        old = str(question.get("lesson_code", ""))
        if old not in mapping:
            raise ValueError(f"Question {question.get('id')} points to unknown lesson_code {old!r}.")
        question["lesson_code"] = mapping[old]
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True,
                        help="QuantaEdge_Class6_NCERT_Hindi_Complete_Content_Package.zip or extracted package directory")
    parser.add_argument("--apply", action="store_true", help="Write content as DRAFT using the authenticated Admin API")
    parser.add_argument("--api-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--subject", choices=("maths", "science"), help="Limit this run to one subject")
    args = parser.parse_args()
    session = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "")
    if args.apply and not session:
        parser.error("--apply requires QUANTAEDGE_ADMIN_SESSION from an authenticated QuantaEdge Admin QE_SESSION cookie.")
    if args.apply and not args.api_url.startswith(("https://", "http://localhost", "http://127.0.0.1")):
        parser.error("Use HTTPS for a remote API; HTTP is permitted only for localhost.")

    results: list[dict[str, Any]] = []
    failed = False
    with tempfile.TemporaryDirectory(prefix="qe-ncert-c6-") as td:
        try:
            bundles = load_bundles(args.package, Path(td))
        except (OSError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as exc:
            parser.error(str(exc))
        if args.subject:
            bundles = [(p, b) for p, b in bundles if b.get("curriculum", {}).get("subject_code") == args.subject]
        api = AdminApi(args.api_url, session) if args.apply else None
        cache: dict[tuple[str, str], list[dict[str, Any]]] = {}
        for source_path, original in bundles:
            try:
                bundle = make_codes_unique(original)
                # NCERT is canonical for this requested content rollout; allow a new NCERT chapter code.
                validate_site_bundle(bundle, allow_noncanonical_chapter=True)
                if not args.apply:
                    item = dry_run_summary(bundle, attach_to_existing_chapter=False, allow_noncanonical_chapter=True)
                    item["source_file"] = Path(source_path).name
                    results.append(item)
                    continue

                curriculum = bundle["curriculum"]
                key = (str(curriculum["class_code"]), str(curriculum["subject_code"]))
                if key not in cache:
                    from urllib.parse import urlencode
                    query = urlencode({"classCode": key[0], "subjectCode": key[1], "status": "ALL"})
                    rows = api.request("GET", "/api/v1/admin/content?" + query)  # type: ignore[union-attr]
                    if not isinstance(rows, list):
                        raise RuntimeError("Admin content catalogue returned an unexpected response.")
                    cache[key] = rows

                code = bundle["chapter"]["code"]
                matching = [r for r in cache[key] if str(r.get("chapter_code", "")).strip() == code]
                attach = bool(matching)
                if matching:
                    present_lessons = {
                        str(r.get("lesson_code")).strip()
                        for r in cache[key] if str(r.get("chapter_code", "")).strip() == code and r.get("lesson_code")
                    }
                    wanted = {str(l["code"]) for l in bundle["lessons"]}
                    if wanted.issubset(present_lessons):
                        results.append({"mode": "SKIPPED_ALREADY_PRESENT", "chapter_code": code,
                                        "subject": key[1], "note": "All intended lesson codes already exist; inspect existing records before retrying."})
                        continue
                    if wanted.intersection(present_lessons):
                        raise RuntimeError("Partial lesson-code collision detected. No changes made for this chapter; inspect Admin Content Studio.")
                result = run_import(bundle, args.api_url, session, attach_to_existing_chapter=attach,
                                    allow_noncanonical_chapter=True)
                result["source_file"] = Path(source_path).name
                results.append(result)
                # Refresh catalogue between writes so later duplicate checks see this batch's inserts.
                cache.pop(key, None)
            except Exception as exc:
                failed = True
                results.append({"mode": "FAILED_SAFELY", "source_file": Path(source_path).name,
                                "chapter_code": original.get("chapter", {}).get("code"),
                                "error": str(exc), "note": "Publication was not attempted."})
    output = {"mode": "APPLY_DRAFT_ONLY" if args.apply else "DRY_RUN",
              "package": str(args.package), "selected_chapters": len(bundles),
              "succeeded_or_skipped": sum(x.get("mode") != "FAILED_SAFELY" for x in results),
              "failed": sum(x.get("mode") == "FAILED_SAFELY" for x in results),
              "publish_action_taken": False, "results": results}
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
