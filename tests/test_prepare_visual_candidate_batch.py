import hashlib
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

from scripts.prepare_visual_candidate_batch import (
    MANIFEST_SCHEMA, extract_archive, inventory_context, missing_artifact, validate_bundle,
    validate_layer1_inventory, validate_legacy_inventory, validate_pack, write_bundle_manifest,
)


class CandidateBundleCheckpointTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = {"book_id": "book-c6-science", "sha256": "a" * 64, "book_slug": "class6-en-science"}
        self.out = self.root / self.source["book_slug"]
        self.out.mkdir()
        self._make_pack()

    def _make_pack(self):
        (self.out / "display.jpg").write_bytes(b"display")
        (self.out / "page.jpg").write_bytes(b"page")
        pack = {
            "schema_version": "quantaedge.visual-candidate-pack.v3",
            "source": {"book_id": self.source["book_id"], "pdf_sha256": self.source["sha256"]},
            "summary": {"selected_candidate_count": 1, "selected_page_numbers": [1], "publication_allowed": False},
            "candidates": [{
                "candidate_id": "candidate-1", "display_path": "display.jpg",
                "source_page_preview_path": "page.jpg", "source_pdf_page_number": 1,
            }],
        }
        (self.out / "candidates.v3.json").write_text(json.dumps(pack), encoding="utf-8")
        (self.out / "summary.v3.json").write_text(json.dumps(pack["summary"]), encoding="utf-8")
        (self.out / "contact-sheet.jpg").write_bytes(b"contact-sheet")

    def test_bundle_manifest_binds_source_and_fingerprint(self):
        manifest = write_bundle_manifest(self.out, self.source, "f" * 64)
        self.assertEqual(MANIFEST_SCHEMA, manifest["schema_version"])
        self.assertEqual(1, validate_bundle(self.out, self.source, "f" * 64)["candidate_count"])
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            validate_bundle(self.out, self.source, "e" * 64)

    def test_manifest_rejects_changed_asset(self):
        write_bundle_manifest(self.out, self.source, "f" * 64)
        (self.out / "display.jpg").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            validate_bundle(self.out, self.source, "f" * 64)

    def test_manifest_rejects_unlisted_files(self):
        write_bundle_manifest(self.out, self.source, "f" * 64)
        (self.out / "extra.bin").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "file list changed"):
            validate_bundle(self.out, self.source, "f" * 64)

    def test_extract_rejects_path_traversal(self):
        archive = self.root / "malicious.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            item = self.root / "payload.txt"
            item.write_text("unsafe", encoding="utf-8")
            tar.add(item, arcname="../escape.txt")
        with self.assertRaisesRegex(ValueError, "unsafe archive path"):
            extract_archive(archive, self.root / "unpacked", self.source["book_slug"])

    def _layer1_fixture(self):
        work = self.root / "source-work"
        inventory_dir = work / "inventory"
        inventory_dir.mkdir(parents=True)
        page_text = "Water cycle"
        (work / "pages.jsonl").write_text(json.dumps({"source_book_page_number": 1, "text": page_text}) + "\n", encoding="utf-8")
        (work / "source.manifest.json").write_text(json.dumps({"page_count": 1}), encoding="utf-8")
        (inventory_dir / "page-0001.jpg").write_bytes(b"preview")
        context_hash = hashlib.sha256(page_text.encode("utf-8")).hexdigest()
        inventory = {
            "schema_version": "quantaedge.visual-inventory.v1",
            "source": {"book_id": self.source["book_id"], "pdf_sha256": self.source["sha256"], "pdf_page_count": 1},
            "pages": [{"source_pdf_page_number": 1, "page_text_sha256": context_hash}],
            "assets": [{"asset_id": "page-1", "kind": "page_preview", "source_pdf_page_number": 1,
                        "page_text_sha256": context_hash, "asset_path": "page-0001.jpg",
                        "asset_sha256": hashlib.sha256(b"preview").hexdigest()}],
        }
        (inventory_dir / "visual-inventory.v1.json").write_text(json.dumps(inventory), encoding="utf-8")
        return work, dict(self.source, text_jsonl_filename="pages.jsonl"), inventory_dir

    def test_layer1_inventory_validates_source_context_and_assets(self):
        work, source, inventory_dir = self._layer1_fixture()
        self.assertEqual("quantaedge.visual-inventory.v1", validate_layer1_inventory(work, source, inventory_dir)["schema_version"])
        path = inventory_dir / "visual-inventory.v1.json"
        inventory = json.loads(path.read_text(encoding="utf-8"))
        inventory["assets"][0]["page_text_sha256"] = hashlib.sha256(b"stale").hexdigest()
        path.write_text(json.dumps(inventory), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "stale/unverified text context"):
            validate_layer1_inventory(work, source, inventory_dir)

    def test_only_explicit_registry_missing_responses_allow_rebuild(self):
        self.assertTrue(missing_artifact("Error: HTTP 404 manifest not found"))
        self.assertTrue(missing_artifact("MANIFEST_UNKNOWN: registry has no matching artifact"))
        reference = "ghcr.io/manav326/quantaedge-textbook-visuals:visual-inventory-v1-class6-class6-en-science-test"
        self.assertTrue(missing_artifact(
            f"Error response from registry: failed to resolve {reference}: {reference}: not found",
            reference,
        ))
        self.assertFalse(missing_artifact(f"failed to resolve {reference}: HTTP 401: access denied", reference))
        self.assertFalse(missing_artifact(f"upstream proxy says {reference} not found", reference))
        self.assertFalse(missing_artifact("HTTP 500: upstream resource not found"))
        self.assertFalse(missing_artifact("HTTP 401: access denied"))

    def test_legacy_reuse_rejects_different_pdf_edition(self):
        prior = self.root / "prior.json"
        current = self.root / "current.json"
        template = {
            "schema_version": "quantaedge.visual-inventory.v1",
            "source": {"book_id": self.source["book_id"], "pdf_sha256": self.source["sha256"], "pdf_page_count": 1},
            "pages": [], "assets": [],
        }
        prior.write_text(json.dumps(template), encoding="utf-8")
        new_inventory = dict(template)
        new_inventory["source"] = dict(template["source"], pdf_sha256="d" * 64)
        current.write_text(json.dumps(new_inventory), encoding="utf-8")
        self.assertFalse(validate_legacy_inventory(prior, current, dict(self.source, sha256="d" * 64)))

    def test_legacy_reuse_requires_exact_layer1_context(self):
        prior = self.root / "prior.json"
        current = self.root / "current.json"
        inventory = {
            "schema_version": "quantaedge.visual-inventory.v1",
            "source": {"book_id": self.source["book_id"], "pdf_sha256": self.source["sha256"], "pdf_page_count": 1},
            "pages": [{"source_pdf_page_number": 1, "page_text_sha256": hashlib.sha256(b"text").hexdigest()}],
            "assets": [{"asset_id": "asset-1", "kind": "page_preview", "asset_sha256": "b" * 64,
                        "page_text_sha256": hashlib.sha256(b"text").hexdigest(), "page_text_context": "text"}],
        }
        prior.write_text(json.dumps(inventory), encoding="utf-8")
        current.write_text(json.dumps(inventory), encoding="utf-8")
        self.assertTrue(validate_legacy_inventory(prior, current, self.source))
        inventory["pages"][0]["page_text_sha256"] = hashlib.sha256(b"changed").hexdigest()
        current.write_text(json.dumps(inventory), encoding="utf-8")
        self.assertFalse(validate_legacy_inventory(prior, current, self.source))


if __name__ == "__main__":
    unittest.main()
