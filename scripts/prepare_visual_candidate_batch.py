#!/usr/bin/env python3
"""Stage-aware, per-book checkpointing for QuantaEdge visual candidate preparation."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path, PurePosixPath
from typing import Any

PACK_SCHEMA = "quantaedge.visual-candidate-pack.v3"
MANIFEST_SCHEMA = "quantaedge.visual-candidate-bundle-manifest.v1"
INDEX_SCHEMA = "quantaedge.visual-candidate-bundle-index.v3"
ARTIFACT_TYPE = "application/vnd.quantaedge.textbook.visual-candidate-bundle.v3"
PREPARATION_SCRIPTS = (
    "scripts/extract_visual_asset_inventory.py",
    "scripts/prepare_visual_candidates_v3.py",
    "scripts/build_visual_lesson_attachment_manifest.py",
    "scripts/prepare_visual_candidate_batch.py",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object at {path}.")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(path)


def safe_path(root: Path, relative: str) -> Path:
    part = PurePosixPath(relative)
    if not relative or part.is_absolute() or ".." in part.parts or not part.parts:
        raise ValueError(f"Unsafe bundle-relative path: {relative!r}")
    base = root.resolve()
    target = (base / Path(*part.parts)).resolve()
    if base not in target.parents:
        raise ValueError(f"Bundle path escapes candidate root: {relative!r}")
    return target


def inventory_context(inventory_path: Path) -> dict[str, Any]:
    value = read_json(inventory_path)
    source = value.get("source") if isinstance(value.get("source"), dict) else {}
    pages, assets = value.get("pages"), value.get("assets")
    if value.get("schema_version") != "quantaedge.visual-inventory.v1" or not isinstance(pages, list) or not isinstance(assets, list):
        raise ValueError(f"Invalid Layer 1 inventory {inventory_path}.")
    return {
        "source_book_id": source.get("book_id"),
        "source_pdf_sha256": str(source.get("pdf_sha256") or "").lower(),
        "pdf_page_count": source.get("pdf_page_count"),
        "page_context": [
            [p.get("source_pdf_page_number"), p.get("page_text_sha256")]
            for p in pages if isinstance(p, dict)
        ],
        "assets": [
            [a.get("asset_id"), a.get("kind"), a.get("asset_sha256"), a.get("page_text_sha256"),
             hashlib.sha256(str(a.get("page_text_context") or "")[:1600].encode("utf-8")).hexdigest()]
            for a in assets if isinstance(a, dict)
        ],
    }


def preparation_fingerprint(source: dict[str, Any], work: Path, inventory_path: Path, repo: Path) -> str:
    pdf = work / "source.pdf"
    manifest = work / "source.manifest.json"
    filename = source.get("text_jsonl_filename")
    if not isinstance(filename, str) or Path(filename).name != filename or not filename.endswith(".jsonl"):
        raise ValueError(f"{source.get('book_slug')}: unsafe/missing page JSONL filename.")
    pages = work / filename
    for name, path in (("source PDF", pdf), ("extraction manifest", manifest), ("page JSONL", pages), ("inventory", inventory_path)):
        if not path.is_file():
            raise ValueError(f"{source.get('book_slug')}: missing {name}: {path}")
    actual_pdf_hash = sha256_file(pdf)
    if actual_pdf_hash != str(source.get("sha256") or "").lower():
        raise ValueError(f"{source.get('book_slug')}: source PDF hash differs from the selected whole-book source.")
    script_hashes = {}
    for relative in PREPARATION_SCRIPTS:
        path = repo / relative
        if not path.is_file():
            raise ValueError(f"Preparation dependency is missing: {relative}")
        script_hashes[relative] = sha256_file(path)
    import fitz
    value = {
        "schema_version": "quantaedge.visual-candidate-preparation-fingerprint.v1",
        "class": source.get("class") or source.get("classes"),
        "source_book_id": source["book_id"],
        "source_pdf_sha256": actual_pdf_hash,
        "manifest_sha256": sha256_file(manifest),
        "page_jsonl_sha256": sha256_file(pages),
        "inventory_sha256": sha256_file(inventory_path),
        "inventory_context": inventory_context(inventory_path),
        "preparation_scripts_sha256": script_hashes,
        "python_version": f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
        "pymupdf_version": str(getattr(fitz, "VersionBind", "unknown")),
        "preview_width": 1200,
        "crop_scale": 2.0,
        "candidate_pack_schema": PACK_SCHEMA,
    }
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def validate_pack(out: Path, source: dict[str, Any]) -> dict[str, Any]:
    pack_path, summary_path = out / "candidates.v3.json", out / "summary.v3.json"
    if not pack_path.is_file() or not summary_path.is_file():
        raise ValueError(f"{source['book_slug']}: candidate pack/summary missing.")
    pack, summary = read_json(pack_path), read_json(summary_path)
    if pack.get("schema_version") != PACK_SCHEMA:
        raise ValueError(f"{source['book_slug']}: candidate pack schema mismatch.")
    pack_source = pack.get("source") if isinstance(pack.get("source"), dict) else {}
    if str(pack_source.get("pdf_sha256") or "").lower() != str(source["sha256"]).lower():
        raise ValueError(f"{source['book_slug']}: candidate pack source PDF hash mismatch.")
    if pack_source.get("book_id") and pack_source["book_id"] != source["book_id"]:
        raise ValueError(f"{source['book_slug']}: candidate pack book ID mismatch.")
    data = pack.get("summary") if isinstance(pack.get("summary"), dict) else {}
    count = data.get("selected_candidate_count")
    if not isinstance(count, int) or count < 1 or data.get("publication_allowed") is not False:
        raise ValueError(f"{source['book_slug']}: candidate pack is empty or is not review-only.")
    if summary.get("selected_candidate_count") != count or summary.get("publication_allowed") is not False:
        raise ValueError(f"{source['book_slug']}: candidate summary mismatches pack.")
    candidates = pack.get("candidates")
    if not isinstance(candidates, list) or len(candidates) != count:
        raise ValueError(f"{source['book_slug']}: candidate list length mismatch.")
    for item in candidates:
        if not isinstance(item, dict) or not item.get("candidate_id"):
            raise ValueError(f"{source['book_slug']}: malformed candidate entry.")
        for key in ("display_path", "source_page_preview_path"):
            rel = item.get(key)
            if not isinstance(rel, str) or not safe_path(out, rel).is_file():
                raise ValueError(f"{source['book_slug']}: missing/unsafe {key} for {item['candidate_id']}.")
    return pack


def write_bundle_manifest(out: Path, source: dict[str, Any], fingerprint: str) -> dict[str, Any]:
    pack = validate_pack(out, source)
    files: dict[str, str] = {}
    for path in sorted(out.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Candidate bundle contains symlink: {path}")
        if path.is_file() and path.name != "candidate-bundle-manifest.v1.json":
            files[path.relative_to(out).as_posix()] = sha256_file(path)
    value = {
        "schema_version": MANIFEST_SCHEMA,
        "book_slug": source["book_slug"],
        "source_book_id": source["book_id"],
        "source_pdf_sha256": source["sha256"],
        "preparation_fingerprint": fingerprint,
        "candidate_pack_schema": pack["schema_version"],
        "candidate_pack_sha256": sha256_file(out / "candidates.v3.json"),
        "summary_sha256": sha256_file(out / "summary.v3.json"),
        "candidate_count": pack["summary"]["selected_candidate_count"],
        "files": files,
        "human_review_required": True,
        "publication_allowed": False,
    }
    write_json(out / "candidate-bundle-manifest.v1.json", value)
    return value


def validate_bundle(out: Path, source: dict[str, Any], fingerprint: str) -> dict[str, Any]:
    manifest_path = out / "candidate-bundle-manifest.v1.json"
    if not manifest_path.is_file():
        raise ValueError(f"{source['book_slug']}: fingerprinted bundle manifest missing.")
    manifest = read_json(manifest_path)
    if (
        manifest.get("schema_version") != MANIFEST_SCHEMA
        or manifest.get("book_slug") != source["book_slug"]
        or manifest.get("source_book_id") != source["book_id"]
        or str(manifest.get("source_pdf_sha256") or "").lower() != str(source["sha256"]).lower()
        or manifest.get("preparation_fingerprint") != fingerprint
        or manifest.get("candidate_pack_schema") != PACK_SCHEMA
        or manifest.get("human_review_required") is not True
        or manifest.get("publication_allowed") is not False
    ):
        raise ValueError(f"{source['book_slug']}: candidate bundle source/fingerprint/schema mismatch.")
    pack = validate_pack(out, source)
    if manifest.get("candidate_pack_sha256") != sha256_file(out / "candidates.v3.json"):
        raise ValueError(f"{source['book_slug']}: pack SHA-256 mismatch.")
    if manifest.get("summary_sha256") != sha256_file(out / "summary.v3.json"):
        raise ValueError(f"{source['book_slug']}: summary SHA-256 mismatch.")
    if manifest.get("candidate_count") != pack["summary"]["selected_candidate_count"]:
        raise ValueError(f"{source['book_slug']}: candidate count mismatch.")
    expected = manifest.get("files")
    if not isinstance(expected, dict):
        raise ValueError(f"{source['book_slug']}: manifest file list malformed.")
    actual = {}
    for path in out.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"{source['book_slug']}: extracted bundle contains symlinks.")
        if path.is_file() and path.name != manifest_path.name:
            actual[path.relative_to(out).as_posix()] = sha256_file(path)
    if set(actual) != set(expected):
        raise ValueError(f"{source['book_slug']}: bundle file list changed.")
    for rel, digest in expected.items():
        safe_path(out, rel)
        if actual.get(rel) != digest:
            raise ValueError(f"{source['book_slug']}: bundled file SHA-256 mismatch: {rel}")
    return manifest


def extract_archive(archive: Path, root: Path, slug: str) -> Path:
    out = root / slug
    shutil.rmtree(out, ignore_errors=True)
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        if not members:
            raise ValueError(f"{slug}: empty archive.")
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or not name.parts or name.parts[0] != slug:
                raise ValueError(f"{slug}: unsafe archive path {member.name!r}.")
            if member.issym() or member.islnk() or member.isdev() or member.isfifo():
                raise ValueError(f"{slug}: archive links/devices are forbidden.")
        tar.extractall(path=root, members=members, filter="data")
    if not out.is_dir():
        raise ValueError(f"{slug}: archive root folder missing.")
    return out


def missing_artifact(message: str) -> bool:
    lower = message.lower()
    # Only an explicit registry "artifact absent" response permits a rebuild.
    # Generic "not found" wording can appear in authentication/proxy/server errors,
    # which must fail closed rather than silently trigger expensive regeneration.
    return any(term in lower for term in ("404", "manifest unknown", "name_unknown"))


def pull_archive(package: str, tag: str, staging: Path) -> tuple[Path | None, str]:
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(["oras", "pull", f"{package}:{tag}", "--output", str(staging)],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    detail = (result.stdout or "").strip()
    if result.returncode:
        if missing_artifact(detail):
            return None, detail
        raise RuntimeError(f"Cannot verify/pull {package}:{tag}: {detail[-1000:]}")
    archives = list(staging.glob("*.tar.gz"))
    if len(archives) != 1:
        raise ValueError(f"{package}:{tag} should contain one tar.gz; found {len(archives)}.")
    return archives[0], detail


def validate_legacy_inventory(prior: Path, current: Path, source: dict[str, Any]) -> bool:
    if not prior.is_file() or not current.is_file():
        return False
    try:
        left, right = inventory_context(prior), inventory_context(current)
    except (ValueError, OSError, json.JSONDecodeError):
        return False
    return (
        left == right
        and right["source_book_id"] == source["book_id"]
        and right["source_pdf_sha256"] == str(source["sha256"]).lower()
    )


def create_archive(out: Path, archive: Path, slug: str) -> None:
    archive.unlink(missing_ok=True)
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(out, arcname=slug, recursive=True)


def commit_bundle_to_registry(package: str, tag: str, archive: Path, work: Path) -> None:
    subprocess.run(
        ["oras", "push", "--artifact-type", ARTIFACT_TYPE, f"{package}:{tag}", f"{archive.name}:application/gzip"],
        cwd=work, check=True,
    )


def prepare_batch(selection_path: Path, source_root: Path, output_root: Path, repo: Path, package: str, mode: str) -> None:
    selection = read_json(selection_path)
    sources = selection.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("Selected source set is empty.")
    source_root, output_root, repo = source_root.resolve(), output_root.resolve(), repo.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    completed = []
    for source in sources:
        if not isinstance(source, dict):
            raise ValueError("Malformed source-selection row.")
        slug = source.get("book_slug")
        if not isinstance(slug, str) or not slug or "/" in slug or ".." in slug:
            raise ValueError(f"Unsafe source slug {slug!r}.")
        work = source_root / slug
        pdf, manifest_path = work / "source.pdf", work / "source.manifest.json"
        page_name = source.get("text_jsonl_filename")
        if not isinstance(page_name, str) or Path(page_name).name != page_name or not page_name.endswith(".jsonl"):
            raise ValueError(f"{slug}: unsafe page JSONL filename.")
        for required in (pdf, manifest_path, work / page_name):
            if not required.is_file():
                raise ValueError(f"{slug}: required source file missing: {required}")
        if sha256_file(pdf) != str(source.get("sha256") or "").lower():
            raise ValueError(f"{slug}: source PDF SHA-256 mismatch.")

        inventory_dir = work / "inventory"
        shutil.rmtree(inventory_dir, ignore_errors=True)
        subprocess.run([
            sys.executable, "scripts/extract_visual_asset_inventory.py",
            "--pdf", str(pdf), "--manifest", str(manifest_path),
            "--output-dir", str(inventory_dir), "--preview-width", "1200",
        ], cwd=repo, check=True)
        inventory_path = inventory_dir / "visual-inventory.v1.json"
        current_inventory = read_json(inventory_path)
        inv_source = current_inventory.get("source") if isinstance(current_inventory.get("source"), dict) else {}
        if current_inventory.get("schema_version") != "quantaedge.visual-inventory.v1" or inv_source.get("book_id") != source["book_id"] or str(inv_source.get("pdf_sha256") or "").lower() != str(source["sha256"]).lower():
            raise ValueError(f"{slug}: fresh inventory does not match whole-book source.")

        fingerprint = preparation_fingerprint(source, work, inventory_path, repo)
        tag = f"candidates-v3-class{selection['class']}-{slug}-prep-{fingerprint[:24]}"
        out = output_root / slug
        archive = output_root / f"{slug}-candidates-v3.tar.gz"
        prior_index_path = repo / "data" / "textbook-visual-candidates-v3" / slug / "bundle-index.v3.json"
        prior_index = read_json(prior_index_path) if prior_index_path.is_file() else {}
        if prior_index and (prior_index.get("source_book_id") != source["book_id"] or str(prior_index.get("source_pdf_sha256") or "").lower() != str(source["sha256"]).lower()):
            prior_index = {}

        restored = False
        pulled, detail = pull_archive(package, tag, output_root / f".pull-{slug}-fingerprint")
        if pulled is not None:
            expected_tar_sha = prior_index.get("bundle_tar_sha256") if prior_index.get("version_tag") == tag else None
            actual_tar_sha = sha256_file(pulled)
            if expected_tar_sha and expected_tar_sha != actual_tar_sha:
                raise ValueError(f"{slug}: committed index archive SHA-256 differs from fingerprinted GHCR bundle.")
            shutil.copy2(pulled, archive)
            extract_archive(archive, output_root, slug)
            validate_bundle(out, source, fingerprint)
            restored = True
            print(json.dumps({"slug": slug, "checkpoint": "RESTORED_FROM_GHCR", "tag": tag, "fingerprint": fingerprint}))
        elif mode == "resume_preparation" and prior_index:
            prior_tag = prior_index.get("version_tag")
            prior_sha = prior_index.get("bundle_tar_sha256")
            prior_inventory = repo / "data" / "textbook-visuals" / slug / "visual-inventory.v1.json"
            if isinstance(prior_tag, str) and isinstance(prior_sha, str) and len(prior_sha) == 64 and validate_legacy_inventory(prior_inventory, inventory_path, source):
                old_archive, old_detail = pull_archive(package, prior_tag, output_root / f".pull-{slug}-legacy")
                if old_archive is not None:
                    if sha256_file(old_archive) != prior_sha:
                        raise ValueError(f"{slug}: legacy GHCR archive SHA-256 mismatch.")
                    shutil.copy2(old_archive, archive)
                    extract_archive(archive, output_root, slug)
                    pack = validate_pack(out, source)
                    saved_pack = repo / "data" / "textbook-visual-candidates-v3" / slug / "candidates.v3.json"
                    if not saved_pack.is_file() or sha256_file(saved_pack) != sha256_file(out / "candidates.v3.json"):
                        raise ValueError(f"{slug}: legacy pack is not byte-identical to committed pack; cannot assert resume.")
                    restored = True
                    print(json.dumps({"slug": slug, "checkpoint": "RESTORED_LEGACY_GHCR_BUNDLE", "tag": prior_tag, "fingerprint_verified": False, "candidate_count": pack["summary"]["selected_candidate_count"]}))
                elif old_detail and not missing_artifact(old_detail):
                    raise RuntimeError(f"{slug}: legacy bundle pull failed: {old_detail[-800:]}")
            if not restored:
                print(json.dumps({"slug": slug, "checkpoint": "LEGACY_NOT_REUSED", "reason": "No source-matched immutable bundle with an identical committed Layer 1 inventory and candidate pack."}))

        if not restored:
            shutil.rmtree(out, ignore_errors=True)
            subprocess.run([
                sys.executable, "scripts/prepare_visual_candidates_v3.py",
                "--inventory", str(inventory_path), "--pdf", str(pdf),
                "--output-dir", str(out), "--crop-scale", "2.0",
            ], cwd=repo, check=True)
            pack = validate_pack(out, source)
            write_bundle_manifest(out, source, fingerprint)
            create_archive(out, archive, slug)
            archive_sha = sha256_file(archive)
            commit_bundle_to_registry(package, tag, archive, output_root)
            restored = True
            print(json.dumps({"slug": slug, "checkpoint": "BUILT_AND_PUBLISHED", "tag": tag, "tar_sha256": archive_sha, "candidate_count": pack["summary"]["selected_candidate_count"], "fingerprint": fingerprint}))

        pack = validate_pack(out, source)
        bundle_manifest_path = out / "candidate-bundle-manifest.v1.json"
        is_fingerprinted = bundle_manifest_path.is_file()
        if is_fingerprinted:
            bundle_manifest = validate_bundle(out, source, fingerprint)
        else:
            bundle_manifest = None

        destination = repo / "data" / "textbook-visual-candidates-v3" / slug
        destination.mkdir(parents=True, exist_ok=True)
        for name in ("candidates.v3.json", "summary.v3.json", "contact-sheet.jpg"):
            if not (out / name).is_file():
                raise ValueError(f"{slug}: required candidate output missing: {name}")
            shutil.copy2(out / name, destination / name)
        write_json(destination / "source-selection.v1.json", source)
        chapter_map = destination / "chapter-map.v1.json"
        catalog = repo / "data" / "textbook-visual-candidates-v3" / "catalogs" / f"{slug}.v3.json"
        catalog.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            sys.executable, "scripts/build_visual_lesson_attachment_manifest.py",
            "--candidate-pack", str(out / "candidates.v3.json"),
            "--candidate-root", str(out), "--repo-root", str(repo),
            "--book-slug", slug, "--output", str(catalog),
        ]
        if chapter_map.is_file():
            cmd.extend(["--chapter-map", str(chapter_map)])
        subprocess.run(cmd, cwd=repo, check=True)

        # Reuse index SHA/tag if the deterministic artifact was just restored; for new tags,
        # actual SHA is the archive bytes which will be verified by the follow-up workflow step.
        archive_sha = sha256_file(archive)
        bundle_index = {
            "schema_version": INDEX_SCHEMA,
            "book_slug": slug,
            "class": selection["class"],
            "language": source["language"],
            "subject": source["subject"],
            "maths_part": source.get("maths_part"),
            "source_book_id": source["book_id"],
            "source_pdf_sha256": source["sha256"],
            "candidate_pack_schema": pack["schema_version"],
            "candidate_count": pack["summary"]["selected_candidate_count"],
            "candidate_pages": pack["summary"].get("selected_page_numbers", []),
            "publication_allowed": False,
            "review_required": True,
            "ghcr_package": package,
            "artifact_type": ARTIFACT_TYPE,
            "version_tag": tag if is_fingerprinted else (prior_index.get("version_tag") or tag),
            "bundle_tar_sha256": archive_sha,
            "restore_note": "Pull this exact ORAS artifact and verify bundle_tar_sha256 plus the embedded per-file manifest before reuse.",
        }
        if is_fingerprinted and bundle_manifest is not None:
            bundle_index["preparation_fingerprint"] = fingerprint
            bundle_index["candidate_pack_sha256"] = sha256_file(out / "candidates.v3.json")
            bundle_index["bundle_manifest_sha256"] = sha256_file(bundle_manifest_path)
        write_json(destination / "bundle-index.v3.json", bundle_index)
        completed.append({"slug": slug, "tag": bundle_index["version_tag"], "candidate_count": bundle_index["candidate_count"], "checkpoint": "FINGERPRINTED" if is_fingerprinted else "LEGACY"})
    print(json.dumps({"class": selection["class"], "per_book_checkpoints": completed, "publication_allowed": False}, indent=2))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--package", required=True)
    parser.add_argument("--mode", choices=("prepare_only", "analyze_all", "resume_preparation"), required=True)
    args = parser.parse_args(argv)
    try:
        prepare_batch(args.selection, args.source_root, args.output_root, args.repo_root, args.package, args.mode)
        return 0
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError, subprocess.CalledProcessError, tarfile.TarError) as exc:
        print(f"Candidate preparation stopped safely: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
