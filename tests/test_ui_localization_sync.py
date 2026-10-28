import hashlib
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.sync_ui_localization import render_android_catalog_resources


class AndroidUiCatalogGenerationTests(unittest.TestCase):
    def test_generates_only_unambiguous_context_safe_pairs(self):
        catalogs = {
            "english": {
                "save.one": "Save changes",
                "save.two": "Save changes",
                "class.title": "Class",
                "class.of": " of ",
                "class.action": "Class",
                "plain.same": "AI Tutor",
            },
            "hinglish": {
                "save.one": "बदलाव सेव करें",
                "save.two": "बदलाव सेव करें",
                "class.title": "कक्षा",
                "class.of": " में से ",
                "class.action": "Class",
                "plain.same": "AI Tutor",
            },
        }
        generated = render_android_catalog_resources(catalogs)
        english = generated["apps/android/app/src/main/res/values/qe_ui_catalog.xml"].decode()
        hinglish = generated["apps/android/app/src/main/res/values-hi/qe_ui_catalog.xml"].decode()
        name = "qe_ui_" + hashlib.sha256("Save changes".encode()).hexdigest()[:16]
        self.assertIn(f'name="{name}" formatted="false">Save changes</string>', english)
        self.assertIn(f'name="{name}" formatted="false">बदलाव सेव करें</string>', hinglish)
        self.assertNotIn("कक्षा", english)
        self.assertNotIn("Class</string>", hinglish)
        self.assertNotIn("AI Tutor", english)
        self.assertNotIn("में से", hinglish)

    def test_escapes_xml_special_characters(self):
        generated = render_android_catalog_resources({
            "english": {"lesson": "Open A & B <source>"},
            "hinglish": {"lesson": "A और B <source> खोलें"},
        })
        english = generated["apps/android/app/src/main/res/values/qe_ui_catalog.xml"].decode()
        hinglish = generated["apps/android/app/src/main/res/values-hi/qe_ui_catalog.xml"].decode()
        self.assertIn("A &amp; B &lt;source&gt;", english)
        self.assertIn("A और B &lt;source&gt; खोलें", hinglish)

    def test_skips_actual_multiline_messages_for_context_review(self):
        generated = render_android_catalog_resources({
            "english": {"multiline": "First line\nSecond line"},
            "hinglish": {"multiline": "पहली पंक्ति\nदूसरी पंक्ति"},
        })
        english = generated["apps/android/app/src/main/res/values/qe_ui_catalog.xml"].decode()
        hinglish = generated["apps/android/app/src/main/res/values-hi/qe_ui_catalog.xml"].decode()
        self.assertNotIn("First line", english)
        self.assertNotIn("पहली पंक्ति", hinglish)

    def test_android_runtime_prefers_unambiguous_central_catalog_before_legacy_phrase_map(self):
        localization = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/AndroidLocalization.kt").read_text(encoding="utf-8")
        central_lookup = localization.index("if (sharedResourceId != 0)")
        legacy_fallback = localization.index("val phrase = uiPhraseBySource")
        self.assertLess(
            central_lookup,
            legacy_fallback,
            "Unambiguous generated catalog entries must take precedence over the legacy phrase map.",
        )

    def test_resource_paths_are_deterministic(self):
        catalogs = {"english": {"x": "Open item"}, "hinglish": {"x": "आइटम खोलें"}}
        self.assertEqual(
            render_android_catalog_resources(catalogs),
            render_android_catalog_resources(catalogs),
        )


if __name__ == "__main__":
    unittest.main()
