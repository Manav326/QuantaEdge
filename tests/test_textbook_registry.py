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

    def test_textbook_binary_size_is_not_artificially_capped(self):
        self.assertIsNone(MAX_BOOK_BYTES)
        self.assertIsNone(MAX_ZIP_BYTES)

    def test_ncert_bundle_failure_does_not_fall_back_to_individual_chapters(self):
        with tempfile.TemporaryDirectory() as temp:
            book = {
                "book_id": "ncert-c6-english-fegp1",
                "code": "fegp1",
                "chapter_count": 2,
                "title": "Ganita Prakash",
                "medium": "english",
                "bundle_url": "https://ncert.nic.in/textbook/pdf/fegp1dd.zip",
            }
            with patch("scripts.textbook_registry.get_url", side_effect=FileNotFoundError("book bundle missing")) as getter:
                with self.assertRaisesRegex(RuntimeError, "no individual-chapter fallback"):
                    download_ncert_merged(book, Path(temp) / "whole-book.pdf")
            self.assertEqual(2, getter.call_count)
            self.assertTrue(all(call.args[0].endswith("fegp1dd.zip") for call in getter.call_args_list))

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
