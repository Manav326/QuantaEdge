import hashlib
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.sync_ui_localization import load_catalogs, load_locales, render_android_catalog_resources


def sample_locales():
    return {
        "english": {"androidQualifier": ""},
        "hinglish": {"androidQualifier": "b+hi+Latn"},
        "pure-hindi": {"androidQualifier": "b+hi+Deva"},
    }


class AndroidUiCatalogGenerationTests(unittest.TestCase):
    def test_generates_script_aware_resources_from_all_three_catalogs(self):
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
                "save.one": "Changes सेव करें",
                "save.two": "Changes सेव करें",
                "class.title": "कक्षा",
                "class.of": " में से ",
                "class.action": "Class",
                "plain.same": "AI Tutor",
            },
            "pure-hindi": {
                "save.one": "बदलाव सहेजें",
                "save.two": "बदलाव सहेजें",
                "class.title": "कक्षा",
                "class.of": " में से ",
                "class.action": "वर्ग",
                "plain.same": "AI Tutor",
            },
        }
        generated = render_android_catalog_resources(catalogs, sample_locales())
        english = generated["apps/android/app/src/main/res/values/qe_ui_catalog.xml"].decode()
        hinglish = generated["apps/android/app/src/main/res/values-b+hi+Latn/qe_ui_catalog.xml"].decode()
        hindi = generated["apps/android/app/src/main/res/values-b+hi+Deva/qe_ui_catalog.xml"].decode()
        name = "qe_ui_" + hashlib.sha256("Save changes".encode()).hexdigest()[:16]

        self.assertIn(f'name="{name}" formatted="false">Save changes</string>', english)
        self.assertIn(f'name="{name}" formatted="false">Changes सेव करें</string>', hinglish)
        self.assertIn(f'name="{name}" formatted="false">बदलाव सहेजें</string>', hindi)
        # A repeated English phrase with conflicting translations must fall back to English.
        class_name = "qe_ui_" + hashlib.sha256("Class".encode()).hexdigest()[:16]
        self.assertIn(f'name="{class_name}"', english)
        self.assertNotIn(f'name="{class_name}"', hinglish)
        self.assertNotIn(f'name="{class_name}"', hindi)
        self.assertNotIn("में से", hinglish)
        self.assertNotIn("में से", hindi)
        # Unchanged labels and whitespace fragments are intentionally omitted.
        self.assertNotIn("AI Tutor", hinglish)
        self.assertNotIn("AI Tutor", hindi)

    def test_escapes_xml_special_characters_for_every_registered_locale(self):
        generated = render_android_catalog_resources({
            "english": {"lesson": "Open A & B <source>"},
            "hinglish": {"lesson": "A और B <source> खोलें"},
            "pure-hindi": {"lesson": "A और B <स्रोत> खोलें"},
        }, sample_locales())
        english = generated["apps/android/app/src/main/res/values/qe_ui_catalog.xml"].decode()
        hinglish = generated["apps/android/app/src/main/res/values-b+hi+Latn/qe_ui_catalog.xml"].decode()
        hindi = generated["apps/android/app/src/main/res/values-b+hi+Deva/qe_ui_catalog.xml"].decode()
        self.assertIn("A &amp; B &lt;source&gt;", english)
        self.assertIn("A और B &lt;source&gt; खोलें", hinglish)
        self.assertIn("A और B &lt;स्रोत&gt; खोलें", hindi)

    def test_skips_actual_multiline_messages_for_context_review(self):
        generated = render_android_catalog_resources({
            "english": {"multiline": "First line\\nSecond line"},
            "hinglish": {"multiline": "पहली पंक्ति\\nदूसरी पंक्ति"},
            "pure-hindi": {"multiline": "पहली पंक्ति\\nदूसरी पंक्ति"},
        }, sample_locales())
        for path, data in generated.items():
            if path.endswith("qe_ui_catalog.xml"):
                self.assertNotIn("First line", data.decode())
                self.assertNotIn("पहली पंक्ति", data.decode())

    def test_android_runtime_uses_script_specific_composition_locale(self):
        localization = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/AndroidLocalization.kt").read_text(encoding="utf-8")
        self.assertIn("LocalQuantaUiLocale", localization)
        self.assertIn('"pure-hindi" -> "hi-Deva"', localization)
        self.assertIn('"hinglish" -> "hi-Latn"', localization)
        self.assertIn("interfaceLocaleLanguageTag(uiLocale)", localization)

    def test_resource_paths_are_deterministic(self):
        catalogs = {
            "english": {"x": "Open item"},
            "hinglish": {"x": "Item खोलें"},
            "pure-hindi": {"x": "मद खोलें"},
        }
        self.assertEqual(
            render_android_catalog_resources(catalogs, sample_locales()),
            render_android_catalog_resources(catalogs, sample_locales()),
        )

    def test_real_central_catalogs_have_matching_keys_and_message_placeholders(self):
        locales = load_locales()
        catalogs = load_catalogs(locales)
        english_keys = set(catalogs["english"])
        self.assertEqual(set(locales), set(catalogs))
        for locale, catalog in catalogs.items():
            self.assertEqual(english_keys, set(catalog), locale)
            for key in english_keys:
                self.assertEqual(
                    sorted(__import__("re").findall(r"\\{([a-zA-Z][a-zA-Z0-9_]*)\\}", catalogs["english"][key])),
                    sorted(__import__("re").findall(r"\\{([a-zA-Z][a-zA-Z0-9_]*)\\}", catalog[key])),
                    f"placeholder mismatch: {locale}.{key}",
                )


if __name__ == "__main__":
    unittest.main()
