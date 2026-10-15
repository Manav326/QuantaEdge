import json
import tempfile
import unittest
from pathlib import Path

from scripts.build_chapter_map_review_candidates import build_candidate


class BuildChapterMapReviewCandidatesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.manifest_path = self.root / "book.manifest.json"
        self.pages_path = self.root / "pages.jsonl"
        self.report_path = self.root / "split-report.json"
        self.book_id = "ncert-c6-english-fegp1"
        self.source_hash = "a" * 64
        self.course_id = 8123
        self._write_inputs()

    def tearDown(self):
        self.temp.cleanup()

    def _write_inputs(self, *, course_id=None, source_hash=None, overlap=False, missing_page=False):
        source_hash = source_hash or self.source_hash
        page_count = 6
        self.manifest_path.write_text(json.dumps({
            "schema_version": 1, "scope": "SUBJECT_BOOK",
            "content_state": "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW",
            "book_id": self.book_id, "source_title": "Ganita Prakash",
            "source_url": "https://ncert.nic.in/textbook.php?fegp1=0-10",
            "edition": "Reprint 2026-27", "class": "6", "language": "english",
            "subject": "Mathematics", "page_count": page_count, "completed_pages": page_count,
            "original_book_sha256": source_hash, "asset_pdf_sha256": source_hash,
            "text_jsonl": self.pages_path.name,
        }), encoding="utf-8")
        pages = []
        for number in range(1, page_count + 1):
            if missing_page and number == page_count:
                continue
            pages.append({
                "book_id": self.book_id, "original_book_sha256": source_hash,
                "asset_pdf_sha256": source_hash, "language": "english",
                "source_book_page_number": number, "text": f"Extracted source page {number}.",
                "review_status": "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW",
            })
        self.pages_path.write_text(
            "".join(json.dumps(row) + "\n" for row in pages), encoding="utf-8"
        )
        scoped_course = self.course_id if course_id is None else course_id
        second_start = 3 if overlap else 4
        rows = [
            {
                "course_id": scoped_course, "class_code": "6", "subject_code": "maths",
                "language": "english", "scope": "SUBJECT_BOOK", "sha256": source_hash,
                "page_count": page_count, "action": "WOULD_CREATE_DRAFT",
            },
            {
                "course_id": scoped_course, "class_code": "6", "subject_code": "maths",
                "language": "english", "scope": "CHAPTER_PDF",
                "chapter_code": "patterns-in-mathematics", "chapter_id": 451,
                "chapter_name": "गणित में पैटर्न", "chapter_order": 1,
                "title": "NCERT | Class 6 | Mathematics | English | Chapter 01: Patterns in Mathematics",
                "parent_book_sha256": source_hash, "source_page_start": 2, "source_page_end": 3,
                "sha256": "b" * 64,
            },
            {
                "course_id": scoped_course, "class_code": "6", "subject_code": "maths",
                "language": "english", "scope": "CHAPTER_PDF",
                "chapter_code": "lines-and-angles", "chapter_id": 452,
                "chapter_name": "रेखाएँ और कोण", "chapter_order": 2,
                "title": "NCERT | Class 6 | Mathematics | English | Chapter 02: Lines and Angles",
                "parent_book_sha256": source_hash, "source_page_start": second_start,
                "source_page_end": 6, "sha256": "c" * 64,
            },
        ]
        self.report_path.write_text(json.dumps({
            "schema": "quantaedge.official-ncert-book-chapter-import-report.v1",
            "result": "PREFLIGHT_OK", "mode": "dry_run", "course_id": scoped_course,
            "program_code": None, "errors": [], "files": rows,
        }), encoding="utf-8")

    def test_builds_source_bound_draft_candidate_from_existing_splitter_ranges(self):
        result = build_candidate(self.manifest_path, self.report_path, self.course_id)
        self.assertEqual("quantaedge.ghcr-extraction-chapter-map-review-candidate.v1", result["schema"])
        self.assertEqual(self.course_id, result["course_id"])
        self.assertEqual(self.book_id, result["book_id"])
        self.assertEqual(self.source_hash, result["source_sha256"])
        self.assertEqual("DRAFT_REQUIRES_EDITOR_REVIEW", result["review_status"])
        self.assertTrue(result["requires_human_review"])
        self.assertFalse(result["student_delivery_allowed"])
        self.assertEqual(
            [("patterns-in-mathematics", 2, 3), ("lines-and-angles", 4, 6)],
            [(c["code"], c["page_start"], c["page_end"]) for c in result["chapters"]],
        )
        self.assertEqual("Patterns in Mathematics", result["chapters"][0]["title"])
        self.assertEqual("", result["reviewer"])
        self.assertEqual("", result["reviewed_at"])

    def test_rejects_split_report_for_different_course_id(self):
        self._write_inputs(course_id=9999)
        with self.assertRaisesRegex(ValueError, "course_id does not match"):
            build_candidate(self.manifest_path, self.report_path, self.course_id)

    def test_rejects_manifest_report_hash_mismatch(self):
        self._write_inputs(source_hash="d" * 64)
        report = json.loads(self.report_path.read_text(encoding="utf-8"))
        for row in report["files"]:
            if row["scope"] == "SUBJECT_BOOK":
                row["sha256"] = self.source_hash
            elif row["scope"] == "CHAPTER_PDF":
                row["parent_book_sha256"] = self.source_hash
        self.report_path.write_text(json.dumps(report), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "matching complete-book row"):
            build_candidate(self.manifest_path, self.report_path, self.course_id)

    def test_rejects_overlapping_chapter_ranges(self):
        self._write_inputs(overlap=True)
        with self.assertRaisesRegex(ValueError, "overlap or are out of order"):
            build_candidate(self.manifest_path, self.report_path, self.course_id)

    def test_rejects_incomplete_page_jsonl(self):
        self._write_inputs(missing_page=True)
        with self.assertRaisesRegex(ValueError, "complete contiguous book"):
            build_candidate(self.manifest_path, self.report_path, self.course_id)

    def test_rejects_failed_report(self):
        report = json.loads(self.report_path.read_text(encoding="utf-8"))
        report["result"] = "FAILED"
        self.report_path.write_text(json.dumps(report), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "PREFLIGHT_OK or COMPLETED"):
            build_candidate(self.manifest_path, self.report_path, self.course_id)

    def test_rejects_split_report_with_errors(self):
        report = json.loads(self.report_path.read_text(encoding="utf-8"))
        report["errors"] = [{"error": "chapter boundary failed"}]
        self.report_path.write_text(json.dumps(report), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "contains errors"):
            build_candidate(self.manifest_path, self.report_path, self.course_id)


if __name__ == "__main__":
    unittest.main()
