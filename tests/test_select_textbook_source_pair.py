import unittest

from scripts.plan_textbook_layer45_matrix import BOOKS, BOOK_PAIRS
from scripts.select_textbook_source_pair import build_selection
from scripts.quantaedge_textbook_pipeline import book_batch_limit


def make_payload(grade: int, language: str) -> dict:
    sources = []
    for book in BOOKS:
        if int(book["class_no"]) != grade:
            continue
        book_id = book["book_id"]
        slug = book["book_slug"]
        if "science" in slug:
            subject, part = "science", None
        elif "maths-part2" in slug:
            subject, part = "maths", 2
        elif "maths" in slug:
            subject, part = "maths", 1
        else:
            raise AssertionError(f"Unrecognized source slug: {slug}")
        if ("english" in book_id) != (language == "english"):
            continue
        sources.append({
            "class": grade,
            "language": language,
            "book_id": book_id,
            "book_slug": slug,
            "title": book_id,
            "subject": subject,
            "maths_part": part,
            "file": f"books/{book_id}.pdf",
            "sha256": "a" * 64,
        })
    return {
        "schema_version": "quantaedge.visual-source-selection.v1",
        "class": grade,
        "language": language,
        "language_count": 1,
        "expected_source_count": len(sources),
        "sources": sources,
        "publication_allowed": False,
        "review_required": True,
    }


class TextbookSourcePairHandoffTests(unittest.TestCase):
    def test_pair_selects_only_the_assigned_english_and_hindi_books(self):
        pair = next(row for row in BOOK_PAIRS if row["class_no"] == "7" and row["book_label"] == "Maths Part I")
        full, selected, scope = build_selection(
            make_payload(7, "english"),
            make_payload(7, "hindi"),
            grade=7,
            book_ids_csv=pair["book_ids_csv"],
            source_slugs_csv=pair["source_slugs_csv"],
            book_pair_id=pair["book_pair_id"],
            book_label=pair["book_label"],
        )
        self.assertEqual(6, len(full["sources"]))
        self.assertEqual(2, len(selected["sources"]))
        self.assertEqual(set(pair["book_ids_csv"].split(",")), {item["book_id"] for item in selected["sources"]})
        self.assertEqual(set(pair["source_slugs_csv"].split(",")), {item["book_slug"] for item in selected["sources"]})
        self.assertEqual(["english", "hindi"], [item["language"] for item in selected["sources"]])
        self.assertEqual("bilingual_book_pair", scope["mode"])
        self.assertEqual(pair["book_pair_id"], scope["book_pair_id"])
        self.assertIs(full["publication_allowed"], False)
        self.assertIs(selected["publication_allowed"], False)

    def test_class_wide_handoff_preserves_every_source_for_a_non_pair_run(self):
        full, selected, scope = build_selection(
            make_payload(8, "english"), make_payload(8, "hindi"), grade=8
        )
        self.assertEqual(6, len(full["sources"]))
        self.assertEqual(6, len(selected["sources"]))
        self.assertEqual("class_wide", scope["mode"])

    def test_pair_rejects_a_book_id_mapped_to_the_wrong_slug(self):
        pair = next(row for row in BOOK_PAIRS if row["class_no"] == "6" and row["book_label"] == "Science")
        wrong_slugs = pair["source_slugs_csv"].split(",")
        wrong_slugs[0] = pair["source_slugs_csv"].split(",")[1]
        with self.assertRaisesRegex(ValueError, "ID to source slug handoff mismatch"):
            build_selection(
                make_payload(6, "english"), make_payload(6, "hindi"), grade=6,
                book_ids_csv=pair["book_ids_csv"],
                source_slugs_csv=",".join(wrong_slugs),
                book_pair_id=pair["book_pair_id"],
                book_label=pair["book_label"],
            )

    def test_pair_rejects_missing_hindi_source_instead_of_falling_back_to_class_wide(self):
        pair = next(row for row in BOOK_PAIRS if row["class_no"] == "6" and row["book_label"] == "Science")
        hindi = make_payload(6, "hindi")
        assigned_ids = set(pair["book_ids_csv"].split(","))
        hindi["sources"] = [item for item in hindi["sources"] if item["book_id"] not in assigned_ids]
        with self.assertRaisesRegex(ValueError, "expected 2 whole-book sources|exactly one assigned English and one assigned Hindi"):
            build_selection(
                make_payload(6, "english"), hindi, grade=6,
                book_ids_csv=pair["book_ids_csv"],
                source_slugs_csv=pair["source_slugs_csv"],
                book_pair_id=pair["book_pair_id"],
                book_label=pair["book_label"],
            )

    def test_pair_rejects_cross_subject_or_cross_part_handoffs(self):
        pair = next(row for row in BOOK_PAIRS if row["class_no"] == "7" and row["book_label"] == "Maths Part I")
        hindi = make_payload(7, "hindi")
        assigned_hindi_id = pair["book_ids_csv"].split(",")[1]
        assigned_hindi = next(item for item in hindi["sources"] if item["book_id"] == assigned_hindi_id)
        assigned_hindi["maths_part"] = 2
        with self.assertRaisesRegex(ValueError, "do not describe the same subject/book part"):
            build_selection(
                make_payload(7, "english"), hindi, grade=7,
                book_ids_csv=pair["book_ids_csv"],
                source_slugs_csv=pair["source_slugs_csv"],
                book_pair_id=pair["book_pair_id"],
                book_label=pair["book_label"],
            )

    def test_batch_budget_is_shared_fairly_across_whole_book_sources(self):
        self.assertEqual(6, book_batch_limit(12, 2), "Both books in a subject pair get up to six batches.")
        self.assertEqual(3, book_batch_limit(12, 4), "Class 6 class-wide analysis shares its cap across four books.")
        self.assertEqual(2, book_batch_limit(12, 6), "Class 7/8 class-wide analysis shares its cap across six books.")
        with self.assertRaisesRegex(ValueError, "At least one"):
            book_batch_limit(12, 0)
        with self.assertRaisesRegex(ValueError, "positive"):
            book_batch_limit(0, 2)


if __name__ == "__main__":
    unittest.main()
