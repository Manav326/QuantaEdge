import hashlib
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from subprocess import CompletedProcess

from scripts.textbook_registry import (
    MAX_BOOK_BYTES,
    MAX_ZIP_BYTES,
    _extract_oci_cache_rootfs,
    download_ncert_merged,
    _is_complete_whole_book_entry,
    host_allowed,
    parse_ncert_catalog,
    parse_scert_classes,
    parse_scert_medium,
    publish_language,
    push_batch,
    class_image_reference,
    _entry_class_numbers,
    _seed_class_image_from_legacy,
)

NCERT_FIXTURE = r"""
<script>
function change()
{
    //this function check the classthat you have selected
    if (document.test.tclass.value==6)
    {
        document.test.tsubject.options[0].text="..Select Subject..";
        document.test.tsubject.options[1].text="Mathematics";
    }
}
function change1(sind)
{
    document.test.tbook.options[0].text="..Select Book Title..";
    if((document.test.tclass.value==6) && (document.test.tsubject.options[sind].text=="Mathematics"))
    {
        document.test.tbook.options[1].text="गणित प्रकाश";
        document.test.tbook.options[1].value="textbook.php?fhgp1=0-14"
        document.test.tbook.options[2].text="Ganita Prakash";
        document.test.tbook.options[2].value="textbook.php?fegp1=0-14"
    }
}
</script>
"""


