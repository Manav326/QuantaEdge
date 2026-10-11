import importlib.util
import io
import json
import os
import tempfile
import unittest
import urllib.error
from email.message import Message
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "import_quantaedge_offline_translations.py"
SPEC = importlib.util.spec_from_file_location("offline_translations", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(module)

# The translation importer and the source importer share this client.
import import_site_content_draft as site_content_importer


class FakeHttpResponse:
    def __init__(self, body: str, set_cookies: list[str] | None = None):
        self._body = body.encode("utf-8")
        self.headers = Message()
        for cookie in set_cookies or []:
            self.headers.add_header("Set-Cookie", cookie)

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False


class StaffSessionRefreshTests(unittest.TestCase):
    def test_401_refreshes_rotated_cookie_pair_and_retries_request(self):
        unauthorized = urllib.error.HTTPError(
            "http://localhost:8080/api/v1/admin/translations/question/1/draft?language=en",
            401,
            "Unauthorized",
            {},
            io.BytesIO(b'{"error":"UNAUTHENTICATED"}'),
        )
        refresh_response = FakeHttpResponse(
            '{"authenticated":true}',
            [
                "QE_STAFF_SESSION=rotated-access; Path=/; HttpOnly; SameSite=Strict",
                "QE_STAFF_REFRESH=rotated-refresh; Path=/; HttpOnly; SameSite=Strict",
            ],
        )
        draft_response = FakeHttpResponse('{"translationStatus":"DRAFT"}')

        with tempfile.TemporaryDirectory() as temp:
            state_path = Path(temp) / "session-state.json"
            env = {
                "QUANTAEDGE_ADMIN_REFRESH": "old-refresh",
                "QE_STAFF_REFRESH": "old-refresh",
                "QUANTAEDGE_SESSION_STATE_FILE": str(state_path),
            }
            with mock.patch.dict(os.environ, env, clear=False):
                api = site_content_importer.AdminApi(
                    "http://localhost:8080", "old-access", timeout=5
                )
                with mock.patch.object(
                    site_content_importer.urllib.request,
                    "urlopen",
                    side_effect=[unauthorized, refresh_response, draft_response],
                ) as urlopen:
                    result = api.request(
                        "POST",
                        "/api/v1/admin/translations/question/1/draft?language=en",
                        {"translatedContent": {"prompt": "Draft"}},
                    )

            self.assertEqual("DRAFT", result["translationStatus"])
            self.assertEqual("rotated-access", api.session_cookie)
            self.assertEqual("rotated-refresh", api.refresh_cookie)
            self.assertEqual(3, urlopen.call_count)
            refresh_request = urlopen.call_args_list[1].args[0]
            retried_request = urlopen.call_args_list[2].args[0]
            self.assertTrue(refresh_request.full_url.endswith("/api/v1/auth/refresh"))
            self.assertEqual("QE_STAFF_REFRESH=old-refresh", refresh_request.get_header("Cookie"))
            self.assertEqual("QE_STAFF_SESSION=rotated-access", retried_request.get_header("Cookie"))
            saved = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertEqual("rotated-access", saved["access_cookie"])
            self.assertEqual("rotated-refresh", saved["refresh_cookie"])


class NumberFidelityTests(unittest.TestCase):
    def test_comma_grouping_and_spaced_math(self):
        self.assertTrue(module.numbers_preserved("2, 7, 12, 17", "2, 7, 12, 17"))

    def test_superscript_and_caret_exponents(self):
        self.assertTrue(module.numbers_preserved("5⁵ gives 3125", "5^5 gives 3125"))

    def test_fraction_decimal_and_changed_values(self):
        self.assertTrue(module.numbers_preserved("3/2 = 1.5", "3/2 = 1.5"))
        self.assertFalse(module.numbers_preserved("3/2 = 1.5", "3/2 = 1.6"))

    def test_written_out_number_can_preserve_source_value(self):
        self.assertTrue(module.numbers_preserved("3 identical items cost ₹27", "Three identical items cost ₹27"))


class PayloadTests(unittest.TestCase):
    def setUp(self):
        self.queue = [
            {"tid":"p6","field":"prompt","source":"3 समान वस्तुओं का मूल्य ₹27 है। एक वस्तु का मूल्य क्या है?",
             "context":{"class":"6","subject":"maths","chapter_code":"ratios","chapter_title":"अनुपात",
                        "question_type":"MCQ","prompt":"3 समान वस्तुओं का मूल्य ₹27 है। एक वस्तु का मूल्य क्या है?"}},
            {"tid":"e6","field":"explanation","source":"27÷3=9।",
             "context":{"class":"6","subject":"maths","chapter_code":"ratios","question_type":"MCQ",
                        "prompt":"3 समान वस्तुओं का मूल्य ₹27 है। एक वस्तु का मूल्य क्या है?"}},
            {"tid":"t6","field":"topic","source":"अनुपात","context":{"class":"6","subject":"maths","chapter_code":"ratios"}},
            {"tid":"s6","field":"subtopic","source":"इकाई दर","context":{"class":"6","subject":"maths","chapter_code":"ratios"}},
            {"tid":"sk6","field":"skill","source":"अवधारणा, अनुप्रयोग और तर्क","context":{"class":"6","subject":"maths"}},
            {"tid":"oB","field":"option_label_MCQ","source":"₹9",
             "context":{"class":"6","subject":"maths","chapter_code":"ratios","question_type":"MCQ",
                        "prompt":"3 समान वस्तुओं का मूल्य ₹27 है। एक वस्तु का मूल्य क्या है?","option_key":"B","option_index":2}},
        ]
        self.translations = {"p6":"Three identical items cost ₹27. What is the cost of one item?",
                             "e6":"27 ÷ 3 = 9.","t6":"Ratio","s6":"Unit rate",
                             "sk6":"Conceptual understanding, application, and reasoning","oB":"₹9"}
        self.row = {"class_code":"6","subject_code":"maths","chapter_code":"ratios","chapter_title":"अनुपात",
                    "question_type":"MCQ","prompt":self.queue[0]["source"],"explanation":"27÷3=9।",
                    "topic":"अनुपात","subtopic":"इकाई दर","skill":"अवधारणा, अनुप्रयोग और तर्क",
                    "options":[{"key":"A","label":"₹8"},{"key":"B","label":"₹9"}],"tags":["hindi","class-6"]}

    def test_option_keys_order_and_tags_are_preserved(self):
        payload, issues = module.question_payload(self.row, self._index(self.queue), self.translations)
        self.assertEqual([], issues)
        self.assertEqual([{"key":"A","label":"₹8"},{"key":"B","label":"₹9"}], payload["options"])
        self.assertEqual(["hindi","class-6"], payload["tags"])
        self.assertEqual("Three identical items cost ₹27. What is the cost of one item?", payload["prompt"])

    def test_missing_prompt_translation_blocks_partial_payload(self):
        queue = [item for item in self.queue if item["field"] != "prompt"]
        payload, issues = module.question_payload(self.row, self._index(queue), self.translations)
        self.assertIsNone(payload)
        self.assertTrue(any("prompt" in issue for issue in issues))

    def test_repeated_source_prefers_matching_chapter(self):
        queue = self.queue + [{"tid":"other","field":"topic","source":"अनुपात",
            "context":{"class":"8","subject":"maths","chapter_code":"different"}}]
        translations = dict(self.translations, other="Different topic")
        got = module.resolve(self._index(queue), translations, "topic", "अनुपात", self.row)
        self.assertEqual("Ratio", got)

    def test_true_false_has_contextual_safe_fallback(self):
        row = dict(self.row, question_type="TRUE_FALSE",
                   options=[{"key":"A","label":"सही"},{"key":"B","label":"गलत"}])
        payload, issues = module.question_payload(row, self._index(self.queue), self.translations)
        self.assertEqual([], issues)
        self.assertEqual([{"key":"A","label":"True"},{"key":"B","label":"False"}], payload["options"])

    @staticmethod
    def _index(queue):
        out = {}
        for item in queue:
            out.setdefault((item["field"], str(item["source"]).strip()), []).append(item)
        return out


if __name__ == "__main__":
    unittest.main()
