from __future__ import annotations
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from scripts.audit_content_package_completeness import audit_package

def bundle(subject="maths", lesson_code="lesson-one"):
    return {
        "schema":"quantaedge.site-content-authoring-draft.v1",
        "bundle_status":"AI_AUTHORED_DRAFT","publish_status":"DRAFT","auto_publish":False,
        "editorial_state":"PENDING_HUMAN_REVIEW","declaration":{"rights_reviewed":False},
        "curriculum":{"class_code":"6","subject_code":subject,"language":"hi","program_code":"NAVODAYA_BATCH","canonical_track_match":"EXACT"},
        "chapter":{"code":"chapter-one","display_name":"अध्याय एक"},
        "source_alignment":{"status":"PENDING","source_of_truth_candidate":{"title":"NCERT source candidate","url":"https://ncert.nic.in/textbook.php"}},
        "lessons":[{"code":lesson_code,"title":"पाठ एक","estimated_minutes":10,"sort_order":1,
          "blocks":[{"block_type":"EXPLANATION","sequence_no":1,"content":{"body":"यह एक उदाहरण पाठ है।"}}]}],
        "online_question_bank":[{"id":"question-"+lesson_code,"lesson_code":lesson_code,"question_type":"MCQ",
          "prompt":"कौन सा उत्तर सही है?","difficulty":"EASY",
          "options":[{"key":"a","label":"उत्तर एक","correct":True},{"key":"b","label":"उत्तर दो","correct":False}],
          "answer_payload":{"kind":"OPTION","value":"a"}}]
    }

class PackageAuditTests(unittest.TestCase):
    def make_zip(self, root, filename, members):
        path=Path(root)/filename
        with zipfile.ZipFile(path,"w",zipfile.ZIP_DEFLATED) as z:
            for name,obj in members: z.writestr(name,json.dumps(obj,ensure_ascii=False))
        return path
    def test_valid_two_track_draft_package(self):
        with tempfile.TemporaryDirectory() as td:
            r=audit_package(self.make_zip(td,"valid.zip",[
                ("root/content/bundles/maths/a.json",bundle("maths")),
                ("root/content/bundles/science/b.json",bundle("science","lesson-two"))]))
            self.assertEqual(r["zip_integrity"],"PASSED")
            self.assertTrue(r["all_bundles_valid"])
            self.assertTrue(r["structural_draft_import_ready"])
            self.assertEqual(r["totals"]["lessons"],2)
    def test_missing_track_is_incomplete(self):
        with tempfile.TemporaryDirectory() as td:
            r=audit_package(self.make_zip(td,"maths.zip",[
                ("root/content/bundles/maths/a.json",bundle())]))
            self.assertTrue(r["all_bundles_valid"])
            self.assertFalse(r["structural_draft_import_ready"])
    def test_extraction_only_is_not_authoring_input(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/"extract.zip"
            with zipfile.ZipFile(path,"w") as z:
                z.writestr("data/textbook-extractions/source.jsonl",'{"text":"यह स्रोत पाठ है"}\n')
            r=audit_package(path)
            self.assertEqual(r["bundle_json_count"],0)
            self.assertEqual(r["classification"],"NO_IMPORTER_COMPATIBLE_AUTHORING_BUNDLES")
    def test_duplicate_targets_block_ready_status(self):
        with tempfile.TemporaryDirectory() as td:
            first,second=bundle(),bundle()
            second["lessons"][0]["title"]="दूसरा पाठ"
            second["online_question_bank"][0]["prompt"]="अलग प्रश्न?"
            r=audit_package(self.make_zip(td,"dups.zip",[
                ("a/content/bundles/maths/a.json",first),
                ("b/content/bundles/maths/b.json",second),
                ("a/content/bundles/science/c.json",bundle("science","lesson-three"))]))
            self.assertTrue(r["duplicate_chapter_targets"])
            self.assertTrue(r["duplicate_lesson_target_codes"])
            self.assertFalse(r["structural_draft_import_ready"])
    def test_missing_zip_fails_safely(self):
        with tempfile.TemporaryDirectory() as td:
            r=audit_package(Path(td)/"missing.zip")
            self.assertFalse(r["exists"])
            self.assertFalse(r["structural_draft_import_ready"])

if __name__ == "__main__":
    unittest.main()
