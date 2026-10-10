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
            archive.writestr("fegp1ps.pdf", make_pdf("book preliminaries"))
            archive.writestr("fegp101.pdf", make_pdf("chapter 1"))
            archive.writestr("fegp102.pdf", make_pdf("chapter 2"))
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
            self.assertEqual([1, 2], book["content_availability"]["available_chapters"])
            self.assertEqual([], book["content_availability"]["missing_chapters"])
            self.assertIn("no chapter fallback", method.lower())
            with fitz.open(destination) as merged:
                self.assertEqual(3, len(merged))
            self.assertEqual(2, len(toc))

        self.assertEqual(1, len(requested))
        self.assertTrue(requested[0].endswith("fegp1dd.zip"))

    def test_incomplete_ncert_bundle_is_rejected_without_chapter_fallback(self):
        import io
        import zipfile
        import fitz
        from scripts.textbook_registry import download_ncert_merged

        document = fitz.open()
        document.new_page()
        pdf_bytes = document.tobytes()
        document.close()
        bundle = io.BytesIO()
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("fegp101.pdf", pdf_bytes)
        requested = []

        def fake_get_url(url, destination=None, limit=None, attempts=4):
            requested.append(url)
            destination.write_bytes(bundle.getvalue())
            return destination

        book = {
            "book_id": "ncert-c6-english-fegp1", "code": "fegp1", "chapter_count": 3,
            "title": "Ganita Prakash", "medium": "english",
            "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
        }
        with tempfile.TemporaryDirectory() as temp, patch(
            "scripts.textbook_registry.get_url", side_effect=fake_get_url
        ):
            destination = Path(temp) / "incomplete-book.pdf"
            with self.assertRaisesRegex(RuntimeError, "no individual-chapter fallback"):
                download_ncert_merged(book, destination)
            self.assertFalse(destination.exists())
        self.assertEqual(3, len(requested))
        self.assertTrue(all(url.endswith("fegp1dd.zip") for url in requested))
        self.assertEqual("unavailable", book["content_availability"]["status"])
        self.assertEqual([1, 2, 3], book["content_availability"]["missing_chapters"])

    def test_ncert_book_with_no_available_complete_bundle_is_described_for_retry(self):
        from scripts.textbook_registry import download_ncert_merged
        book = {
            "book_id": "ncert-c6-english-fegp1", "code": "fegp1", "chapter_count": 2,
            "title": "Ganita Prakash", "medium": "english",
            "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
        }
        requested = []
        with tempfile.TemporaryDirectory() as temp, patch(
            "scripts.textbook_registry.get_url",
            side_effect=lambda url, destination=None, limit=None, attempts=4: (
                requested.append(url) or (_ for _ in ()).throw(FileNotFoundError("source unavailable"))
            ),
        ):
            destination = Path(temp) / "missing.pdf"
            with self.assertRaisesRegex(RuntimeError, "no individual-chapter fallback"):
                download_ncert_merged(book, destination)
            self.assertFalse(destination.exists())
        self.assertEqual(3, len(requested))
        self.assertTrue(all(url.endswith("fegp1dd.zip") for url in requested))
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
            "edition": "Official listing",
            "source_url": "https://scert.bihar.gov.in/eresources/sample",
            "catalog_entry_url": "https://scert.bihar.gov.in/eresources/sample",
            "pdf_url": "https://scert.bihar.gov.in/public/uploads/eresources/sample.pdf",
            "bundle_url": "https://scert.bihar.gov.in/public/uploads/eresources/sample.pdf",
            "code": "",
            "chapter_count": None,
        }

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

        def fail_full_book(item, destination):
            attempts.append(item["book_id"])
            item["content_availability"] = {"status": "unavailable", "missing_chapters": [1, 2]}
            raise RuntimeError("No complete official book bundle")

        captured_indexes = []

        def fake_push(_image, _exists, _books, _files, index, _context, **kwargs):
            captured_indexes.append((index, kwargs))

        with patch("scripts.textbook_registry.pull_index", return_value=(True, existing_index)), \
             patch("scripts.textbook_registry._download_full_book", side_effect=fail_full_book), \
             patch("scripts.textbook_registry.push_batch", side_effect=fake_push):
            report = publish_language(
                "hindi", "ghcr.io/example/quantaedge-textbooks-class-6-hindi:latest",
                catalog=[book], class_no=6, download_workers=1, push_batch_size=1, retry_rounds=1,
            )

        self.assertEqual(["partial-existing"], attempts)
        self.assertEqual([], report["already_cached"])
        self.assertEqual(1, report["pending_at_start"])
        self.assertEqual(0, report["partial_book_count"])
        self.assertEqual([], report["partial_books"])
        self.assertIn("partial-existing", [item["book_id"] for item in report["failed_after_retries"]])
        self.assertTrue(any(
            all(item.get("book_id") != "partial-existing" for item in index.get("books", []))
            and kwargs.get("force_compact") is True
            for index, kwargs in captured_indexes
        ))

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
