import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "ai_training_dataset.py"
spec = importlib.util.spec_from_file_location("ai_training_dataset", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

def record(eid, prompt, chapter="fractions", lang="hi"):
    return {
      "example_id": eid,
      "messages": [
        {"role":"system","content":"Tutor"},
        {"role":"user","content":prompt},
        {"role":"assistant","content":"यह एक उदाहरणात्मक उत्तर है।"}
      ],
      "metadata":{
        "class_code":"6","subject_code":"maths","language":lang,"chapter_code":chapter,
        "source_id":"source-a","source_url":"https://example.org/book","source_edition":"2025 edition",
        "source_pdf_sha256":"a"*64,"source_pages":[12],"review_status":"approved"
      }
    }

class DatasetTests(unittest.TestCase):
    def test_messages_and_provenance_validate(self):
        self.assertEqual(mod.validate_rows([record("a","भिन्न क्या है?")], True), [])
    def test_missing_provenance_fails(self):
        row=record("a","भिन्न क्या है?"); row["metadata"]["source_pdf_sha256"]="bad"
        self.assertTrue(mod.validate_rows([row]))
    def test_exact_prompt_deduplication(self):
        rows,dropped=mod.deduplicate([record("a","भिन्न क्या है?"),record("b","  भिन्न क्या है? ")])
        self.assertEqual(len(rows),1); self.assertEqual(dropped,1)
    def test_splits_are_deterministic_and_grouped(self):
        rows=[record(str(i),f"Question {i}",chapter=f"chapter-{i}") for i in range(100)]
        one=mod.split_rows(rows); two=mod.split_rows(rows)
        self.assertEqual({k:[r["example_id"] for r in v] for k,v in one.items()},
                         {k:[r["example_id"] for r in v] for k,v in two.items()})
    def test_split_group_never_leaks(self):
        rows=[record("a","Question A"),record("b","Question B")]
        splits=mod.split_rows(rows)
        found={r["example_id"]:name for name,part in splits.items() for r in part}
        self.assertEqual(found["a"],found["b"])
if __name__=="__main__": unittest.main()
