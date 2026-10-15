import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import fitz

from scripts.extract_visual_asset_inventory import build_inventory
from scripts.prepare_visual_candidates_v3 import prepare_candidates


class PrepareVisualCandidatesV3Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.pdf_path = self.root / "source.pdf"
        self.manifest_path = self.root / "source.manifest.json"
        self.inventory_dir = self.root / "inventory"
        self.output_dir = self.root / "candidate-pack"
        self.image_path = self.root / "reused-figure.png"
        self._write_source()

    def tearDown(self):
        self.temp.cleanup()

    def _write_source(self):
        pixmap = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, 300, 200), 0)
        pixmap.clear_with(175)
        pixmap.save(self.image_path)

        document = fitz.open()
        text_rows = []
        for page_number in range(1, 17):
            page = document.new_page(width=450, height=600)
            if page_number <= 2:
                page.insert_text((36, 40), f"ISBN QR code Reprint front matter page {page_number}", fontsize=11)
                page_text = f"ISBN QR code Reprint front matter page {page_number}"
            else:
                page.insert_text((36, 40), f"Chapter {page_number} Science experiment observation figure", fontsize=11)
                page.insert_text((36, 65), "Observe the diagram and explain the scientific relationship.", fontsize=10)
                page_text = f"Chapter {page_number} Science experiment observation figure. Observe the diagram and explain the scientific relationship."
            # Reuse the exact same image bytes across several pages to test content dedupe
            # while retaining distinct page/position occurrences in the candidate manifest.
            if page_number in {4, 8, 12, 16}:
                page.insert_image(fitz.Rect(60, 110, 390, 330), filename=str(self.image_path))
            text_rows.append({
                "book_id": "ncert-c6-english-fecu1",
                "source_book_page_number": page_number,
                "source_pdf_sha256": "",
                "text": page_text,
                "review_status": "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW",
                "extraction_method": "PDF_TEXT",
            })
        document.save(self.pdf_path)
        document.close()
        source_hash = hashlib.sha256(self.pdf_path.read_bytes()).hexdigest()
        for row in text_rows:
            row["source_pdf_sha256"] = source_hash
        (self.root / "pages.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in text_rows),
            encoding="utf-8",
        )
        self.manifest_path.write_text(json.dumps({
            "book_id": "ncert-c6-english-fecu1",
            "asset_pdf_sha256": source_hash,
            "original_book_sha256": source_hash,
            "page_count": 16,
            "edition": "Reprint 2026-27",
            "language": "english",
            "subject": "Science",
            "class": "6",
            "text_jsonl": "pages.jsonl",
        }), encoding="utf-8")
        self.inventory = build_inventory(
            self.pdf_path, self.manifest_path, self.inventory_dir,
            render_page_previews=True, preview_width=600,
        )

    def test_accepts_source_pdf_page_number_alias_for_existing_jsonl(self):
        rows = [
            json.loads(line)
            for line in (self.root / "pages.jsonl").read_text(encoding="utf-8").splitlines()
        ]
        for row in rows:
            row["source_pdf_page_number"] = row.pop("source_book_page_number")
        (self.root / "pages.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8",
        )
        alias_inventory_dir = self.root / "inventory-pdf-page-alias"
        inventory = build_inventory(
            self.pdf_path, self.manifest_path, alias_inventory_dir,
            render_page_previews=False,
        )
        self.assertEqual("existing_text_jsonl", inventory["text_context_source"])
        self.assertEqual(16, inventory["source"]["pdf_page_count"])

    def test_prepares_source_bound_candidates_across_page_strata(self):
        pack = prepare_candidates(
            self.inventory_dir / "visual-inventory.v1.json",
            self.pdf_path,
            self.output_dir,
            max_candidates=10,
            crop_scale=1.2,
        )
        self.assertEqual("quantaedge.visual-candidate-pack.v3", pack["schema_version"])
        self.assertFalse(pack["summary"]["publication_allowed"])
        self.assertEqual(10, pack["summary"]["selected_candidate_count"])
        self.assertGreaterEqual(pack["summary"]["distinct_selected_pages"], 7)
        page_numbers = pack["summary"]["selected_page_numbers"]
        self.assertLessEqual(min(page_numbers), 4)
        self.assertGreaterEqual(max(page_numbers), 13)
        self.assertEqual(
            hashlib.sha256(self.pdf_path.read_bytes()).hexdigest(),
            pack["source"]["pdf_sha256"],
        )
        self.assertTrue((self.output_dir / "candidates.v3.json").is_file())
        self.assertTrue((self.output_dir / "summary.v3.json").is_file())
        self.assertTrue((self.output_dir / "contact-sheet.pdf").is_file())
        self.assertTrue((self.output_dir / "contact-sheet.jpg").is_file())
        self.assertTrue((self.output_dir / "review-index.html").is_file())

        for candidate in pack["candidates"]:
            self.assertEqual("CANDIDATE_NEEDS_HUMAN_REVIEW", candidate["review_status"])
            self.assertFalse(candidate["publication_allowed"])
            self.assertTrue(candidate["source_pdf_sha256"])
            self.assertTrue((self.output_dir / candidate["display_path"]).is_file())
            self.assertTrue((self.output_dir / candidate["source_page_preview_path"]).is_file())

    def test_deduplicates_identical_image_bytes_but_keeps_all_occurrences(self):
        pack = prepare_candidates(
            self.inventory_dir / "visual-inventory.v1.json",
            self.pdf_path,
            self.output_dir,
            max_candidates=12,
            crop_scale=1.2,
        )
        embedded = [row for row in pack["candidates"] if row["candidate_kind"] == "embedded_image"]
        self.assertEqual(1, len(embedded), "the same image bytes should make one processing candidate")
        self.assertGreaterEqual(len(embedded[0]["occurrences"]), 4)
        self.assertEqual(
            {4, 8, 12, 16},
            {row["source_pdf_page_number"] for row in embedded[0]["occurrences"]},
        )
        self.assertTrue((self.output_dir / embedded[0]["original_asset_path"]).is_file())
        self.assertTrue(embedded[0]["original_asset_sha256"])
        self.assertTrue(embedded[0]["page_crop_path"])
        self.assertTrue((self.output_dir / embedded[0]["page_crop_path"]).is_file())

    def test_rejects_pdf_hash_mismatch_without_preparing_output(self):
        changed_pdf = self.root / "changed.pdf"
        changed_pdf.write_bytes(self.pdf_path.read_bytes() + b"mismatched")
        with self.assertRaisesRegex(ValueError, "SHA-256 does not match"):
            prepare_candidates(
                self.inventory_dir / "visual-inventory.v1.json",
                changed_pdf,
                self.output_dir,
                max_candidates=5,
            )
        self.assertFalse(self.output_dir.exists())

    def test_rejects_corrupted_inventory_asset_hash(self):
        inventory_path = self.inventory_dir / "visual-inventory.v1.json"
        inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
        page_preview = next(row for row in inventory["assets"] if row.get("kind") == "page_preview")
        page_preview["asset_sha256"] = "0" * 64
        inventory_path.write_text(json.dumps(inventory), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "Page preview SHA-256 mismatch"):
            prepare_candidates(
                inventory_path, self.pdf_path, self.output_dir, max_candidates=5,
            )

    def test_rejects_nonempty_output_directory(self):
        self.output_dir.mkdir(parents=True)
        marker = self.output_dir / "keep-me.txt"
        marker.write_text("do not overwrite", encoding="utf-8")
        with self.assertRaisesRegex(FileExistsError, "not empty"):
            prepare_candidates(
                self.inventory_dir / "visual-inventory.v1.json",
                self.pdf_path,
                self.output_dir,
                max_candidates=5,
            )
        self.assertEqual("do not overwrite", marker.read_text(encoding="utf-8"))

    def test_rejects_candidate_limit_outside_safe_range(self):
        with self.assertRaisesRegex(ValueError, "between 1 and 200"):
            prepare_candidates(
                self.inventory_dir / "visual-inventory.v1.json",
                self.pdf_path,
                self.output_dir,
                max_candidates=201,
            )


if __name__ == "__main__":
    unittest.main()
