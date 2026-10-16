import hashlib,json,tempfile,unittest
from pathlib import Path
from scripts.quantaedge_textbook_pipeline import build_coverage_ledger,changed_correction_paths
def w(p,v):p.write_text(json.dumps(v)+"\n",encoding="utf-8")
class PipelineTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.sha="a"*64;self.ash=hashlib.sha256(b"x").hexdigest()
  self.inv=self.root/"i.json";self.raw=self.root/"r.json";self.man=self.root/"m.json";self.txt=self.root/"p.jsonl";self.eff=self.root/"eff.json";self.ledger=self.root/"ledger.json"
  w(self.inv,{"schema_version":"quantaedge.visual-inventory.v1","source":{"book_id":"b","pdf_sha256":self.sha,"pdf_page_count":1,"class":"6"},"pages":[{"source_pdf_page_number":1}],"assets":[{"asset_id":"a1","kind":"page_preview","source_pdf_page_number":1,"asset_sha256":self.ash}]})
  w(self.man,{"book_id":"b","original_book_sha256":self.sha});self.txt.write_text(json.dumps({"source_book_page_number":1,"text":"Water","review_status":"TEXT_EXTRACTED"})+"\n")
  self.analysis={"visual_summary":"Water diagram","visual_type":"diagram","visible_text":[],"concepts":["water"],"context_relation":"near text","limitations":[]}
  w(self.raw,{"schema_version":"quantaedge.visual-analysis.v1","source_book_id":"b","source_pdf_sha256":self.sha,"model":"vision","prompt_version":"p1","results":[{"asset_id":"a1","asset_sha256":self.ash,"analysis_status":"DRAFT_REQUIRES_REVIEW","analysis":self.analysis}]})
 def build(self,c=None):return build_coverage_ledger(self.inv,self.raw,self.man,self.txt,self.eff,self.ledger,corrections_path=c,repo_root=self.root)
 def test_complete_inventory(self):
  r=self.build();self.assertEqual("COMPLETE",r["ledger_status"]);self.assertEqual(1,len(r["pages"]));self.assertEqual(1,len(r["visuals"]))
 def test_missing_analysis_is_not_dropped(self):
  r=json.loads(self.raw.read_text());r["results"]=[];w(self.raw,r);out=self.build();self.assertEqual("INCOMPLETE",out["ledger_status"]);self.assertEqual("ANALYSIS_MISSING",out["visuals"][0]["analysis_status"])
 def test_correction_changes_effective_not_raw(self):
  before=self.raw.read_bytes();p=self.root/"c.json";w(p,{"schema_version":"quantaedge.visual-analysis-corrections.v1","source_pdf_sha256":self.sha,"corrections":[{"asset_id":"a1","asset_sha256":self.ash,"status":"ACCEPTED","reviewer":"editor","reason":"correct","analysis_patch":{"visual_summary":"Corrected"}}]})
  out=self.build(p);e=json.loads(self.eff.read_text());self.assertEqual("Corrected",e["results"][0]["effective_analysis"]["visual_summary"]);self.assertEqual(before,self.raw.read_bytes());self.assertEqual(1,out["summary"]["corrections_applied"])
 def test_bad_hash_overlay_rejected(self):
  p=self.root/"c.json";w(p,{"schema_version":"quantaedge.visual-analysis-corrections.v1","source_pdf_sha256":"b"*64,"corrections":[]})
  with self.assertRaisesRegex(ValueError,"hash mismatch"):self.build(p)
 def test_changed_overlay_paths_detected(self):
  ev=self.root/"event.json";w(ev,{"commits":[{"added":["data/x/books/b/visual-analysis-corrections.v1.json"],"removed":["data/y/books/c/visual-analysis-corrections.v1.json"]}]})
  rows=changed_correction_paths(ev,self.root);self.assertEqual(2,len(rows));self.assertEqual(("b",True),(rows[0][0].parent.name,rows[0][1]));self.assertEqual(("c",False),(rows[1][0].parent.name,rows[1][1]))
if __name__=="__main__":unittest.main()
