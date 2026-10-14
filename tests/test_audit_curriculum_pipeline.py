import hashlib,tempfile,unittest
from pathlib import Path
from scripts.audit_curriculum_pipeline import audit_book,audit_extraction,audit_registry,LANG,summary,reconcile_coverage,entry_identity_issues

class PipelineAuditTests(unittest.TestCase):
 def test_language_aliases(self):
  self.assertEqual("hindi",LANG["hi"]);self.assertEqual("english",LANG["en"])
 def test_valid_complete_pdf_hash(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/"b.pdf";raw=b"%PDF-1.4\nfixture\n";p.write_bytes(raw)
   r,b=audit_book(Path(d),{"file":"b.pdf","bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest(),"edition":"2025-26","source_url":"https://scert.bihar.gov.in/textbooks","content_availability":{"status":"complete"}},False)
   self.assertEqual("complete",r["status"]);self.assertTrue(r["sha256_matches"]);self.assertFalse(any(x["severity"]=="BLOCKER" for x in b))
 def test_book_claimed_edition_must_match_a_printed_pdf_imprint(self):
  import fitz
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/"b.pdf"
   pdf=fitz.open();page=pdf.new_page();page.insert_text((72,72),"This is sufficiently long English science text explaining definitions, concepts, examples, and practice exercises for students.");pdf.save(p);pdf.close()
   r,b=audit_book(Path(d),{"file":"b.pdf","bytes":p.stat().st_size,"sha256":hashlib.sha256(p.read_bytes()).hexdigest(),"edition":"Printed edition 2025-26","source_url":"https://scert.bihar.gov.in/eresources","medium":"english","content_availability":{"status":"complete"}},True)
   self.assertIn("EDITION_PRINTED_IMPRINT_NOT_FOUND",{x["code"] for x in b})
 def test_registry_placeholder_edition_phrases_are_blockers(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/"b.pdf";raw=b"%PDF-1.4\\nfixture\\n";p.write_bytes(raw)
   _,issues=audit_book(Path(d),{"file":"b.pdf","bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest(),"edition":"Current official catalogue; retain printed reprint year from the source PDF","source_url":"https://scert.bihar.gov.in/eresources","content_availability":{"status":"complete"}},False)
   self.assertIn("EDITION_UNVERIFIED",{x["code"] for x in issues})

 def test_book_medium_and_class_are_matched_to_isolated_registry(self):
  issues=entry_identity_issues({
   "book_id":"wrong-track","class":"7","classes":[7],"medium":"english","language":"English"
  },"6","hindi")
  codes={x["code"] for x in issues}
  self.assertIn("BOOK_CLASS_SCOPE_MISMATCH",codes)
  self.assertIn("BOOK_MEDIUM_MISMATCH",codes)

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
   r,b=audit_registry(Path(d),["6"],["hindi","english"]);self.assertEqual(2,len(r["images"]))
   codes={x["code"] for x in b};self.assertIn("REGISTRY_INDEX_MISSING",codes);self.assertIn("TRACK_BOOK_COVERAGE_MISSING",codes)
 def test_page_gap_and_overlap(self):
  bundle={"schema_version":1,"bundle_status":"DRAFT_EXTRACTION_ONLY","curriculum":{"class_code":"6","subject_code":"maths","language":"hi"},"source":{"pdf_page_count":3,"pdf_sha256":"a"*64},"chapter_map":[{"code":"a","title":"A","page_start":1,"page_end":2},{"code":"b","title":"B","page_start":2,"page_end":3}],"extraction":{"requires_human_review":True},"pages":[{"page_number":1,"chapter_code":"a","text":"one"},{"page_number":3,"chapter_code":"b","text":"three"}]}
  _,bad=audit_extraction(bundle,Path("fixture.json"));codes={x["code"] for x in bad}
  self.assertIn("CHAPTER_RANGE_OVERLAP",codes);self.assertIn("PAGE_COVERAGE_GAP",codes);self.assertIn("EXTRACTION_LANGUAGE_MISMATCH",codes)
 def test_bilingual_reconciliation_reports_missing_chapters_and_type_targets(self):
  targets=[{"class_code":"6","subject_code":"maths","chapter_code":"one","question_type":"MCQ","minimum_target_count":2,"actual_active_approved_count":0,"target_eligible":True}]
  extraction={"configured":True,"items":[{"class_code":"6","subject_code":"maths","language":"hindi","chapter_codes":["one"]}]}
  candidates={"configured":True,"items":[{"class_code":"6","subject_code":"maths","language":"hindi","chapter_code":"one","question_type_counts":{"MCQ":1}}]}
  reviewed={"configured":True,"items":[]}
  result,issues=reconcile_coverage(targets,["6"],["hindi","english"],extraction,candidates,reviewed)
  self.assertEqual(2,result["expected_chapter_track_language_rows"])
  self.assertEqual(1,result["extraction_rows_missing"])
  self.assertEqual(1,result["candidate_rows_missing"])
  self.assertEqual(3,result["missing_question_total"])
  codes={x["code"] for x in issues}
  self.assertIn("BILINGUAL_CHAPTER_COVERAGE_GAP",codes)
  self.assertIn("BILINGUAL_CANDIDATE_COVERAGE_GAP",codes)
  self.assertIn("BILINGUAL_QUESTION_TARGET_GAPS",codes)

 def test_planned_not_actual_count(self):
  r=summary([{"class_code":"6","subject_code":"maths","chapter_code":"a","question_type":"MCQ","minimum_target_count":5,"actual_active_approved_count":2,"target_eligible":True}])
  self.assertEqual(5,r["minimum_question_target"]);self.assertEqual(2,r["documented_baseline_approved_questions"]);self.assertEqual(3,r["remaining_to_target_at_documented_baseline"])
if __name__=="__main__":unittest.main()