class TextbookRegistryTests(unittest.TestCase):
    def test_official_host_gate_requires_https(self):
        self.assertTrue(host_allowed("https://ncert.nic.in/textbook.php"))
        self.assertTrue(host_allowed("https://scert.bihar.gov.in/eresources"))
        self.assertFalse(host_allowed("http://ncert.nic.in/textbook.php"))
        self.assertFalse(host_allowed("https://untrusted.example/book.pdf"))
        self.assertFalse(host_allowed("https://user:pass@ncert.nic.in/book.pdf"))

    def test_iri_to_uri_percent_encodes_devanagari_paths(self):
        from scripts.textbook_registry import iri_to_uri
        encoded = iri_to_uri("https://scert.bihar.gov.in/eresources/इतिहास-की-दुनिया-1708424151")
        self.assertIn("%E0%A4", encoded)
        self.assertNotIn("इतिहास", encoded)
        self.assertTrue(encoded.startswith("https://scert.bihar.gov.in/eresources/"))

    def test_ncert_catalogue_splits_english_and_hindi(self):
        books = parse_ncert_catalog(NCERT_FIXTURE)
        self.assertEqual(2, len(books))
        by_medium = {item["medium"]: item for item in books}
        self.assertEqual("fhgp1", by_medium["hindi"]["code"])
        self.assertEqual("fegp1", by_medium["english"]["code"])
        self.assertEqual(6, int(by_medium["english"]["class"]))
        self.assertEqual(14, by_medium["english"]["chapter_count"])

    def test_scert_detail_parser_handles_real_html_labels_and_absolute_pdf_link(self):
        from scripts.textbook_registry import parse_scert_detail

        detail_url = "https://scert.bihar.gov.in/eresources/विज्ञान-भाग-1-1707973674"
        html_page = """
        <html><body>
          <h4>विज्ञान, भाग-1</h4>
          <div><strong>Class :</strong> CLASS VI,</div>
          <div><strong>Subject :</strong> भाग-1, ,विज्ञान,</div>
          <div><strong>language :</strong> Hindi,</div>
          <div><strong>Publisher :</strong> Bihar Education Project Council</div>
          <a href="/public/uploads/eresources/vigyan-bhag-1.pdf">Download</a>
        </body></html>
        """
        items = parse_scert_detail(detail_url, html_page)

        self.assertEqual(1, len(items))
        item = items[0]
        self.assertEqual("scert-bihar-1707973674-hindi", item["book_id"])
        self.assertEqual([6], item["classes"])
        self.assertEqual("hindi", item["medium"])
        self.assertIn("विज्ञान", item["subject"])
        self.assertEqual("https://scert.bihar.gov.in/public/uploads/eresources/vigyan-bhag-1.pdf", item["pdf_url"])

    def test_scert_single_bilingual_pdf_is_blocked_for_both_isolated_medium_registries(self):
        from scripts.textbook_registry import _download_full_book, parse_scert_detail

        html_page = """
        <html><body>
          <h3>Mathematics and Maths</h3>
          <p>Class : CLASS VI, CLASS VII, CLASS VIII,</p>
          <p>Subject : Math</p>
          <p>Language : Hindi English</p>
          <p>Publisher : SCERT Bihar</p>
          <a href="https://scert.bihar.gov.in/public/uploads/eresources/math.pdf">PDF</a>
        </body></html>
        """
        items = parse_scert_detail("https://scert.bihar.gov.in/eresources/math-1700000000", html_page)
        self.assertEqual(["english", "hindi"], sorted(item["medium"] for item in items))
        for item in items:
            self.assertEqual("unavailable", item["content_availability"]["status"])
            self.assertIn("Separate language-specific whole-book PDFs", item["content_availability"]["note"])
            with tempfile.TemporaryDirectory() as temp:
                with self.assertRaisesRegex(ValueError, "Separate language-specific whole-book PDFs"):
                    _download_full_book(item, Path(temp) / "must-not-download.pdf")
                self.assertFalse((Path(temp) / "must-not-download.pdf").exists())

    def test_scert_pdf_text_must_match_declared_medium(self):
        import fitz
        from scripts.textbook_registry import download_scert_pdf

        document = fitz.open()
        page = document.new_page()
        page.insert_text((72, 72), "This is a sufficiently long English textbook passage explaining science concepts and examples for students.")
        payload = document.tobytes()
        document.close()
        book = {
            "title": "Example Science", "subject": "Science", "medium": "hindi",
            "pdf_url": "https://scert.bihar.gov.in/public/uploads/example.pdf",
        }
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "wrong-medium.pdf"
            def fake_get_url(url, target=None, limit=None, attempts=1):
                target.write_bytes(payload)
                return target
            with patch("scripts.textbook_registry.get_url", side_effect=fake_get_url):
                with self.assertRaisesRegex(ValueError, "failed its declared-medium text check"):
                    download_scert_pdf(book, destination)
            self.assertFalse(destination.exists())
            self.assertEqual("unavailable", book["content_availability"]["status"])

    def test_scert_pdf_without_printed_edition_is_rejected(self):
        import fitz
        from scripts.textbook_registry import download_scert_pdf

        document = fitz.open()
        page = document.new_page()
        page.insert_text((72, 72), "This is a complete English science lesson with examples, definitions, practice exercises, and explanations for students.")
        payload = document.tobytes()
        document.close()
        book = {
            "title": "Example Science", "subject": "Science", "medium": "english",
            "pdf_url": "https://scert.bihar.gov.in/public/uploads/example.pdf",
        }
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "no-edition.pdf"

            def fake_get_url(url, target=None, limit=None, attempts=1):
                target.write_bytes(payload)
                return target

            with patch("scripts.textbook_registry.get_url", side_effect=fake_get_url):
                with self.assertRaisesRegex(ValueError, "dated printed edition/reprint imprint"):
                    download_scert_pdf(book, destination)
            self.assertFalse(destination.exists())
            self.assertEqual("unavailable", book["content_availability"]["status"])

    def test_scert_class_detection_handles_roman_and_numeric(self):
        self.assertEqual([6, 7, 8], parse_scert_classes("Class : CLASS VI, ,CLASS VII, ,CLASS VIII,"))
        self.assertEqual([9, 10, 11, 12], parse_scert_classes("Class IX, Class X, Class 11, Class XII"))

    def test_scert_language_detection_handles_bilingual_books(self):
        self.assertEqual("hindi", parse_scert_medium("language : Hindi"))
        self.assertEqual("english", parse_scert_medium("language : English"))
        self.assertEqual("both", parse_scert_medium("language : Hindi English"))
        self.assertIsNone(parse_scert_medium("language : Urdu"))



    def test_large_book_url_uses_one_attempt_when_requested_for_a_retry_round(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "book.pdf"
            with patch("scripts.textbook_registry._get_url_once", side_effect=RuntimeError("source unavailable")) as get_once:
                from scripts.textbook_registry import get_url
                with self.assertRaisesRegex(RuntimeError, "Failed after 1 attempt"):
                    get_url("https://ncert.nic.in/textbook/pdf/bookdd.zip", destination, None, attempts=1)
            self.assertEqual(1, get_once.call_count)


    def test_class_image_reference_is_stable_and_separated_by_grade_and_medium(self):
        prefix = "ghcr.io/manav326/quantaedge-textbooks"
        self.assertEqual("ghcr.io/manav326/quantaedge-textbooks-class-6-hindi:latest",
                         class_image_reference(prefix, 6, "hindi"))
        self.assertEqual("ghcr.io/manav326/quantaedge-textbooks-class-12-english:latest",
                         class_image_reference(prefix, 12, "english"))
        self.assertEqual([6, 8], _entry_class_numbers({"classes": ["6", "8"]}))
        self.assertEqual([7], _entry_class_numbers({"class": "VII"}))

    def test_class_filter_does_not_download_books_for_other_grades(self):
        def book(book_id, grade):
            return {
                "book_id": book_id, "code": book_id, "publisher": "SCERT Bihar",
                "source_type": "SCERT_BIHAR", "class": grade, "classes": [grade],
                "medium": "hindi", "language": "Hindi", "title": "Book " + book_id,
                "subject": "Science", "edition": "2025", "source_url": "https://scert.bihar.gov.in/eresources",
                "catalog_entry_url": "https://scert.bihar.gov.in/eresources",
                "pdf_url": "https://scert.bihar.gov.in/public/uploads/book.pdf",
                "bundle_url": "https://scert.bihar.gov.in/public/uploads/book.pdf", "chapter_count": None,
            }
        class_six, class_seven = book("book-six", 6), book("book-seven", 7)
        attempted = []

        def fake_download(item, destination):
            attempted.append(item["book_id"])
            destination.parent.mkdir(parents=True, exist_ok=True)
            data = ("%PDF-1.4\n" + item["book_id"] + "\n").encode()
            destination.write_bytes(data)
            return hashlib.sha256(data).hexdigest(), 1, [[1, "Chapter 1", 1]], "whole-book fixture", destination

        with patch("scripts.textbook_registry.pull_index", return_value=(False, {
            "schema_version": 1, "books": [], "download_status": {},
        })), patch("scripts.textbook_registry._download_full_book", side_effect=fake_download), \
             patch("scripts.textbook_registry.push_batch"):
            report = publish_language(
                "hindi", "ghcr.io/example/quantaedge-textbooks-class-6-hindi:latest",
                catalog=[class_six, class_seven], class_no=6,
                download_workers=1, push_batch_size=1, retry_rounds=1,
            )

        self.assertEqual(["book-six"], attempted)
        self.assertEqual(1, report["catalog_entries"])
        self.assertEqual(["book-six"], report["cached_book_ids"])

    def test_existing_legacy_book_is_migrated_without_a_source_download_and_checkpointed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legacy_root = root / "legacy-hindi"
            (legacy_root / "books").mkdir(parents=True)
            payload = b"%PDF-1.4\nalready cached in old image\n"
            (legacy_root / "books" / "old-book.pdf").write_bytes(payload)
            entry = {
                "book_id": "old-book", "file": "books/old-book.pdf", "class": "6",
                "classes": ["6"], "medium": "hindi", "publisher": "NCERT",
                "source_type": "NCERT", "title": "Existing Class Six", "subject": "Mathematics",
                "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest(),
            }
            legacy_index = {"books": [entry], "download_status": {}}
            target_index = {
                "schema_version": 1, "books": [{**entry, "classes": ["6"], "registry_class": 6}],
                "download_status": {}, "legacy_migration_complete": True,
            }
            with patch("scripts.textbook_registry.pull_index", side_effect=[
                (False, {"schema_version": 1, "books": [], "download_status": {}}),
                (True, target_index),
            ]), patch("scripts.textbook_registry.push_batch") as push, \
                 patch("scripts.textbook_registry._extract_image_books") as extract:
                def loader(language):
                    self.assertEqual("hindi", language)
                    return legacy_index, legacy_root
                first = _seed_class_image_from_legacy(
                    "ghcr.io/example/quantaedge-textbooks", 6, "hindi", loader, root)
                second = _seed_class_image_from_legacy(
                    "ghcr.io/example/quantaedge-textbooks", 6, "hindi",
                    lambda _language: self.fail("completed migration must not reload legacy"), root)

            self.assertEqual(1, first["migrated_from_legacy"])
            self.assertEqual(0, second["migrated_from_legacy"])
            self.assertTrue(second["migration_skipped"])
            self.assertEqual(1, push.call_count)
            extract.assert_not_called()

    def test_textbook_binary_size_is_not_artificially_capped(self):
        self.assertIsNone(MAX_BOOK_BYTES)
        self.assertIsNone(MAX_ZIP_BYTES)

    def test_detect_printed_edition_uses_only_a_dated_imprint(self):
        from scripts.textbook_registry import detect_printed_edition
        self.assertIn("Reprint 2025-26", detect_printed_edition(
            "NCERT\nReprint 2025-26, Phalguna 1947\nMathematics textbook"
        ))
        self.assertIsNone(detect_printed_edition(
            "Current official catalogue; retain printed reprint year from the source PDF"
        ))

    def test_ncert_pdf_text_must_match_declared_medium(self):
        import io
        import zipfile
        import fitz
        from scripts.textbook_registry import download_ncert_merged

        def make_pdf(label):
            document = fitz.open()
            page = document.new_page()
            page.insert_text((72, 72), label)
            payload = document.tobytes()
            document.close()
            return payload

        bundle = io.BytesIO()
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("fegp101.pdf", make_pdf(
                "Chapter one is an English mathematics passage with enough readable words to check medium."
            ))
            archive.writestr("fegp102.pdf", make_pdf(
                "Chapter two is an English mathematics passage with examples and worked exercises for students."
            ))
        requested = []

        def fake_get_url(url, destination=None, limit=None, attempts=4):
            requested.append(url)
            destination.write_bytes(bundle.getvalue())
            return destination

        book = {
            "book_id": "ncert-c6-hindi-fegp1", "code": "fegp1", "chapter_count": 2,
            "title": "Ganita Prakash", "medium": "hindi", "source_type": "NCERT",
            "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
        }
        with tempfile.TemporaryDirectory() as temp, patch(
            "scripts.textbook_registry.get_url", side_effect=fake_get_url
        ):
            destination = Path(temp) / "wrong-medium.pdf"
            with self.assertRaisesRegex(RuntimeError, "complete official NCERT whole-book bundle"):
                download_ncert_merged(book, destination)
            self.assertFalse(destination.exists())
        self.assertTrue(all(url.endswith(".zip") for url in requested))
        self.assertEqual("unavailable", book["content_availability"]["status"])

    def test_complete_ncert_bundle_merges_all_chapters_without_individual_downloads(self):
        import io
        import zipfile
        import fitz
        from scripts.textbook_registry import download_ncert_merged

        def make_pdf(label):
            document = fitz.open()
            page = document.new_page()
            page.insert_text((72, 72), label)
            data = document.tobytes()
            document.close()
            return data

        bundle = io.BytesIO()
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("fegp1ps.pdf", make_pdf("Book preliminaries. Printed edition 2024-25. NCERT mathematics textbook in English."))
            archive.writestr("fegp101.pdf", make_pdf("Chapter 1 explains important mathematics concepts in English with examples for learners."))
            archive.writestr("fegp102.pdf", make_pdf("Chapter 2 continues the English mathematics lesson with guided practice and examples."))
        requested = []

        def fake_get_url(url, destination=None, limit=None, attempts=4):
            requested.append(url)
            destination.write_bytes(bundle.getvalue())
            return destination

        book = {
            "book_id": "ncert-c6-english-fegp1", "code": "fegp1", "chapter_count": 2,
            "title": "Ganita Prakash", "medium": "english",
            "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
        }
        with tempfile.TemporaryDirectory() as temp, patch(
            "scripts.textbook_registry.get_url", side_effect=fake_get_url
        ):
            destination = Path(temp) / "whole-book.pdf"
            digest, pages, toc, method = download_ncert_merged(book, destination)
            self.assertTrue(destination.is_file())
            self.assertTrue(digest)
            self.assertEqual("complete", book["content_availability"]["status"])
            self.assertIn("Printed edition 2024-25", book["edition"])
            self.assertEqual([1, 2], book["content_availability"]["available_chapters"])
            self.assertEqual([], book["content_availability"]["missing_chapters"])
            self.assertIn("all expected chapters validated", method.lower())
            with fitz.open(destination) as merged:
                self.assertEqual(3, len(merged))
            self.assertEqual(2, len(toc))

        self.assertEqual(1, len(requested))
        self.assertTrue(requested[0].endswith("fegp1dd.zip"))

    def test_complete_ncert_bundle_accepts_legacy_nonconsecutive_suffixes_by_count(self):
        import io
        import zipfile
        import fitz
        from scripts.textbook_registry import download_ncert_merged

        def make_pdf(label):
            document = fitz.open()
            page = document.new_page()
            page.insert_text((72, 72), label)
            result = document.tobytes()
            document.close()
            return result

        bundle = io.BytesIO()
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("fegp1ps.pdf", make_pdf("NCERT mathematics textbook in English. Printed edition 2024-25."))
            archive.writestr("fegp101.pdf", make_pdf("Chapter one is an English mathematics section with a sufficiently long text passage for language verification."))
            archive.writestr("fegp111.pdf", make_pdf("Chapter two is an English mathematics section with detailed explanation and worked examples."))
            archive.writestr("fegp131.pdf", make_pdf("Chapter three is an English mathematics section with review activities for students."))
        requested = []

        def fake_get_url(url, destination=None, limit=None, attempts=4):
            requested.append(url)
            destination.write_bytes(bundle.getvalue())
            return destination

        book = {
            "book_id": "ncert-c6-english-fegp1", "code": "fegp1", "chapter_count": 3,
            "title": "Example current edition", "medium": "english",
            "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
        }
        with tempfile.TemporaryDirectory() as temp, patch(
            "scripts.textbook_registry.get_url", side_effect=fake_get_url
        ):
            destination = Path(temp) / "whole-book.pdf"
            digest, pages, toc, method = download_ncert_merged(book, destination)
            self.assertTrue(destination.is_file())
            self.assertTrue(digest)
            self.assertEqual("complete", book["content_availability"]["status"])
            self.assertEqual([1, 2, 3], book["content_availability"]["available_chapters"])
            self.assertEqual(["Chapter 1", "Chapter 2", "Chapter 3"], [row[1] for row in toc])
            with fitz.open(destination) as merged:
                self.assertEqual(4, len(merged))
        self.assertEqual(1, len(requested))
        self.assertTrue(requested[0].endswith("fegp1dd.zip"))

    def test_ncert_whole_book_without_printed_edition_is_rejected(self):
        import io
        import zipfile
        import fitz
        from scripts.textbook_registry import download_ncert_merged

        document = fitz.open()
        page = document.new_page()
        page.insert_text((72, 72), "This complete English mathematics chapter explains examples, methods, and practice for students.")
        payload = document.tobytes()
        document.close()
        bundle = io.BytesIO()
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("fegp101.pdf", payload)
        requested = []

        def fake_get_url(url, destination=None, limit=None, attempts=1):
            requested.append(url)
            destination.write_bytes(bundle.getvalue())
            return destination

        book = {
            "book_id": "ncert-c6-english-fegp1", "code": "fegp1", "chapter_count": 1,
            "title": "Example textbook", "medium": "english",
            "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
        }
        with tempfile.TemporaryDirectory() as temp, patch(
            "scripts.textbook_registry.get_url", side_effect=fake_get_url
        ):
            destination = Path(temp) / "unverified-edition.pdf"
            with self.assertRaisesRegex(RuntimeError, "complete official NCERT whole-book bundle"):
                download_ncert_merged(book, destination)
            self.assertFalse(destination.exists())
        self.assertTrue(all(url.endswith(".zip") for url in requested))
        self.assertEqual("unavailable", book["content_availability"]["status"])

    def test_incomplete_ncert_bundle_fails_closed_without_individual_chapter_downloads(self):
        import io
        import zipfile
        import fitz
        from scripts.textbook_registry import download_ncert_merged

        def make_pdf(label):
            document = fitz.open()
            page = document.new_page()
            page.insert_text((72, 72), label)
            payload = document.tobytes()
            document.close()
            return payload

        bundle = io.BytesIO()
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("fegp101.pdf", make_pdf("Chapter 1 in an incomplete bundle"))
        requested = []

        def fake_get_url(url, destination=None, limit=None, attempts=4):
            requested.append(url)
            if url.endswith("fegp1dd.zip"):
                destination.write_bytes(bundle.getvalue())
                return destination
            raise AssertionError("The downloader must never request individual chapter PDFs: " + url)

        book = {
            "book_id": "ncert-c6-english-fegp1", "code": "fegp1", "chapter_count": 3,
            "title": "Ganita Prakash", "medium": "english", "source_type": "NCERT",
            "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
        }
        with tempfile.TemporaryDirectory() as temp, patch(
            "scripts.textbook_registry.get_url", side_effect=fake_get_url
        ):
            destination = Path(temp) / "partial-book.pdf"
            with self.assertRaisesRegex(RuntimeError, "complete official NCERT whole-book bundle"):
                download_ncert_merged(book, destination)
            self.assertFalse(destination.exists(), "No partial/synthetic book may be retained.")
        self.assertTrue(all(url.endswith(".zip") for url in requested))
        self.assertEqual("unavailable", book["content_availability"]["status"])
        self.assertEqual([1, 2, 3], book["content_availability"]["missing_chapters"])

    def test_ncert_book_with_no_retrievable_chapters_is_described_for_retry(self):
        from scripts.textbook_registry import download_ncert_merged
        book = {
            "book_id": "ncert-c6-english-fegp1", "code": "fegp1", "chapter_count": 2,
            "title": "Ganita Prakash", "medium": "english", "source_type": "NCERT",
            "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
        }
        with tempfile.TemporaryDirectory() as temp, patch(
            "scripts.textbook_registry.get_url", side_effect=FileNotFoundError("source unavailable")
        ):
            with self.assertRaisesRegex(RuntimeError, "complete official NCERT whole-book bundle"):
                download_ncert_merged(book, Path(temp) / "missing.pdf")
        self.assertEqual("unavailable", book["content_availability"]["status"])
        self.assertEqual([1, 2], book["content_availability"]["missing_chapters"])

    @staticmethod
    def make_registry_book(book_id, medium="hindi"):
        return {
            "book_id": book_id,
            "publisher": "SCERT Bihar",
            "source_type": "SCERT_BIHAR",
            "class": 6,
            "classes": [6],
            "medium": medium,
            "language": "Hindi" if medium == "hindi" else "English",
            "title": "Textbook " + book_id,
            "subject": "Mathematics",
            "edition": "Printed edition 2025-26",
            "content_availability": {
                "status": "complete", "expected_chapters": [], "available_chapters": [], "missing_chapters": [],
            },
            "source_url": "https://scert.bihar.gov.in/eresources/sample",
            "catalog_entry_url": "https://scert.bihar.gov.in/eresources/sample",
            "pdf_url": "https://scert.bihar.gov.in/public/uploads/eresources/sample.pdf",
            "bundle_url": "https://scert.bihar.gov.in/public/uploads/eresources/sample.pdf",
            "code": "",
            "chapter_count": None,
        }

    def test_cached_book_requires_complete_status_verified_edition_official_source_and_medium(self):
        valid = self.make_registry_book("valid-cached-book")
        self.assertTrue(_is_complete_whole_book_entry(valid))
        invalid_cases = []
        bad_edition = dict(valid, edition="UNVERIFIED: record the printed edition")
        invalid_cases.append(bad_edition)
        bad_source = dict(valid, source_url="https://books.example.invalid/textbook.pdf")
        invalid_cases.append(bad_source)
        bad_medium = dict(valid, medium="both", language="Hindi English")
        invalid_cases.append(bad_medium)
        missing_coverage = dict(valid)
        missing_coverage.pop("content_availability")
        invalid_cases.append(missing_coverage)
        partial = dict(valid, content_availability={
            "status": "complete", "expected_chapters": [1, 2],
            "available_chapters": [1], "missing_chapters": [2],
        })
        invalid_cases.append(partial)
        for entry in invalid_cases:
            with self.subTest(entry=entry):
                self.assertFalse(_is_complete_whole_book_entry(entry))

    def test_legacy_partial_book_is_removed_and_never_republished(self):
        book = self.make_registry_book("partial-existing")
        old_pdf = b"%PDF-1.4\nold incomplete book\n"
        old_coverage = {
            "status": "partial", "expected_chapters": [1, 2],
            "available_chapters": [1], "missing_chapters": [2],
        }
        entry = {
            **book, "sha256": hashlib.sha256(old_pdf).hexdigest(),
            "bytes": len(old_pdf), "page_count": 1, "toc": [[1, "Chapter 1", 1]],
            "file": "books/partial-existing.pdf", "content_availability": old_coverage,
        }
        existing_index = {
            "schema_version": 1, "books": [entry], "download_status": {
                "partial-existing": {"status": "partial", "content_availability": old_coverage}
            },
        }
        attempts = []

        def pretend_partial_download(item, destination):
            attempts.append(item["book_id"])
            payload = b"%PDF-1.4\\nrecovered but still partial\\n"
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(payload)
            item["content_availability"] = {
                "status": "partial", "expected_chapters": [1, 2],
                "available_chapters": [1], "missing_chapters": [2],
                "missing_chapter_labels": ["Chapter 2"], "note": "Chapter 2 unavailable",
            }
            return hashlib.sha256(payload).hexdigest(), 1, [[1, "Chapter 1", 1]], "PARTIAL NCERT book", destination

        captured_indexes = []

        def fake_push(_image, _exists, _books, _files, index, _context, **kwargs):
            captured_indexes.append((index, kwargs))

        with patch("scripts.textbook_registry.pull_index", return_value=(True, existing_index)), \\
             patch("scripts.textbook_registry._download_full_book", side_effect=pretend_partial_download), \\
             patch("scripts.textbook_registry.push_batch", side_effect=fake_push):
            report = publish_language(
                "hindi", "ghcr.io/example/quantaedge-textbooks-class-6-hindi:latest",
                catalog=[book], class_no=6, download_workers=1, push_batch_size=1, retry_rounds=1,
            )

        self.assertEqual(["partial-existing"], attempts)
        self.assertEqual([], report["already_cached"])
        self.assertEqual(1, report["pending_at_start"])
        self.assertEqual(0, report["partial_book_count"])
        self.assertTrue(any(row["book_id"] == "partial-existing" for row in report["failed_after_retries"]))
        self.assertTrue(captured_indexes, "Removing the legacy partial asset must be persisted to the image.")
        self.assertTrue(all(
            all(item.get("book_id") != "partial-existing" for item in index.get("books", []))
            for index, kwargs in captured_indexes
        ), "The partial book must never be written back to the active books list.")

    def test_existing_registry_is_checked_first_and_new_books_push_as_one_batch(self):
        cached = self.make_registry_book("already-cached")
        new_a = self.make_registry_book("new-a")
        new_b = self.make_registry_book("new-b")
        existing_index = {
            "schema_version": 1,
            "registry": "ghcr.io/example/quantaedge-textbooks-hindi:latest",
            "books": [{**cached, "sha256": "cached-hash", "file": "books/already-cached.pdf"}],
            "download_status": {},
        }
        calls = []

        def fake_download(book, destination):
            calls.append(book["book_id"])
            destination.parent.mkdir(parents=True, exist_ok=True)
            payload = ("%PDF-1.4\n" + book["book_id"] + "\n").encode()
            destination.write_bytes(payload)
            return hashlib.sha256(payload).hexdigest(), 1, [[1, "Chapter 1", 1]], "test whole-book PDF", destination

        with patch("scripts.textbook_registry.pull_index", return_value=(True, existing_index)), \
             patch("scripts.textbook_registry._download_full_book", side_effect=fake_download), \
             patch("scripts.textbook_registry.push_batch") as push, \
             patch("scripts.textbook_registry.log") as progress_log:
            report = publish_language(
                "hindi", "ghcr.io/example/quantaedge-textbooks-hindi:latest",
                catalog=[cached, new_a, new_b], download_workers=2, push_batch_size=2, retry_rounds=1,
            )

        log_messages = [str(call.args[0]) for call in progress_log.call_args_list]
        self.assertTrue(any("pending at start=2" in message for message in log_messages))
        self.assertTrue(any("CACHE_PROGRESS" in message and "pending_downloads=0" in message for message in log_messages))
        self.assertEqual(0, report["pending_after_retries"])
        self.assertEqual(2, report["resolved_candidates"])
        self.assertEqual(["already-cached"], report["already_cached"])
        self.assertCountEqual(["new-a", "new-b"], report["downloaded_and_pushed"])
        self.assertCountEqual(["new-a", "new-b"], calls)
        self.assertEqual(1, push.call_count)
        self.assertEqual("ghcr.io/example/quantaedge-textbooks-hindi:latest", push.call_args.args[0])
        self.assertEqual(2, len(push.call_args.args[2]))

    def test_failed_book_is_retried_without_redownloading_successful_book(self):
        retry_book = self.make_registry_book("retry-book")
        good_book = self.make_registry_book("good-book")
        attempts = {"retry-book": 0, "good-book": 0}

        def fake_download(book, destination):
            book_id = book["book_id"]
            attempts[book_id] += 1
            if book_id == "retry-book" and attempts[book_id] == 1:
                raise RuntimeError("temporary source timeout")
            destination.parent.mkdir(parents=True, exist_ok=True)
            payload = ("%PDF-1.4\n" + book_id + "\n").encode()
            destination.write_bytes(payload)
            return hashlib.sha256(payload).hexdigest(), 1, [[1, "Chapter 1", 1]], "test whole-book PDF", destination

        with patch("scripts.textbook_registry.pull_index", return_value=(False, {
            "schema_version": 1, "books": [], "download_status": {},
        })), patch("scripts.textbook_registry._download_full_book", side_effect=fake_download), \
             patch("scripts.textbook_registry.push_batch") as push:
            report = publish_language(
                "hindi", "ghcr.io/example/quantaedge-textbooks-hindi:latest",
                catalog=[retry_book, good_book], download_workers=2, push_batch_size=2, retry_rounds=2,
            )

        self.assertEqual(2, attempts["retry-book"])
        self.assertEqual(1, attempts["good-book"])
        self.assertCountEqual(["retry-book", "good-book"], report["downloaded_and_pushed"])
        self.assertEqual([], report["failed_after_retries"])
        self.assertEqual(2, push.call_count)

    def test_class_image_reference_is_stable_for_all_fourteen_registries(self):
        from scripts.textbook_registry import class_image_reference

        for grade in range(6, 13):
            for language in ("hindi", "english"):
                with self.subTest(grade=grade, language=language):
                    self.assertEqual(
                        f"ghcr.io/manav326/quantaedge-textbooks-class-{grade}-{language}:latest",
                        class_image_reference("ghcr.io/Manav326/quantaedge-textbooks", grade, language),
                    )

    def test_class_image_reference_rejects_unsupported_classes(self):
        from scripts.textbook_registry import class_image_reference

        with self.assertRaises(ValueError):
            class_image_reference("ghcr.io/manav326/quantaedge-textbooks", 5, "hindi")
        with self.assertRaises(ValueError):
            class_image_reference("ghcr.io/manav326/quantaedge-textbooks", 13, "english")

    def test_persistent_failure_is_reported_after_five_rounds(self):
        book = self.make_registry_book("always-fails")
        attempts = {"count": 0}

        def fail_download(_book, _destination):
            attempts["count"] += 1
            raise RuntimeError("official source stayed unavailable")

        with patch("scripts.textbook_registry.pull_index", return_value=(False, {
            "schema_version": 1, "books": [], "download_status": {},
        })), patch("scripts.textbook_registry._download_full_book", side_effect=fail_download), \
             patch("scripts.textbook_registry.push_batch") as push:
            report = publish_language(
                "hindi", "ghcr.io/example/quantaedge-textbooks-hindi:latest",
                catalog=[book], download_workers=1, push_batch_size=1, retry_rounds=5,
            )

        self.assertEqual(5, attempts["count"])
        self.assertEqual(1, len(report["failed_after_retries"]))
        self.assertEqual(5, report["failed_after_retries"][0]["attempts_this_run"])
        self.assertEqual(5, push.call_count)


    def test_oci_cache_recovery_merges_layers_and_verifies_all_book_checksums(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            layout = root / "oci"
            blobs = layout / "blobs" / "sha256"
            blobs.mkdir(parents=True)
            layout_index = layout / "index.json"

            pdf_a = b"%PDF-1.4\ncache book a\n"
            pdf_b = b"%PDF-1.4\ncache book b\n"
            entry_a = {
                "book_id": "book-a", "file": "books/book-a.pdf",
                "sha256": hashlib.sha256(pdf_a).hexdigest(), "bytes": len(pdf_a),
            }
            entry_b = {
                "book_id": "book-b", "file": "books/book-b.pdf",
                "sha256": hashlib.sha256(pdf_b).hexdigest(), "bytes": len(pdf_b),
            }

            def make_layer(files):
                buffer = io.BytesIO()
                with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
                    for name, content in files:
                        info = tarfile.TarInfo(name)
                        info.size = len(content)
                        archive.addfile(info, io.BytesIO(content))
                raw = buffer.getvalue()
                digest = hashlib.sha256(raw).hexdigest()
                (blobs / digest).write_bytes(raw)
                return {"mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
                        "digest": "sha256:" + digest, "size": len(raw)}

            old_index = json.dumps({"books": [entry_a], "download_status": {}}).encode()
            new_index = json.dumps({"books": [entry_a, entry_b], "download_status": {}}).encode()
            layers = [
                make_layer([("index.json", old_index), ("books/book-a.pdf", pdf_a)]),
                make_layer([("index.json", new_index), ("books/book-b.pdf", pdf_b)]),
            ]
            manifest = {
                "schemaVersion": 2,
                "mediaType": "application/vnd.oci.image.manifest.v1+json",
                "config": {"mediaType": "application/vnd.oci.image.config.v1+json",
                           "digest": "sha256:" + "0" * 64, "size": 0},
                "layers": layers,
            }
            manifest_raw = json.dumps(manifest).encode()
            manifest_digest = hashlib.sha256(manifest_raw).hexdigest()
            (blobs / manifest_digest).write_bytes(manifest_raw)
            layout_index.write_text(json.dumps({
                "schemaVersion": 2,
                "manifests": [{
                    "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "digest": "sha256:" + manifest_digest,
                    "size": len(manifest_raw),
                    "annotations": {"org.opencontainers.image.ref.name": "cache"},
                }],
            }), encoding="utf-8")

            rootfs = root / "rootfs"
            recovered = _extract_oci_cache_rootfs(layout, rootfs)

            self.assertEqual(["book-a", "book-b"], [item["book_id"] for item in recovered["books"]])
            self.assertEqual(pdf_a, (rootfs / "books/book-a.pdf").read_bytes())
            self.assertEqual(pdf_b, (rootfs / "books/book-b.pdf").read_bytes())
            self.assertEqual(new_index, (rootfs / "index.json").read_bytes())

    def test_push_batch_flattens_near_layer_limit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            context = root / "context"
            new_pdf = root / "new-book.pdf"
            old_pdf = b"%PDF-1.4\nalready cached\n"
            new_pdf.write_bytes(b"%PDF-1.4\nnew book\n")
            image = "ghcr.io/example/quantaedge-textbooks-hindi:latest"
            index = {
                "books": [
                    {"book_id": "old-book", "file": "books/old-book.pdf"},
                    {"book_id": "new-book", "file": "books/new-book.pdf"},
                ],
                "download_status": {},
            }

            def fake_run(command, **kwargs):
                if command[:3] == ["docker", "image", "inspect"]:
                    return CompletedProcess(
                        command, 0,
                        stdout=("sha256:previous\n" if command[3] == "--format={{.Id}}" else "89\n"),
                    )
                if command[:2] == ["docker", "cp"]:
                    destination = Path(command[3])
                    destination.mkdir(parents=True, exist_ok=True)
                    (destination / "old-book.pdf").write_bytes(old_pdf)
                return CompletedProcess(command, 0, stdout="")

            with patch("scripts.textbook_registry.subprocess.run", side_effect=fake_run):
                push_batch(
                    image, True, [{"book_id": "new-book"}],
                    {"new-book": new_pdf}, index, context,
                )

            dockerfile = (context / "Dockerfile").read_text(encoding="utf-8")
            self.assertIn("FROM scratch", dockerfile)
            self.assertIn("COPY books/ /books/", dockerfile)
            self.assertTrue((context / "books/old-book.pdf").is_file())
            self.assertTrue((context / "books/new-book.pdf").is_file())


if __name__ == "__main__":
    unittest.main()
