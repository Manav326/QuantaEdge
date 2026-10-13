import hashlib,tempfile,unittest
from pathlib import Path
from scripts.audit_curriculum_pipeline import audit_book,audit_extraction,audit_registry,LANG,summary

class PipelineAuditTests(unittest.TestCase):
 def test_language_aliases(self):
  self.assertEqual("hindi",LANG["hi"]);self.assertEqual("english",LANG["en"])
 def test_valid_complete_pdf_hash(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/"b.pdf";raw=b"%PDF-1.4\nfixture\n";p.write_bytes(raw)
   r,b=audit_book(Path(d),{"file":"b.pdf","bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest(),"content_availability":{"status":"complete"}},False)
   self.assertEqual("complete",r["status"]);self.assertTrue(r["sha256_matches"]);self.assertFalse(any(x["severity"]=="BLOCKER" for x in b))
 def test_partial_book_fails(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/"b.pdf";p.write_bytes(b"%PDF-1.4\n")
   r,b=audit_book(Path(d),{"file":"b.pdf","sha256":hashlib.sha256(p.read_bytes()).hexdigest(),"content_availability":{"status":"partial","missing_chapters":[2]}},False)
   self.assertEqual("partial",r["status"]);self.assertIn("PARTIAL_BOOK_NOT_COMPLETE",{x["code"] for x in b})
 def test_hash_and_path_failures(self):
  with tempfile.TemporaryDirectory() as d:
   (Path(d)/"b.pdf").write_bytes(b"%PDF-1.4\n")
   _,b=audit_book(Path(d),{"file":"b.pdf","sha256":"0"*64,"content_availability":{"status":"complete"}},False);self.assertIn("SHA_MISMATCH",{x["code"] for x in b})
   _,b=audit_book(Path(d),{"file":"../secret.pdf"},False);self.assertIn("UNSAFE_PATH",{x["code"] for x in b})
 def test_missing_registry_reports_both_media(self):
  with tempfile.TemporaryDirectory() as d:
   r,b=audit_registry(Path(d),["6"],["hindi","english"],False);self.assertEqual(2,len(r["images"]))
   codes={x["code"] for x in b};self.assertIn("REGISTRY_INDEX_MISSING",codes);self.assertIn("TRACK_BOOK_COVERAGE_MISSING",codes)
 def test_page_gap_and_overlap(self):
  bundle={"schema_version":1,"bundle_status":"DRAFT_EXTRACTION_ONLY","curriculum":{"class_code":"6","subject_code":"maths","language":"hi"},"source":{"pdf_page_count":3,"pdf_sha256":"a"*64},"chapter_map":[{"code":"a","title":"A","page_start":1,"page_end":2},{"code":"b","title":"B","page_start":2,"page_end":3}],"extraction":{"requires_human_review":True},"pages":[{"page_number":1,"chapter_code":"a","text":"one"},{"page_number":3,"chapter_code":"b","text":"three"}]}
  _,bad=audit_extraction(bundle,Path("fixture.json"));codes={x["code"] for x in bad}
  self.assertIn("CHAPTER_RANGE_OVERLAP",codes);self.assertIn("PAGE_COVERAGE_GAP",codes)
 def test_planned_not_actual_count(self):
  r=summary([{"class_code":"6","subject_code":"maths","chapter_code":"a","question_type":"MCQ","minimum_target_count":5,"actual_active_approved_count":2,"target_eligible":True}])
  self.assertEqual(5,r["minimum_question_target"]);self.assertEqual(2,r["documented_baseline_approved_questions"]);self.assertEqual(3,r["remaining_to_target_at_documented_baseline"])
if __name__=="__main__":unittest.main()
