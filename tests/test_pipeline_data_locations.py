import unittest

from scripts.validate_pipeline_data_locations import is_generated_pipeline_artifact, validate_paths


class PipelineDataLocationTests(unittest.TestCase):
    def test_generated_pipeline_outputs_outside_data_are_rejected(self):
        violations = validate_paths([
            "apps/admin/public/content/visual-candidates/book.v3.json",
            "apps/web/public/content/visual-assets/book/candidates/figure.jpg",
            "reports/coverage-ledger.v1.json",
            "apps/tools/layer-8-rag-corpus.v1.jsonl",
        ])
        self.assertEqual([
            "apps/admin/public/content/visual-candidates/book.v3.json",
            "apps/tools/layer-8-rag-corpus.v1.jsonl",
            "apps/web/public/content/visual-assets/book/candidates/figure.jpg",
            "reports/coverage-ledger.v1.json",
        ], violations)

    def test_pipeline_artifacts_inside_data_are_allowed(self):
        paths = [
            "data/textbook-content-pipeline/class-6/books/book/coverage-ledger.v1.json",
            "data/textbook-visual-candidates-v3/catalogs/book.v3.json",
            "data/structured-lessons/class-6/science/topic.v1.json",
            "scripts/quantaedge_layers_6_8.py",
            "docs/textbook-content-pipeline-eight-layers.md",
        ]
        self.assertEqual([], validate_paths(paths))

    def test_directory_markers_are_not_allowed_outside_data(self):
        self.assertTrue(is_generated_pipeline_artifact("apps/runtime/textbook-content-pipeline/book/out.json"))
        self.assertTrue(is_generated_pipeline_artifact("apps/runtime/structured-lessons/topic.json"))


if __name__ == "__main__":
    unittest.main()
