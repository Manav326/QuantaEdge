import hashlib
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

from scripts.prepare_visual_candidate_batch import (
    MANIFEST_SCHEMA, extract_archive, inventory_context, validate_bundle, validate_legacy_inventory,
    validate_pack, write_bundle_manifest,
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
