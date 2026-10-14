import csv
import json
import tempfile
import unittest
from pathlib import Path

from scripts.build_extraction_bundle_from_ghcr_pages import build_bundle


class BuildExtractionBundleFromGhcrPagesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.book_id = "ncert-c6-english-fegp1"
        self.source_hash = "a" * 64
        self.manifest_path = self.root / "book.manifest.json"
        self.pages_path = self.root / "book.jsonl"
        self.map_path = self.root / "reviewed-chapter-map.json"
        self.targets_path = self.root / "targets.csv"
        self._write_fixture()

    def tearDown(self):
        self.temp.cleanup()

    def _write_fixture(self, bad_page_hash=False, missing_page=False):
        manifest = {
            "scope": "SUBJECT_BOOK",
            "content_state": "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW",
            "book_id": self.book_id,
            "source_title": "Ganita Prakash",
            "source_url": "https://ncert.nic.in/textbook.php?fegp1=0-12",
            "edition": "Reprint 2026-27",
            "class": "6",
            "language": "english",
            "subject": "Mathematics",
            "page_count": 4,
            "completed_pages": 4,
            "original_book_sha256": self.source_hash,
            "asset_pdf_sha256": self.source_hash,
            "asset_pdf_filename": "book.pdf",
            "text_jsonl": self.pages_path.name,
        }
        self.manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        statuses = {
            1: "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW",
            2: "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW",
            3: "LOW_TEXT_REVIEW",
            4: "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW",
        }
        texts = {
            1: "Printed edition Reprint 2026-27.",
            2: "Patterns and numbers explained in this mathematics chapter.",
            3: "Short or incomplete OCR text.",
            4: "Whole numbers and their properties in mathematics.",
        }
        rows = []
        for number, text_value in texts.items():
            if missing_page and number == 4:
                continue
            rows.append({
                "book_id": self.book_id,
                "original_book_sha256": "b" * 64 if bad_page_hash and number == 2 else self.source_hash,
                "language": "english",
                "subject": "Mathematics",
                "source_book_page_number": number,
                "text": text_value,
                "review_status": statuses[number],
            })
        self.pages_path.write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8",
        )
        map_data = {
            "book_id": self.book_id,
            "source_sha256": self.source_hash,
            "review_status": "EDITOR_REVIEWED",
            "reviewer": "Content Editor",
            "reviewed_at": "2026-10-14",
            "class_code": "6",
            "subject_code": "maths",
            "chapters": [
                {"code": "knowing-numbers", "title": "Patterns and numbers", "page_start": 2, "page_end": 3},
                {"code": "whole-numbers", "title": "Whole numbers", "page_start": 4, "page_end": 4},
            ],
        }
        self.map_path.write_text(json.dumps(map_data, ensure_ascii=False), encoding="utf-8")
        with self.targets_path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=["class_code", "subject_code", "chapter_code", "chapter_title"])
            writer.writeheader()
            writer.writerows([
                {"class_code": "6", "subject_code": "maths", "chapter_code": "knowing-numbers", "chapter_title": "Knowing numbers"},
                {"class_code": "6", "subject_code": "maths", "chapter_code": "whole-numbers", "chapter_title": "Whole numbers"},
            ])

    def test_builds_existing_question_pipeline_input_and_blocks_only_low_quality_chapter(self):
        bundle = build_bundle(self.manifest_path, self.map_path, self.targets_path)
        self.assertEqual("DRAFT_EXTRACTION_ONLY", bundle["bundle_status"])
        self.assertEqual("6", bundle["curriculum"]["class_code"])
        self.assertEqual("maths", bundle["curriculum"]["subject_code"])
        self.assertEqual("en", bundle["curriculum"]["language"])
        self.assertEqual(["whole-numbers"], [chapter["code"] for chapter in bundle["chapter_map"]])
        blocked = bundle["extraction"]["quality_blocked_chapters"][0]
        self.assertEqual([3], [page["page_number"] for page in blocked["blocked_pages"]])
        self.assertIsNone(bundle["pages"][1]["chapter_code"])
        self.assertIsNone(bundle["pages"][2]["chapter_code"])
        self.assertEqual("whole-numbers", bundle["pages"][3]["chapter_code"])
        self.assertFalse(bundle["extraction"]["student_delivery_allowed"])

    def test_rejects_page_identity_or_hash_mismatch(self):
        self._write_fixture(bad_page_hash=True)
        with self.assertRaisesRegex(ValueError, "Inconsistent"):
            build_bundle(self.manifest_path, self.map_path, self.targets_path)

    def test_rejects_missing_page_in_full_book_jsonl(self):
        self._write_fixture(missing_page=True)
        with self.assertRaisesRegex(ValueError, "complete contiguous book"):
            build_bundle(self.manifest_path, self.map_path, self.targets_path)

    def test_rejects_unreviewed_chapter_map(self):
        data = json.loads(self.map_path.read_text(encoding="utf-8"))
        data["review_status"] = "DRAFT_REQUIRES_REVIEW"
        self.map_path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "EDITOR_REVIEWED"):
            build_bundle(self.manifest_path, self.map_path, self.targets_path)

    def test_rejects_map_bound_to_another_book_hash(self):
        data = json.loads(self.map_path.read_text(encoding="utf-8"))
        data["source_sha256"] = "c" * 64
        self.map_path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source_sha256"):
            build_bundle(self.manifest_path, self.map_path, self.targets_path)

    def test_rejects_noncanonical_chapter_code_and_overlapping_ranges(self):
        data = json.loads(self.map_path.read_text(encoding="utf-8"))
        data["chapters"][0]["code"] = "not-a-canonical-chapter"
        self.map_path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "not a canonical"):
            build_bundle(self.manifest_path, self.map_path, self.targets_path)
        data["chapters"][0]["code"] = "knowing-numbers"
        data["chapters"][0]["page_start"] = 2
        data["chapters"][0]["page_end"] = 4
        self.map_path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "overlap"):
            build_bundle(self.manifest_path, self.map_path, self.targets_path)


if __name__ == "__main__":
    unittest.main()
